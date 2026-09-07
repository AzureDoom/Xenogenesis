package mod.azure.ovomorphosis.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.infection.EggmorphTracker;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.infection.InfectionState;

public final class OvomorphosisSavedData extends SavedData {

    private UUID hiveId = UUID.randomUUID();

    private final Map<ResourceKey<Level>, List<HiveMemory>> hives = new HashMap<>();

    private static final double HIVE_JOIN_RADIUS = 256.0D;

    private static final double HIVE_JOIN_RADIUS_SQR = HIVE_JOIN_RADIUS * HIVE_JOIN_RADIUS;

    public static OvomorphosisSavedData get(ServerLevel level) {
        var overworld = level.getServer().overworld();
        return overworld.getDataStorage()
            .computeIfAbsent(
                new SavedData.Factory<>(
                    OvomorphosisSavedData::createEmpty,
                    (tag, provider) -> load(tag, overworld),
                    DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES
                ),
                "ovomorphosis_data"
            );
    }

    public static HiveMemory getOrCreateHive(ServerLevel level, BlockPos origin) {
        var data = get(level);
        var dimension = level.dimension();

        var dimensionHives = data.hives.computeIfAbsent(
            dimension,
            key -> new ArrayList<>()
        );

        var nearest = getNearest(origin, dimensionHives);

        if (nearest != null)
            return nearest;

        var created = new HiveMemory();
        var center = HiveMemory.resolveOpenCenter(level, origin);
        HiveMemory.ensureCenterClear(level, center);
        created.claimDomeCenter(center);

        dimensionHives.add(created);
        data.setDirty();

        return created;
    }

    /**
     * Finds the nearest existing hive to {@code origin} within the usual join radius, without creating one if none is
     * found — unlike {@link #getOrCreateHive}, which is meant for the AI's own hive-expansion logic and would
     * spuriously spawn a brand-new (dome-less) hive entry if used for something like a block-placement hook.
     */
    public static Optional<HiveMemory> findNearestHive(ServerLevel level, BlockPos origin) {
        var data = get(level);
        var dimensionHives = data.hives.get(level.dimension());
        if (dimensionHives == null)
            return Optional.empty();

        return Optional.ofNullable(getNearest(origin, dimensionHives));
    }

