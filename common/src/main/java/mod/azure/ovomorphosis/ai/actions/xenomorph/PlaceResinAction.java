package mod.azure.ovomorphosis.ai.actions.xenomorph;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.blocks.ResinBlock;
import mod.azure.ovomorphosis.data.OvomorphosisSavedData;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.util.ModTags;

public final class PlaceResinAction<E extends Mob, G> implements Action<E, G> {

    private static final int MAX_LIGHT_LEVEL = 4;

    /**
     * How far around the mob's own position to scan for dome-shell or tunnel-adjacent candidates each activation. Wider
     * than the dome's own {@link #DOME_RADIUS} band so a mob doesn't have to be standing almost exactly on the shell to
     * find something to build — too small a margin here was the main cause of spurious
     * {@link PlanFailureReason#FAILED_NO_VALID_PLACEMENT} results (and the resulting goal-suppression cooldown) once
     * mobs wandered even a little off the shell, which made hive expansion feel rare despite the goal itself scoring
     * highly while idle.
     */
    private static final int LOCAL_SCAN_RADIUS = 16;

    /** Outer radius of the dome hemisphere, measured from {@link HiveMemory#getDomeCenter()}. */
    private static final double DOME_RADIUS = 10.0D;

    /** The shell is the band between {@code DOME_RADIUS} and {@code DOME_RADIUS - DOME_SHELL_THICKNESS}. */
    private static final double DOME_SHELL_THICKNESS = 2.0D;

    /**
     * Running placed-block count at which the dome is considered complete. A cheap proxy for "the shell is filled in"
     * rather than an exhaustive scan of the whole hemisphere every activation — most of a shell this size is already
     * solid terrain requiring no placement, so this comfortably covers the blocks that actually needed replacing.
     */
    private static final int DOME_COMPLETE_BLOCK_TARGET = 260;

    private static final int MAX_ACTIVE_TUNNELS = 4;

    private static final int TUNNEL_MIN_LENGTH = 24;

    private static final int TUNNEL_MAX_LENGTH = 56;

    /** How close the mob must be to a tunnel's tip to extend that specific tunnel this activation. */
    private static final double TUNNEL_CAPTURE_RADIUS = 5.0D;

    /** How many tunnel steps a single activation may carve, mirroring the dome phase's per-tick block cap. */
    private static final int MAX_TUNNEL_STEPS_PER_TICK = 6;

    /**
     * Chance that a freshly spawned tunnel aims back toward a distant, already-placed piece of hive structure (a known
     * {@code RESIN_WEB_CROSS}) instead of straight outward from the dome. Without this, every tunnel radiates outward
     * independently and the tunnel network stays a simple star with no cross-links; biasing a fraction of new tunnels
     * toward existing structure instead lets branches curve back and intersect, growing one larger connected nest
     * rather than several disjoint spokes.
     */
    private static final float RECONNECT_TUNNEL_CHANCE = 0.35F;

    /**
     * Minimum known {@code RESIN_WEB_CROSS} count before reconnect-targeting kicks in — a young hive barely has any
     * structure yet, so there's nothing meaningfully distinct to connect back toward.
     */
    private static final int RECONNECT_MIN_KNOWN_CROSSES = 6;

    /**
     * Minimum distance a candidate reconnect target must be from the spawning mob for the resulting tunnel to be a
     * meaningful connector rather than a trivial one-block "tunnel" back to wherever the mob is already standing.
     */
    private static final double RECONNECT_MIN_DISTANCE = 20.0D;

    private static final double RECONNECT_MIN_DISTANCE_SQ = RECONNECT_MIN_DISTANCE * RECONNECT_MIN_DISTANCE;

    /**
     * Search radius, from a tunnel's current tip, within which an already-placed {@code RESIN_WEB_CROSS} will
     * continuously pull that tunnel's heading toward it as it's carved (see {@link #extendTunnel}). This is what
     * actually lets a reconnect-targeted tunnel (or any tunnel that happens to wander near existing structure) curve in
     * and connect, rather than just starting out aimed at a target and then jittering away from it over its full
     * length.
     */
    private static final double RECONNECT_BIAS_RADIUS = 40.0D;

