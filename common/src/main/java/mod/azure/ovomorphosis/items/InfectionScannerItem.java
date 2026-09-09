package mod.azure.ovomorphosis.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.compat.AVPCompat;
import mod.azure.ovomorphosis.compat.GigeresqueCompat;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.services.XenoServices;

public class InfectionScannerItem extends Item {

    private static final int MAX_DAMAGE = 32;

    public static final int MODEL_CLEAR = 0;

    public static final int MODEL_SYMPTOMATIC = 1;

    public static final int MODEL_CRITICAL = 2;

    public InfectionScannerItem() {
        super(new Item.Properties().durability(MAX_DAMAGE).stacksTo(1));
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(
        @NotNull Level level,
        @NotNull Player player,
        @NotNull InteractionHand hand
    ) {
        var stack = player.getItemInHand(hand);

        if (player.getCooldowns().isOnCooldown(this) || isScanning(stack)) {
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }

        var target = findLookTarget(player, level);
        var targetId = Objects.requireNonNullElse(target, player).getUUID();

        beginScan(stack, targetId, level.getGameTime());

        level.playSound(
            null,
            player.blockPosition(),
            SoundEvents.NOTE_BLOCK_PLING.value(),
            SoundSource.PLAYERS,
            CommonMod.getConfig().itemConfigs.infectionScannerSoundVolume,
            0.7F
        );

        return InteractionResultHolder.success(stack);
    }

    @Override
    public void inventoryTick(
        @NotNull ItemStack stack,
        @NotNull Level level,
        @NotNull Entity entity,
        int slotId,
        boolean isSelected
    ) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);

        if (level.isClientSide()) {
            return;
        }