    private static @Nullable HiveMemory getNearest(BlockPos origin, List<HiveMemory> dimensionHives) {
        HiveMemory nearest = null;
        var nearestDistanceSq = Double.MAX_VALUE;
        for (var hive : dimensionHives) {
            var center = hive.getDomeCenter().orElse(null);
            if (center == null)
                continue;

            var distanceSq = center.distSqr(origin);

            if (
                distanceSq <= HIVE_JOIN_RADIUS_SQR
                    && distanceSq < nearestDistanceSq
            ) {
                nearest = hive;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    private static OvomorphosisSavedData createEmpty() {
        return new OvomorphosisSavedData();
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.@NotNull Provider provider) {
        tag.putUUID("hiveId", hiveId);
        tag.put("eggmorph", saveEggmorph());
        tag.put("infections", saveInfections());
        tag.put("hives", saveHives());
        return tag;
    }

    private CompoundTag saveHives() {
        var root = new CompoundTag();
        var list = new ListTag();

        for (var dimensionEntry : hives.entrySet()) {
            var dimension = dimensionEntry.getKey();

            for (var hive : dimensionEntry.getValue()) {
                var hiveTag = new CompoundTag();

                hiveTag.putString(
                    "dimension",
                    dimension.location().toString()
                );

                hiveTag.put("data", hive.save());

                list.add(hiveTag);
            }
        }

        root.put("entries", list);
        return root;
    }

    public static void markHiveDirty(ServerLevel level) {
        get(level).setDirty();
    }

    /**
     * Drops {@code hive} from {@code level}'s dimension once every block it ever had has been destroyed (see
     * {@link HiveMemory#isFullyDestroyed()}), so it no longer shows up for {@link #findNearestHive} or
     * {@link #getOrCreateHive} — meaning a newly spawned or freshly hatched xenomorph can no longer bind to a hive that
     * no longer physically exists in the world (see {@code XenomorphEntity#ensureHiveAssignment}), and one that later
     * rebuilds there simply creates a fresh hive entry instead.
     * <p>
     * A no-op (returning {@code false}) if the hive still has structure left, or is somehow no longer present in the
     * dimension's list.
     *
     * @return {@code true} if the hive was actually removed
     */
    public static boolean removeHiveIfDestroyed(ServerLevel level, HiveMemory hive) {
        if (!hive.isFullyDestroyed())
            return false;

        var data = get(level);
        var dimensionHives = data.hives.get(level.dimension());

        if (dimensionHives != null && dimensionHives.remove(hive)) {
            data.setDirty();
            return true;
        }

        return false;
    }

    private static OvomorphosisSavedData load(CompoundTag tag, ServerLevel level) {
        var data = new OvomorphosisSavedData();
        EggmorphTracker.clearAll();
        InfectionManager.clearAll();
        if (tag.contains("eggmorph", Tag.TAG_LIST))
            loadEggmorph(tag.getList("eggmorph", Tag.TAG_COMPOUND), level);
        if (tag.contains("infections", Tag.TAG_LIST))
            loadInfections(tag.getList("infections", Tag.TAG_COMPOUND));
        if (tag.contains("hives", Tag.TAG_COMPOUND)) {
            data.loadHives(tag.getCompound("hives"));
        } else if (tag.contains("hiveMemory", Tag.TAG_COMPOUND)) {
            var legacyHive = HiveMemory.load(tag.getCompound("hiveMemory"));

            data.hives
                .computeIfAbsent(
                    Level.OVERWORLD,
                    key -> new ArrayList<>()
                )
                .add(legacyHive);
            data.setDirty();
        }
        return data;
    }

    private void loadHives(CompoundTag root) {
        hives.clear();

        var list = root.getList("entries", Tag.TAG_COMPOUND);

        for (var i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);

            if (
                !entry.contains("dimension", Tag.TAG_STRING)
                    || !entry.contains("data", Tag.TAG_COMPOUND)
            ) {
                continue;
            }

            var dimensionLocation =
                ResourceLocation.tryParse(entry.getString("dimension"));

            if (dimensionLocation == null)
                continue;

            var dimension = ResourceKey.create(
                Registries.DIMENSION,
                dimensionLocation
            );

            var hive = HiveMemory.load(
                entry.getCompound("data")
            );

            hives.computeIfAbsent(
                dimension,
                key -> new ArrayList<>()
            ).add(hive);
        }
    }

    private static ListTag saveEggmorph() {
        var list = new ListTag();
        for (var entry : EggmorphTracker.snapshotForSave().entrySet()) {
            var compound = new CompoundTag();
            compound.put("pos", NbtUtils.writeBlockPos(entry.getKey()));

            var entriesTag = new ListTag();
            for (var e : entry.getValue().entrySet()) {
                var entryTag = new CompoundTag();
                entryTag.putInt("entityId", e.getKey());
                entryTag.putString("phase", e.getValue().phase().toLowerCase(Locale.ROOT));
                entryTag.putInt("ticks", e.getValue().ticks());
                entriesTag.add(entryTag);
            }
            compound.put("entries", entriesTag);
            list.add(compound);
        }
        return list;
    }

    private static void loadEggmorph(ListTag list, ServerLevel level) {
        for (var i = 0; i < list.size(); i++) {
            var compound = list.getCompound(i);
            var pos = NbtUtils.readBlockPos(compound, "pos").orElse(null);
            if (pos == null)
                continue;

            var entriesTag = compound.getList("entries", Tag.TAG_COMPOUND);
            for (var j = 0; j < entriesTag.size(); j++) {
                var entryTag = entriesTag.getCompound(j);
                var entityId = entryTag.getInt("entityId");
                var phase = entryTag.getString("phase");
                var ticks = entryTag.getInt("ticks");

                var entity = level.getEntity(entityId);
                if (entity instanceof LivingEntity living) {
                    EggmorphTracker.restoreEntry(pos, living, phase, ticks);
                }
            }
        }
    }

    private static ListTag saveInfections() {
        var list = new ListTag();
        for (var entry : InfectionManager.snapshotForSave().entrySet()) {
            var compound = new CompoundTag();
            compound.putUUID("uuid", entry.getKey());
            compound.putInt("duration", entry.getValue().duration);
            compound.putInt("ticks", entry.getValue().ticks);
            compound.putInt("ticksSinceLastDamage", entry.getValue().ticksSinceLastDamage);
            compound.putBoolean("hasBurst", entry.getValue().hasBurst);
            if (entry.getValue().lastKnownPos != null)
                compound.put("lastKnownPos", NbtUtils.writeBlockPos(entry.getValue().lastKnownPos));
            list.add(compound);
        }
        return list;
    }

    private static void loadInfections(ListTag list) {
        for (var i = 0; i < list.size(); i++) {
            var compound = list.getCompound(i);
            var uuid = compound.getUUID("uuid");
            var state = new InfectionState(compound.getInt("duration"));
            state.ticks = compound.getInt("ticks");
            state.ticksSinceLastDamage = compound.getInt("ticksSinceLastDamage");
            state.hasBurst = compound.getBoolean("hasBurst");
            if (compound.contains("lastKnownPos"))
                state.lastKnownPos = NbtUtils.readBlockPos(compound, "lastKnownPos")
                    .orElse(BlockPos.ZERO);
            InfectionManager.restore(uuid, state);
        }
    }

    public static @Nullable HiveMemory findHiveById(
        ServerLevel level,
        UUID hiveId
    ) {
        var data = get(level);

        var dimensionHives =
            data.hives.get(level.dimension());

        if (dimensionHives == null)
            return null;

        for (var hive : dimensionHives) {
            if (hive.getHiveId().equals(hiveId))
                return hive;
        }

        return null;
    }
}