    /** How strongly each step's direction is pulled toward a nearby reconnect target, blended with normal jitter. */
    private static final double RECONNECT_BIAS_WEIGHT = 0.3D;

    /** Reconnect bias is skipped once a tunnel is this close to its target, letting normal carving finish the job. */
    private static final double RECONNECT_ARRIVAL_DISTANCE = 4.0D;

    /**
     * Number of times, within a single tick, to reshuffle and retry the candidate list when the random per-candidate
     * placement roll happens to reject every entry. Without this, a location with plenty of valid candidates could
     * still report {@link PlanFailureReason#FAILED_NO_VALID_PLACEMENT} purely from bad RNG, incorrectly suppressing
     * {@link AiGoalType#EXPAND_HIVE} at a perfectly good spot.
     */
    private static final int PLACEMENT_ROLL_RETRIES = 3;

    /**
     * Chance a dome-shell candidate becomes a vent block instead of ordinary nest resin. Vent blocks are the "nest
     * building" side of the vent network (see {@code HiveMemory}'s vent tracking) — since the dome is the hive's
     * primary structure, this is the more common of the two vent-placement sites (compare
     * {@link #VENT_BLOCK_CHANCE_TUNNEL}, which is rarer).
     */
    private static final float VENT_BLOCK_CHANCE_NEST = 0.03F;

    /**
     * Chance a tunnel-floor candidate becomes a vent block instead of ordinary floor resin. Deliberately rarer than
     * {@link #VENT_BLOCK_CHANCE_NEST} — tunnels are the hive's "resin spread" reaching outward, and should pick up vent
     * connections more sparingly than the dome itself does.
     */
    private static final float VENT_BLOCK_CHANCE_TUNNEL = 0.01F;

    /**
     * Number of permanent doorway columns cut radially through the dome shell, one per evenly spaced compass heading.
     * These positions are excluded from {@link #findDomeShellCandidates} so ordinary shell-filling never seals them,
     * and are actively re-cleared by {@link #findBlockedDoor} if anything ends up occupying them.
     */
    private static final int DOOR_COUNT = 4;

    /** How many blocks tall each doorway column is, from dome floor level upward — enough headroom to walk through. */
    private static final int DOOR_HEIGHT = 3;

    private final int priority;

    private final int placementCooldownTicks;

    private int settleTicks = 0;

    private boolean placed = false;