        tickScanProgress(stack, level, entity);
        tickDecay(stack, level);
    }

    /**
     * Advances an in-progress scan: plays periodic beeps while charging, and once SCAN_DELAY_TICKS has elapsed,
     * resolves the locked-in target and reports the result.
     */
    private void tickScanProgress(ItemStack stack, Level level, Entity entity) {
        if (!isScanning(stack)) {
            return;
        }

        if (!(entity instanceof Player player)) {
            return;
        }

        var elapsed = level.getGameTime() - getScanStart(stack);

        if (elapsed >= 60) {
            finishScan(stack, player, level);
            return;
        }

        if (elapsed % 5 == 0) {
            level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.PLAYERS,
                CommonMod.getConfig().itemConfigs.infectionScannerSoundVolume,
                2.0F
            );
        }
    }

    /**
     * Resolves the target that was locked in when the scan started, runs the actual infection check, and applies
     * durability/cooldown now that the reading is complete.
     */
    private void finishScan(ItemStack stack, Player player, Level level) {
        var targetId = getScanTarget(stack);
        clearScanState(stack);

        LivingEntity target = player;

        if (targetId != null && !targetId.equals(player.getUUID()) && level instanceof ServerLevel serverLevel) {
            var resolved = serverLevel.getEntity(targetId);
            if (resolved instanceof LivingEntity living && living.isAlive()) {
                target = living;
            }
        }

        scanEntity(target, player, stack);

        stack.hurtAndBreak(1, player, player.getEquipmentSlotForItem(stack));
        player.getCooldowns().addCooldown(this, 30);
    }

    /**
     * Ticks the decay timer for a completed reading. Once the reading has been displayed for DECAY_TICKS, the model
     * resets to neutral (MODEL_CLEAR / no CustomModelData).
     */
    private void tickDecay(ItemStack stack, Level level) {
        var cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd == null || cmd.value() <= MODEL_CLEAR) {
            return;
        }

        var scanTime = getScanTime(stack);
        if (scanTime <= 0) {
            return;
        }

        if (level.getGameTime() - scanTime >= 100L) {
            setScannerModel(stack, MODEL_CLEAR);
            clearScanTime(stack);
        }
    }

    private void scanEntity(LivingEntity target, Player scanner, ItemStack stack) {
        var level = scanner.level();

        if (XenoServices.COMMON_REGISTRY.isModLoaded("gigeresque")) {
            if (GigeresqueCompat.tryScanGigInfection(target, scanner, stack)) {
                return;
            }
        }

        if (XenoServices.COMMON_REGISTRY.isModLoaded("avp_alien")) {
            if (AVPCompat.tryScanAVPInfection(target, scanner, stack)) {
                return;
            }
        }

        var infected = InfectionManager.isInfected(target);
        var isSelf = target == scanner;

        if (infected) {
            var phase = InfectionManager.getPhase(target);
            if (phase == null) {
                return;
            }
            var remainingTicks = InfectionManager.getInfectionRemainingTime(target);
            var phaseStr = switch (phase) {
                case DORMANT -> "DORMANT";
                case SYMPTOMATIC -> "SYMPTOMATIC";
                case CRITICAL -> "CRITICAL";
            };

            var modelData = switch (phase) {
                case DORMANT -> MODEL_CLEAR;
                case SYMPTOMATIC -> MODEL_SYMPTOMATIC;
                case CRITICAL -> MODEL_CRITICAL;
            };

            setScannerModel(stack, modelData);
            if (modelData > MODEL_CLEAR) {
                setScanTime(stack, level.getGameTime());
            } else {
                clearScanTime(stack);
            }

            var who = isSelf
                ? Component.translatable("item.ovomorphosis.infection_scanner.tooltip.self")
                : target.getDisplayName();

            var phaseKey = Component.translatable(
                "item.ovomorphosis.infection_scanner.tooltip.stage." + phaseStr.toLowerCase(Locale.ROOT)
            );

            if (CommonMod.getConfig().itemConfigs.disableInfectionScannerTimeOutput) {
                scanner.displayClientMessage(
                    Component.translatable(
                        "item.ovomorphosis.infection_scanner.tooltip.infected",
                        who,
                        phaseKey,
                        remainingTicks / 20
                    ).withStyle(ChatFormatting.RED),
                    true
                );
            } else {
                scanner.displayClientMessage(
                    Component.translatable(
                        "item.ovomorphosis.infection_scanner.tooltip.infected_no_time",
                        who,
                        phaseKey
                    ).withStyle(ChatFormatting.RED),
                    true
                );
            }

            level.playSound(
                null,
                scanner.blockPosition(),
                SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.PLAYERS,
                CommonMod.getConfig().itemConfigs.infectionScannerSoundVolume,
                0.5F
            );
        } else {
            setScannerModel(stack, MODEL_CLEAR);
            clearScanTime(stack);

            var who = isSelf
                ? Component.translatable("item.ovomorphosis.infection_scanner.tooltip.self")
                : target.getDisplayName();

            scanner.displayClientMessage(
                Component.translatable(
                    "item.ovomorphosis.infection_scanner.tooltip.clear",
                    who
                ).withStyle(ChatFormatting.GREEN),
                true
            );

            level.playSound(
                null,
                scanner.blockPosition(),
                SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.PLAYERS,
                CommonMod.getConfig().itemConfigs.infectionScannerSoundVolume,
                1.5F
            );
        }
    }

    /**
     * Finds the nearest living entity the player is roughly looking at within SCAN_RANGE. Returns null if none found
     * (triggers self-scan).
     */
    public static LivingEntity findLookTarget(Player player, Level level) {
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();

        return level.getEntitiesOfClass(
            LivingEntity.class,
            new AABB(player.blockPosition()).inflate(4),
            e -> e != player && e.isAlive()
        )
            .stream()
            .filter(e -> {
                var toEntity = e.getEyePosition().subtract(eyePos).normalize();
                return toEntity.dot(lookVec) > 0.85;
            })
            .min((a, b) -> {
                var dA = a.getEyePosition().subtract(eyePos).cross(lookVec).lengthSqr();
                var dB = b.getEyePosition().subtract(eyePos).cross(lookVec).lengthSqr();
                return Double.compare(dA, dB);
            })
            .orElse(null);
    }

    @Override
    public void appendHoverText(
        @NotNull ItemStack stack,
        @NotNull TooltipContext context,
        @NotNull List<Component> components,
        @NotNull TooltipFlag flag
    ) {
        components.add(
            Component.translatable("item.ovomorphosis.infection_scanner.tooltip")
                .withStyle(ChatFormatting.GRAY)
        );
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return false;
    }

    public static void setScannerModel(ItemStack stack, int customModelData) {
        if (customModelData <= 0) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
            return;
        }

        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(customModelData));
    }

    public static void setScanTime(ItemStack stack, long time) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> {
            var tag = data.copyTag();
            tag.putLong("ScanTime", time);
            return CustomData.of(tag);
        });
    }

    public static long getScanTime(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0L;
        }
        return data.copyTag().getLong("ScanTime");
    }

    public static void clearScanTime(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }
        var tag = data.copyTag();
        if (tag.contains("ScanTime")) {
            tag.remove("ScanTime");
            stack.set(DataComponents.CUSTOM_DATA, tag.isEmpty() ? null : CustomData.of(tag));
        }
    }

    private static boolean isScanning(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains("ScanStart");
    }

    private static void beginScan(ItemStack stack, UUID targetId, long gameTime) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> {
            var tag = data.copyTag();
            tag.putLong("ScanStart", gameTime);
            tag.putUUID("ScanTarget", targetId);
            return CustomData.of(tag);
        });
    }

    private static long getScanStart(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0L;
        }
        return data.copyTag().getLong("ScanStart");
    }

    private static UUID getScanTarget(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        var tag = data.copyTag();
        return tag.hasUUID("ScanTarget") ? tag.getUUID("ScanTarget") : null;
    }

    private static void clearScanState(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }
        var tag = data.copyTag();
        if (tag.contains("ScanStart")) {
            tag.remove("ScanStart");
        }
        if (tag.contains("ScanTarget")) {
            tag.remove("ScanTarget");
        }
        stack.set(DataComponents.CUSTOM_DATA, tag.isEmpty() ? null : CustomData.of(tag));
    }
}