    public PlaceResinAction(int priority, int placementCooldownTicks) {
        this.priority = priority;
        this.placementCooldownTicks = placementCooldownTicks;
    }

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 1);
        settleTicks = 0;
        placed = false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionOutcome.failed();

        var mobPos = mob.blockPosition();
        if (mob.level().getMaxLocalRawBrightness(mobPos) > MAX_LIGHT_LEVEL) {
            return (ActionOutcome<G>) ActionOutcome.failed(
                PlanFailureReason.FAILED_UNSUITABLE_CONDITIONS,
                mobPos,
                AiGoalType.EXPAND_HIVE
            );
        }

        if (!placed) {
            var hiveMemory = getOrCreateHiveMemory(mob, blackboard);
            var domeCenter = resolveDomeCenter(mob, hiveMemory);

            var blockedDoor = findBlockedDoor(mob, domeCenter);

            if (blockedDoor != null) {
                mob.level().setBlockAndUpdate(blockedDoor, Blocks.CAVE_AIR.defaultBlockState());
                markHiveDirty(mob);
                cooldowns.set(AiKeys.RESIN_PLACE_COOLDOWN, placementCooldownTicks);
                placed = true;
            } else {
                var needsRepair = hiveMemory.isDomeComplete()
                    && hiveMemory.findNearestPendingBreach(mob.level(), mobPos, LOCAL_SCAN_RADIUS).isPresent();

                var count = (hiveMemory.isDomeComplete() && !needsRepair)
                    ? tickTunnelPhase(mob, hiveMemory, domeCenter)
                    : tickDomePhase(mob, hiveMemory, domeCenter);

                if (count <= 0) {
                    return (ActionOutcome<G>) ActionOutcome.failed(
                        PlanFailureReason.FAILED_NO_VALID_PLACEMENT,
                        mobPos,
                        AiGoalType.EXPAND_HIVE
                    );
                }

                cooldowns.set(AiKeys.RESIN_PLACE_COOLDOWN, placementCooldownTicks);
                placed = true;
            }
        }

        if (settleTicks++ >= 20)
            return ActionOutcome.success();

        return ActionOutcome.running();
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {}

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * Returns the shared dome center, claiming a suitable position near the mob's current spot as that center if none
     * exists yet. The claimed position is resolved via {@link HiveMemory#resolveOpenCenter} (so it isn't sealed inside
     * solid/unbreakable terrain) and force-cleared via {@link HiveMemory#ensureCenterClear} before being claimed, so
     * every subsequent user of {@link HiveMemory#getDomeCenter()} can rely on that exact position being reachable.
     */
    private BlockPos resolveDomeCenter(E mob, HiveMemory hiveMemory) {
        return hiveMemory.getDomeCenter().orElseGet(() -> {
            var claimed = HiveMemory.resolveOpenCenter(mob.level(), mob.blockPosition());
            HiveMemory.ensureCenterClear(mob.level(), claimed);
            hiveMemory.claimDomeCenter(claimed);
            markHiveDirty(mob);
            return claimed;
        });
    }

    /**
     * Scans near the mob for dome-shell candidates and places a batch of them, returning how many blocks were placed (0
     * if the mob isn't currently near any unfilled part of the shell).
     */
    private int tickDomePhase(E mob, HiveMemory hiveMemory, BlockPos domeCenter) {
        var candidates = findDomeShellCandidates(mob, domeCenter);
        if (candidates.isEmpty())
            return 0;

        var count = placeBatch(mob, hiveMemory, candidates);
        if (count > 0) {
            hiveMemory.recordDomeBlocksPlaced(count, DOME_COMPLETE_BLOCK_TARGET);
            markHiveDirty(mob);
        }
        return count;
    }

    /**
     * Finds candidates near the mob's own position that fall within the dome's shell band (the hollow hemisphere
     * surface, not its solid interior) and are valid to replace with resin.
     */
    private List<BlockPos> findDomeShellCandidates(E mob, BlockPos domeCenter) {
        var level = mob.level();
        var origin = mob.blockPosition();
        var doorPositions = getDoorColumnPositions(domeCenter);

        List<BlockPos> candidates = new ArrayList<>();

        for (var x = -LOCAL_SCAN_RADIUS; x <= LOCAL_SCAN_RADIUS; x++) {
            for (var y = -LOCAL_SCAN_RADIUS; y <= LOCAL_SCAN_RADIUS; y++) {
                for (var z = -LOCAL_SCAN_RADIUS; z <= LOCAL_SCAN_RADIUS; z++) {
                    var pos = origin.offset(x, y, z);

                    if (pos.getY() < domeCenter.getY())
                        continue;

                    var distFromCenter = Math.sqrt(pos.distSqr(domeCenter));
                    if (distFromCenter > DOME_RADIUS || distFromCenter < DOME_RADIUS - DOME_SHELL_THICKNESS)
                        continue;

                    if (doorPositions.contains(pos.immutable()))
                        continue;

                    if (isValidReplacementTarget(level, pos))
                        candidates.add(pos.immutable());
                }
            }
        }

        return candidates;
    }

    /**
     * Every block position belonging to one of the dome's permanent doorway columns: a full-thickness radial slice
     * through the shell (from {@code DOME_RADIUS - DOME_SHELL_THICKNESS} out to {@code DOME_RADIUS}) at
     * {@link #DOOR_COUNT} evenly spaced compass headings, {@link #DOOR_HEIGHT} blocks tall starting at the dome's floor
     * level. Deterministic from {@code domeCenter} alone — nothing needs to be persisted.
     */
    private Set<BlockPos> getDoorColumnPositions(BlockPos domeCenter) {
        Set<BlockPos> positions = new HashSet<>();
        var shellMinRadius = (int) Math.floor(DOME_RADIUS - DOME_SHELL_THICKNESS);
        var shellMaxRadius = (int) Math.ceil(DOME_RADIUS);

        for (var i = 0; i < DOOR_COUNT; i++) {
            var angle = (2.0D * Math.PI * i) / DOOR_COUNT;
            var dirX = Math.cos(angle);
            var dirZ = Math.sin(angle);

            for (var r = shellMinRadius; r <= shellMaxRadius; r++) {
                var baseX = domeCenter.getX() + (int) Math.round(dirX * r);
                var baseZ = domeCenter.getZ() + (int) Math.round(dirZ * r);

                for (var y = 0; y < DOOR_HEIGHT; y++) {
                    positions.add(new BlockPos(baseX, domeCenter.getY() + y, baseZ));
                }
            }
        }

        return positions;
    }

    /**
     * Returns the nearest doorway position, if any, that's currently occupied by a solid {@link ModTags#WEAK_BLOCKS}
     * -tagged block within {@link #LOCAL_SCAN_RADIUS} of the mob. Restricted to weak-tagged blocks on purpose — a
     * doorway that's silted over with dirt, gravel, or caved-in stone should get dug back out, but anything sturdier
     * (or player-placed and not weak-tagged) is left alone rather than casually demolished.
     */
    private BlockPos findBlockedDoor(E mob, BlockPos domeCenter) {
        var level = mob.level();
        var origin = mob.blockPosition();

        BlockPos best = null;
        var bestDistSq = Double.MAX_VALUE;

        for (var pos : getDoorColumnPositions(domeCenter)) {
            var distSq = origin.distSqr(pos);
            if (distSq > LOCAL_SCAN_RADIUS * (double) LOCAL_SCAN_RADIUS)
                continue;

            var state = level.getBlockState(pos);
            if (state.isAir() || !state.is(ModTags.WEAK_BLOCKS))
                continue;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = pos;
            }
        }

        return best;
    }

    /**
     * Either extends an existing tunnel the mob is currently near, or spawns a brand new one from the dome's outer
     * surface (if under {@link #MAX_ACTIVE_TUNNELS}), returning how many blocks were carved this activation.
     */
    private int tickTunnelPhase(E mob, HiveMemory hiveMemory, BlockPos domeCenter) {
        var tunnels = hiveMemory.getActiveTunnels();

        var nearby = findNearestTunnel(mob, tunnels);
        if (nearby != null) {
            return extendTunnel(mob, hiveMemory, nearby);
        }

        if (tunnels.size() < MAX_ACTIVE_TUNNELS) {
            var spawned = spawnTunnel(mob, hiveMemory, domeCenter);
            if (spawned != null) {
                tunnels.add(spawned);

                var count = extendTunnel(mob, hiveMemory, spawned);

                if (count <= 0 && tunnels.contains(spawned))
                    markHiveDirty(mob);

                return count;
            }
        }

        return 0;
    }

    private HiveMemory.TunnelState findNearestTunnel(E mob, List<HiveMemory.TunnelState> tunnels) {
        var origin = mob.blockPosition();
        var captureSq = TUNNEL_CAPTURE_RADIUS * TUNNEL_CAPTURE_RADIUS;

        HiveMemory.TunnelState best = null;
        var bestDistSq = Double.MAX_VALUE;

        for (var tunnel : tunnels) {
            var distSq = origin.distSqr(tunnel.tip());
            if (distSq > captureSq)
                continue;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = tunnel;
            }
        }

        return best;
    }

    /**
     * Picks a random already-known {@code RESIN_WEB_CROSS} position at least {@link #RECONNECT_MIN_DISTANCE} away from
     * the mob to serve as a new tunnel's reconnect target, or {@code null} if the hive doesn't have enough established
     * structure yet ({@link #RECONNECT_MIN_KNOWN_CROSSES}) or nothing qualifies. Picking randomly among qualifying
     * candidates (rather than always the single nearest) spreads reconnect attempts across different parts of the hive
     * instead of every tunnel converging on the same spot.
     */
    private BlockPos findReconnectTarget(E mob, HiveMemory hiveMemory) {
        var known = hiveMemory.getOwnedWebCrosses();
        if (known.size() < RECONNECT_MIN_KNOWN_CROSSES)
            return null;

        var mobPos = mob.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (var pos : known) {
            if (mobPos.distSqr(pos) >= RECONNECT_MIN_DISTANCE_SQ)
                candidates.add(pos);
        }

        if (candidates.isEmpty())
            return null;

        return candidates.get(mob.getRandom().nextInt(candidates.size()));
    }

    /**
     * Picks a random point on the dome's outer surface and starts a new tunnel heading outward from it (or, with
     * {@link #RECONNECT_TUNNEL_CHANCE} probability, leaning toward a distant piece of existing hive structure instead —
     * see {@link #findReconnectTarget}). Returns {@code null} if the mob isn't near enough to the dome to plausibly
     * start one there (avoids spawning a tunnel whose starting tip is nowhere near any mob able to carve it).
     */
    private HiveMemory.TunnelState spawnTunnel(E mob, HiveMemory hiveMemory, BlockPos domeCenter) {
        var random = mob.getRandom();
        var mobPos = mob.blockPosition();

        var reconnectTarget = random.nextFloat() < RECONNECT_TUNNEL_CHANCE
            ? findReconnectTarget(mob, hiveMemory)
            : null;

        double baseX = mobPos.getX() - domeCenter.getX();
        double baseY = mobPos.getY() - domeCenter.getY();
        double baseZ = mobPos.getZ() - domeCenter.getZ();
        var baseLen = Math.sqrt(baseX * baseX + baseY * baseY + baseZ * baseZ);
        if (baseLen < 1.0E-4D) {
            baseX = 1.0D;
            baseY = 0.2D;
            baseZ = 0.0D;
            baseLen = Math.sqrt(baseX * baseX + baseY * baseY + baseZ * baseZ);
        }
        baseX /= baseLen;
        baseZ /= baseLen;

        if (reconnectTarget != null) {
            var toTargetX = reconnectTarget.getX() - mobPos.getX();
            var toTargetZ = reconnectTarget.getZ() - mobPos.getZ();
            var toTargetLen = Math.sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ);
            if (toTargetLen > 1.0E-4D) {
                var blendedX = baseX * (1.0D - RECONNECT_BIAS_WEIGHT) + (toTargetX / toTargetLen)
                    * RECONNECT_BIAS_WEIGHT;
                var blendedZ = baseZ * (1.0D - RECONNECT_BIAS_WEIGHT) + (toTargetZ / toTargetLen)
                    * RECONNECT_BIAS_WEIGHT;
                var blendedLen = Math.sqrt(blendedX * blendedX + blendedZ * blendedZ);
                if (blendedLen > 1.0E-4D) {
                    baseX = blendedX / blendedLen;
                    baseZ = blendedZ / blendedLen;
                }
            }
        }

        var yawJitter = Math.toRadians((random.nextDouble() * 2.0D - 1.0D) * 35.0D);
        var cos = Math.cos(yawJitter);
        var sin = Math.sin(yawJitter);

        var dirX = baseX * cos - baseZ * sin;
        var dirZ = baseX * sin + baseZ * cos;
        var dirY = Mth.clamp(baseY + (random.nextDouble() * 2.0D - 1.0D) * 0.15D, -0.6D, 0.3D);

        var length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 1.0E-4D)
            return null;
        dirX /= length;
        dirY /= length;
        dirZ /= length;

        var startPos = BlockPos.containing(
            domeCenter.getX() + dirX * DOME_RADIUS,
            domeCenter.getY() + dirY * DOME_RADIUS,
            domeCenter.getZ() + dirZ * DOME_RADIUS
        );

        if (mobPos.distSqr(startPos) > TUNNEL_CAPTURE_RADIUS * TUNNEL_CAPTURE_RADIUS * 4)
            return null;

        var stepsTotal = TUNNEL_MIN_LENGTH + random.nextInt(TUNNEL_MAX_LENGTH - TUNNEL_MIN_LENGTH + 1);
        return new HiveMemory.TunnelState(startPos, dirX, dirY, dirZ, stepsTotal);
    }

    /**
     * Carves {@code tunnel} forward by up to {@link #MAX_TUNNEL_STEPS_PER_TICK} steps, occasionally jittering its
     * direction so the path winds organically instead of running dead straight. Terminates (and removes the tunnel from
     * {@code hiveMemory}) early if it runs out of step budget or hits something that can't be carved through.
     *
     * @return how many blocks were actually placed/cleared this activation
     */
    private int extendTunnel(E mob, HiveMemory hiveMemory, HiveMemory.TunnelState tunnel) {
        var level = mob.level();
        var random = mob.getRandom();
        var placedCount = 0;
        var blocked = false;

        for (var step = 0; step < MAX_TUNNEL_STEPS_PER_TICK; step++) {
            if (tunnel.isExhausted())
                break;

            var tip = tunnel.tip();
            var floor = tip.below();
            var head = tip.above();

            var tipState = level.getBlockState(tip);
            var headState = level.getBlockState(head);

            if (!isValidReplacementTarget(level, tip, true) && !tipState.isAir()) {
                blocked = true;
                break;
            }

            if (!tipState.isAir())
                level.setBlockAndUpdate(tip, Blocks.CAVE_AIR.defaultBlockState());

            if (!headState.isAir() && isValidReplacementTarget(level, head, true))
                level.setBlockAndUpdate(head, Blocks.CAVE_AIR.defaultBlockState());

            var floorState = level.getBlockState(floor);
            if (!floorState.isFaceSturdy(level, floor, Direction.UP)) {
                if (random.nextFloat() < VENT_BLOCK_CHANCE_TUNNEL) {
                    level.setBlockAndUpdate(floor, BlockRegistry.RESIN_VENT.get().defaultBlockState());
                    hiveMemory.registerVentBlock(floor);
                } else {
                    var placeCross = random.nextFloat() < 0.10F;
                    var floorBlockState = placeCross
                        ? BlockRegistry.RESIN_WEB_CROSS.get().defaultBlockState()
                        : BlockRegistry.RESIN.get()
                            .defaultBlockState()
                            .setValue(ResinBlock.LAYERS, 8);

                    level.setBlockAndUpdate(floor, floorBlockState);

                    if (placeCross)
                        hiveMemory.trackOwnedWebCross(floor);
                }
            }

            placedCount++;

            var jitter = 0.35D;
            var newDirX = tunnel.dirX() + (random.nextDouble() * 2.0D - 1.0D) * jitter;
            var newDirY = Mth.clamp(
                tunnel.dirY() + (random.nextDouble() * 2.0D - 1.0D) * (jitter * 0.4D),
                -0.6D,
                0.3D
            );
            var newDirZ = tunnel.dirZ() + (random.nextDouble() * 2.0D - 1.0D) * jitter;

            var reconnectTarget = hiveMemory.findNearestOwnedWebCross(level, tip, RECONNECT_BIAS_RADIUS);
            if (reconnectTarget.isPresent()) {
                var target = reconnectTarget.get();
                var toTargetX = target.getX() - tip.getX();
                var toTargetY = target.getY() - tip.getY();
                var toTargetZ = target.getZ() - tip.getZ();
                var toTargetLen = Math.sqrt(toTargetX * toTargetX + toTargetY * toTargetY + toTargetZ * toTargetZ);

                if (toTargetLen > RECONNECT_ARRIVAL_DISTANCE) {
                    newDirX = newDirX * (1.0D - RECONNECT_BIAS_WEIGHT)
                        + (toTargetX / toTargetLen) * RECONNECT_BIAS_WEIGHT;
                    newDirY = Mth.clamp(
                        newDirY * (1.0D - RECONNECT_BIAS_WEIGHT) + (toTargetY / toTargetLen) * RECONNECT_BIAS_WEIGHT,
                        -0.6D,
                        0.3D
                    );
                    newDirZ = newDirZ * (1.0D - RECONNECT_BIAS_WEIGHT)
                        + (toTargetZ / toTargetLen) * RECONNECT_BIAS_WEIGHT;
                }
            }

            var len = Math.sqrt(
                newDirX * newDirX
                    + newDirY * newDirY
                    + newDirZ * newDirZ
            );

            if (len < 1.0E-4D) {
                tunnel.advance(tip, tunnel.dirX(), tunnel.dirY(), tunnel.dirZ());
                continue;
            }

            newDirX /= len;
            newDirY /= len;
            newDirZ /= len;

            var maxComponent = Math.max(Math.abs(newDirX), Math.max(Math.abs(newDirY), Math.abs(newDirZ)));
            var stepScale = maxComponent > 1.0E-4D ? 1.0D / maxComponent : 1.0D;

            var nextTip = BlockPos.containing(
                tip.getX() + newDirX * stepScale,
                tip.getY() + newDirY * stepScale,
                tip.getZ() + newDirZ * stepScale
            );

            if (nextTip.equals(tip)) {
                tunnel.advance(tip, newDirX, newDirY, newDirZ);
                continue;
            }

            tunnel.advance(nextTip, newDirX, newDirY, newDirZ);
        }

        var removed = false;

        if (blocked || tunnel.isExhausted()) {
            hiveMemory.getActiveTunnels().remove(tunnel);
            removed = true;
        }

        if (placedCount > 0 || removed) {
            markHiveDirty(mob);
        }

        return placedCount;
    }

    /**
     * A block is a valid dig/build target if it's naturally replaceable (air, grass, flowers, ...) or tagged
     * {@link ModTags#WEAK_BLOCKS} with sane hardness — mirroring the "soft enough to carve through" category used
     * elsewhere in the mod (e.g. {@code BreakToTargetAction}) — and isn't either brightly lit. Naturally-replaceable
     * candidates additionally require an adjacent solid face so the dome doesn't sprout floating resin in open air;
     * weak-tagged solid blocks are inherently backed by whatever's around them, so that check is skipped for them.
     */
    private boolean isValidReplacementTarget(Level level, BlockPos pos) {
        return isValidReplacementTarget(level, pos, false);
    }

    /**
     * @param allowCarveThroughResin when {@code true}, the hive's own {@link ModTags#RESIN}-tagged blocks (the dome
     *                               shell, tunnel floor resin, web crosses) also count as valid — needed so tunnels can
     *                               breach the shell and cut through previously placed resin. Left {@code false} for
     *                               dome-shell scanning ({@link #findDomeShellCandidates}) so that phase only ever
     *                               targets untouched terrain instead of endlessly re-rolling blocks it already placed.
     */
    private boolean isValidReplacementTarget(Level level, BlockPos pos, boolean allowCarveThroughResin) {
        if (level.getMaxLocalRawBrightness(pos) > MAX_LIGHT_LEVEL)
            return false;

        var state = level.getBlockState(pos);
        var isWeak = state.is(ModTags.WEAK_BLOCKS) || (allowCarveThroughResin && state.is(ModTags.RESIN));
        var isReplaceable = state.canBeReplaced();

        if (!isReplaceable && !isWeak)
            return false;

        if (isWeak) {
            var hardness = state.getDestroySpeed(level, pos);
            return !(hardness < 0f);
        }

        return hasAdjacentSolid(level, pos);
    }

    private int placeBatch(E mob, HiveMemory hiveMemory, List<BlockPos> candidates) {
        var level = mob.level();
        var count = 0;

        for (var attempt = 0; attempt < PLACEMENT_ROLL_RETRIES && count == 0; attempt++) {
            Collections.shuffle(candidates, new Random(mob.getRandom().nextLong()));

            for (var pos : candidates) {
                if (count >= 12)
                    break;
                if (mob.getRandom().nextFloat() > 0.35F)
                    continue;

                if (mob.getRandom().nextFloat() < VENT_BLOCK_CHANCE_NEST) {
                    level.setBlockAndUpdate(pos, BlockRegistry.RESIN_VENT.get().defaultBlockState());
                    hiveMemory.registerVentBlock(pos);
                    count++;
                    continue;
                }

                if (isFloorBacked(level, pos)) {
                    var placeResinCross = mob.getRandom().nextFloat() < 0.125F;
                    var newState = placeResinCross
                        ? BlockRegistry.RESIN_WEB_CROSS.get().defaultBlockState()
                        : BlockRegistry.RESIN.get()
                            .defaultBlockState()
                            .setValue(ResinBlock.LAYERS, 1 + mob.getRandom().nextInt(8));

                    level.setBlockAndUpdate(pos, newState);
                    if (placeResinCross)
                        hiveMemory.trackOwnedWebCross(pos);
                } else {
                    level.setBlockAndUpdate(pos, BlockRegistry.RESIN_BLOCK.get().defaultBlockState());
                }
                count++;
            }
        }

        return count;
    }

    /**
     * {@code true} if {@code pos} has a solid, face-sturdy block directly beneath it — the one support configuration
     * the layered "nest" {@link ResinBlock} can actually survive on. Anything else that still counts as a valid
     * placement target (backed from the side or above instead) is wall/ceiling-backed and must use the full-cube resin
     * block instead.
     */
    private boolean isFloorBacked(Level level, BlockPos pos) {
        var below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    private boolean hasAdjacentSolid(Level level, BlockPos target) {
        var below = target.below();
        var belowState = level.getBlockState(below);

        if (belowState.isFaceSturdy(level, below, Direction.UP))
            return true;

        var above = target.above();
        var aboveState = level.getBlockState(above);
        if (aboveState.isFaceSturdy(level, above, Direction.DOWN))
            return true;

        for (var dir : Direction.Plane.HORIZONTAL) {
            var adj = target.relative(dir);
            var adjState = level.getBlockState(adj);
            if (adjState.isFaceSturdy(level, adj, dir.getOpposite()))
                return true;
        }
        return false;
    }

    private HiveMemory getOrCreateHiveMemory(
        E mob,
        Blackboard blackboard
    ) {
        var existing = blackboard.get(AiKeys.HIVE_MEMORY);

        if (existing != null)
            return existing;

        if (mob.level() instanceof ServerLevel serverLevel) {
            var hive = OvomorphosisSavedData.getOrCreateHive(
                serverLevel,
                mob.blockPosition()
            );

            blackboard.set(AiKeys.HIVE_MEMORY, hive);
            return hive;
        }

        var fallback = new HiveMemory();
        blackboard.set(AiKeys.HIVE_MEMORY, fallback);
        return fallback;
    }

    private void markHiveDirty(E mob) {
        if (mob.level() instanceof ServerLevel serverLevel) {
            OvomorphosisSavedData.markHiveDirty(serverLevel);
        }
    }
}
