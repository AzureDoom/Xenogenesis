package mod.azure.ovomorphosis.ai.actions;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.config.CortexConfig;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.navigation.astar.AStarPathfinder;
import com.azure.azurecortex.navigation.astar.IncrementalPathSession;
import com.azure.azurecortex.navigation.astar.PathNodeCache;
import com.azure.azurecortex.navigation.astar.PhasedPathSession;
import com.azure.azurecortex.navigation.crawl.CrawlController;
import com.azure.azurecortex.navigation.crawl.CrawlTraversalEvaluator;
import com.azure.azurecortex.navigation.movement.MovementController;
import com.azure.azurecortex.navigation.movement.NavigationQueries;
import com.azure.azurecortex.navigation.traversal.TraversalQueries;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.level.TunnelEntryRegistry;
import mod.azure.ovomorphosis.util.ModTags;

public final class MoveToTargetAction<E extends Mob, G> implements Action<E, G> {

    /**
     * Hard cap on {@link #noProgressTicks} before this action gives up and bubbles
     * {@link PlanFailureReason#FAILED_STUCK} up to GOAP via {@link CommonBlackboardKeys#LAST_PLAN_FEEDBACK}, instead of
     * retrying local recovery (detours, block breaks, jumps) forever. Local recovery attempts do not reset this counter
     * — only actual distance-to-target improvement does — so a mob that keeps detouring/jumping/breaking without ever
     * closing the distance will still terminate and let the planner pick a different goal.
     */
    private static final int HARD_NO_PROGRESS_TICKS = 200;

    /**
     * Soft threshold on {@link #noProgressTicks}: once crossed (but before the hard cap), the action reports
     * {@link ActionOutcome.Blocked} every tick so GOAP gets an early, non-terminal signal that this strategy isn't
     * working, well before local recovery (detours, block breaks, jumps) has actually given up.
     */
    private static final int SOFT_NO_PROGRESS_TICKS = HARD_NO_PROGRESS_TICKS / 2;

    private static final int ABSOLUTE_MAX_CHASE_TICKS = 600;

    /**
     * Max distance (blocks, squared) at which {@link #findEncasingWallBreakTarget} bothers checking at all. Kept small
     * and villager-house-sized so this never fires on a target that's merely far away with unrelated terrain in
     * between.
     */
    private static final double ENCASED_CHECK_MAX_DIST_SQ = 16.0D * 16.0D;

    /**
     * Below this straight-line distance (blocks, squared), the detour-ratio check in
     * {@link #findEncasingWallBreakTarget} is skipped — short paths naturally wind a bit and shouldn't trip it.
     */
    private static final double MIN_STRAIGHT_DIST_FOR_DETOUR_SQR = 2.0D * 2.0D;

    /**
     * How many times longer than straight-line distance a path must be before {@link #findEncasingWallBreakTarget}
     * treats it as routing around a structure rather than merely winding.
     */
    private static final double DETOUR_RATIO_THRESHOLD = 1.8D;

    private int ticksSinceStart = 0;

    private int dangerLeapCooldown = 0;

    private final double stopDistanceSqr;

    private final double speed;

    private final int priority;

    private final boolean canCrawl;

    private final int[] steerBias = { 0 };

    private Vec3 lastPos = Vec3.ZERO;

    private int stuckTicks = 0;

    private Vec3 detourDirection = Vec3.ZERO;

    private int detourTicks = 0;

    private int blockBreakCooldown = 0;

    private int crowdPushCooldown = 0;

    private List<BlockPos> path = Collections.emptyList();

    private int pathIndex = 0;

    private int repathCooldown = 0;

    private double lastDistSqToTarget = Double.MAX_VALUE;

    private int noProgressTicks = 0;

    /**
     * The in-progress incremental fallback chain (primary crawl → tunnel-entry retry → relaxed crawl → plain ground A*,
     * all sharing one per-tick budget), or {@code null} when none is running. See {@link PhasedPathSession},
     * {@link IncrementalPathSession}, and {@code OvomorphosisConfig#enableIncrementalPathfinding}.
     */
    private PhasedPathSession phasedSession = null;

    /** The {@code pathStart}/{@code crawlGoal} {@link #phasedSession} was created for, used to detect staleness. */
    private BlockPos phasedSessionStart = null;

    private BlockPos phasedSessionGoal = null;

    /** Ticks {@link #phasedSession} has been running; a safety valve so a pathological search can't run forever. */
    private int phasedSessionAgeTicks = 0;

    /**
     * A {@link PathNodeCache} dedicated to {@link #phasedSession}'s lifetime, separate from {@link #nodeCache} (which
     * is cleared every tick for correctness of this action's own live per-tick terrain checks). This one is only
     * cleared when a new {@link #phasedSession} starts, so a search spanning many ticks keeps the benefit of its own
     * memoized terrain classifications between steps instead of re-querying the world on every single step. See
     * {@link PathNodeCache#invalidate} for how known block changes (see {@link #lastBreakToTargetTriggerActive}) are
     * reflected without discarding the whole thing.
     */
    private final PathNodeCache sessionCache = new PathNodeCache();

    /**
     * Whether {@code AiKeys#BREAK_TO_TARGET_TRIGGER} was set on the previous tick. Used to detect the falling edge — a
     * break-to-target cycle just finished, successfully or exhausted — so {@link #sessionCache} can be selectively
     * invalidated around the route instead of continuing to trust classifications computed before the break.
     */
    private boolean lastBreakToTargetTriggerActive = false;

    /** If the mob's feet moved further than this (blocks, squared) since {@link #phasedSession} started, restart it. */
    private static final double PATH_SESSION_START_DRIFT_SQ = 3.0D * 3.0D;

    /** If the goal moved further than this (blocks, squared) since {@link #phasedSession} started, restart it. */
    private static final double PATH_SESSION_GOAL_DRIFT_SQ = 4.0D * 4.0D;

    /** Hard cap on how many ticks a single incremental session may run before it is abandoned and restarted. */
    private static final int PATH_SESSION_MAX_AGE_TICKS = 40;

    /**
     * Set by {@link #applyPathMovement} / {@link #applyFlatFallback} when they have an outcome to report this tick (a
     * hard-cap {@link ActionOutcome.Failed}, or a soft-threshold {@link ActionOutcome.Blocked}) rather than a plain
     * {@link ActionOutcome#running()}.
     */
    private ActionOutcome<G> pendingOutcome = null;

    private final PathNodeCache nodeCache = new PathNodeCache();

    private BlockPos cachedTunnelEntry = null;

    private BlockPos tunnelScanOrigin = null;

    private int tunnelRescanCooldown = 0;

    private static final int TUNNEL_RESCAN_TICKS = 20;

    private static final int TUNNEL_SCAN_RADIUS = 32;

    private static final int TUNNEL_SCAN_MIN_DY = -3;

    private static final int TUNNEL_SCAN_MAX_DY = 1;

    public MoveToTargetAction(
        double stopDistance,
        double speed,
        int priority,
        boolean canCrawl
    ) {
        this.stopDistanceSqr = stopDistance * stopDistance;
        this.speed = speed;
        this.priority = priority;
        this.canCrawl = canCrawl;
    }

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 1);
        mob.setAggressive(true);
        lastPos = mob.position();
        stuckTicks = 0;
        detourDirection = Vec3.ZERO;
        detourTicks = 0;
        blockBreakCooldown = 0;
        crowdPushCooldown = 0;
        dangerLeapCooldown = 0;
        path = Collections.emptyList();
        pathIndex = 0;
        repathCooldown = 0;
        lastDistSqToTarget = Double.MAX_VALUE;
        noProgressTicks = 0;
        ticksSinceStart = 0;
        pendingOutcome = null;
        nodeCache.clear();
        cachedTunnelEntry = null;
        tunnelScanOrigin = null;
        tunnelRescanCooldown = 0;
        phasedSession = null;
        phasedSessionStart = null;
        phasedSessionGoal = null;
        phasedSessionAgeTicks = 0;
        sessionCache.clear();
        lastBreakToTargetTriggerActive = false;
    }

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        nodeCache.clear();
        ticksSinceStart++;

        if (mob.getHealth() <= 0) {
            mob.setAggressive(false);
            return ActionOutcome.failed();
        }

        var target = blackboard.get(CommonBlackboardKeys.TARGET);

        if (target == null || !target.isAlive()) {
            if (!canCrawl || !CrawlController.isWallCrawling(mob)) {
                mob.setDeltaMovement(mob.getDeltaMovement().scale(0.5D));
            }
            return ActionOutcome.failed(PlanFailureReason.FAILED_TARGET_LOST);
        }

        var yDiff = target.getY() - mob.getY();
        if (!canCrawl && yDiff > 12.0D) {
            mob.getNavigation().stop();
            return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION);
        }

        if (mob.distanceToSqr(target) <= stopDistanceSqr && TargetingUtils.hasMeleeLineOfSight(mob, target)) {
            var dangerMove = MovementController.steerAwayFromDangerEntities(mob, Vec3.ZERO);

            if (dangerMove.lengthSqr() > 0.0001D) {
                var safe = MovementController.findSafeMovement(mob, dangerMove, steerBias);

                if (!safe.equals(Vec3.ZERO)) {
                    mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
                    mob.hasImpulse = true;
                    faceTarget(mob, target);
                    return ActionOutcome.running();
                }
            }

            mob.setDeltaMovement(mob.getDeltaMovement().scale(0.4D));
            faceTarget(mob, target);
            return ActionOutcome.success();
        }

        if (repathCooldown > 0) {
            repathCooldown--;
        }

        var mobFeetPos = BlockPos.containing(mob.getX(), mob.getBoundingBox().minY, mob.getZ());
        var groundBlock = mobFeetPos.below();
        var mobInFluid = !mob.level().getBlockState(mobFeetPos).getFluidState().isEmpty();
        var mobOnSolidGround = !mobInFluid && !mob.level()
            .getBlockState(groundBlock)
            .getCollisionShape(mob.level(), groundBlock)
            .isEmpty();

        var pathStart = mobFeetPos;
        var belowFeet = mob.level().getBlockState(mobFeetPos.below());
        var feetOrBelowInFluid = mobInFluid || !belowFeet.getFluidState().isEmpty();

        if (feetOrBelowInFluid) {
            if (mobInFluid) {
                if (mob.level().getBlockState(pathStart).getFluidState().isEmpty()) {
                    for (int dy = 1; dy <= 3; dy++) {
                        var candidate = pathStart.above(dy);
                        if (!mob.level().getBlockState(candidate).getFluidState().isEmpty()) {
                            pathStart = candidate;
                            break;
                        }
                    }
                }
            } else {
                pathStart = mobFeetPos.below();
            }
        }

        var breakToTargetTriggerActive = blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER);
        if (lastBreakToTargetTriggerActive && !breakToTargetTriggerActive) {
            sessionCache.invalidate(pathStart);
            for (var li = pathIndex; li < Math.min(path.size(), pathIndex + 3); li++) {
                sessionCache.invalidate(path.get(li));
            }
        }
        lastBreakToTargetTriggerActive = breakToTargetTriggerActive;

        var mobIsInOrAtTunnel = canCrawl
            && (nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeetPos)
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeetPos.below())
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeetPos.above())
                || (!mobOnSolidGround && nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeetPos))
                || (!mobOnSolidGround && nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeetPos.below())));

        var nextWaypointNeedsCrawl = false;
        if (canCrawl && !path.isEmpty() && pathIndex < path.size()) {
            for (var li = pathIndex; li < Math.min(path.size(), pathIndex + 3); li++) {
                var la = path.get(li);
                var belowLa = la.below();
                var laHasGroundBelow = !mob.level()
                    .getBlockState(belowLa)
                    .getCollisionShape(mob.level(), belowLa)
                    .isEmpty();
                if (
                    nodeCache.tunnelCanStandAt(mob.level(), mob, la)
                        || (!laHasGroundBelow && nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, la))
                        || (nodeCache.isSafeClimbNode(mob.level(), la, mob)
                            && !nodeCache.canStandAt(mob.level(), mob, la))
                ) {
                    nextWaypointNeedsCrawl = true;
                    break;
                }
            }
        }
        if (mobOnSolidGround && !mobIsInOrAtTunnel && !nextWaypointNeedsCrawl) {
            CrawlController.setWallCrawling(mob, false);
        }

        var isCrawlingNow = canCrawl && CrawlController.isWallCrawling(mob);

        if (
            canCrawl
                && isCrawlingNow
                && !mobOnSolidGround
                && !mobIsInOrAtTunnel
                && !nextWaypointNeedsCrawl
                && !mob.horizontalCollision
                && !nodeCache.isSafeClimbNode(mob.level(), mobFeetPos, mob)
        ) {
            CrawlController.setWallCrawling(mob, false);
            isCrawlingNow = false;
        }
        var nearbyTunnelEntry = canCrawl ? findNearbyTunnelEntry(mob) : null;
        if (nearbyTunnelEntry != null && mobOnSolidGround && !mobIsInOrAtTunnel) {
            var toTunnel = Vec3.atBottomCenterOf(nearbyTunnelEntry).subtract(mob.position());
            var toTarget = target.position().subtract(mob.position());
            var t2d = toTunnel.multiply(1, 0, 1);
            var g2d = toTarget.multiply(1, 0, 1);
            if (
                t2d.lengthSqr() < 0.01D || g2d.lengthSqr() < 0.01D
                    || t2d.normalize().dot(g2d.normalize()) <= -0.3D
            ) {
                nearbyTunnelEntry = null;
            }
        }

        var shouldUseCrawlingNow = canCrawl && (isCrawlingNow
            || mobIsInOrAtTunnel
            || nextWaypointNeedsCrawl
            || (nearbyTunnelEntry != null)
            || CrawlController.shouldUseWallCrawlingToTarget(mob, target));

        var tunnelBiasedGoal = (shouldUseCrawlingNow && (!mobOnSolidGround || mobIsInOrAtTunnel
            || nearbyTunnelEntry != null))
                ? findBestTunnelBiasedGoal(mob, target)
                : null;

        var repathInterval = (isCrawlingNow ? 40 : 20) + (mob.getId() & 7);

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size()) {
            var crawlGoal = (tunnelBiasedGoal != null) ? tunnelBiasedGoal : target.blockPosition();
            if (
                mob.level().getBlockState(crawlGoal).isAir()
                    && !mob.level().getBlockState(crawlGoal.below()).getFluidState().isEmpty()
            ) {
                crawlGoal = crawlGoal.below();
            }
            var fluidGoalRadius = feetOrBelowInFluid ? 2 : 0;

            final var searchCrawlGoal = crawlGoal;

            List<BlockPos> newPath;

            if (CortexConfig.get().enableIncrementalPathfinding) {
                var stale = phasedSession != null
                    && (phasedSessionStart.distSqr(pathStart) > PATH_SESSION_START_DRIFT_SQ
                        || phasedSessionGoal.distSqr(searchCrawlGoal) > PATH_SESSION_GOAL_DRIFT_SQ
                        || phasedSessionAgeTicks > PATH_SESSION_MAX_AGE_TICKS);

                if (phasedSession == null || stale) {
                    sessionCache.clear();
                    phasedSession = new PhasedPathSession(
                        buildPhases(mob, pathStart, searchCrawlGoal, nearbyTunnelEntry, target, fluidGoalRadius)
                    );
                    phasedSessionStart = pathStart;
                    phasedSessionGoal = searchCrawlGoal;
                    phasedSessionAgeTicks = 0;
                }

                phasedSessionAgeTicks++;
                var status = phasedSession.step(CortexConfig.get().incrementalPathfindingNodeBudget);

                newPath = switch (status) {
                    case RUNNING -> null;
                    case DONE -> phasedSession.result();
                    case FAILED -> Collections.emptyList();
                };

                if (status != PhasedPathSession.Status.RUNNING) {
                    phasedSession = null;
                }
            } else {
                newPath = findPathSynchronously(
                    mob,
                    pathStart,
                    searchCrawlGoal,
                    nearbyTunnelEntry,
                    target,
                    fluidGoalRadius
                );
            }

            if (newPath != null) {
                path = newPath;
                pathIndex = path.size() > 1 ? 1 : 0;
                repathCooldown = repathInterval;

                if (isCrawlingNow && pathIndex < path.size()) {
                    while (pathIndex < path.size() && hasReachedWaypoint(mob, path.get(pathIndex), true)) {
                        pathIndex++;
                    }
                }

                if (!blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER) && !path.isEmpty()) {
                    checkPathForBreakableWall(mob, blackboard, path, pathIndex);
                }
            }
        }

        if (!blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER)) {
            var encasingWallBlock = findEncasingWallBreakTarget(mob, target, path, pathIndex);

            if (encasingWallBlock != null) {
                blackboard.set(AiKeys.BREAK_TO_TARGET_SCAN, encasingWallBlock);
                blackboard.set(AiKeys.BREAK_TO_TARGET_TRIGGER, Boolean.TRUE);

                var directDirection = target.position().subtract(mob.position());
                if (directDirection.lengthSqr() > 0.0001D) {
                    applyFlatFallback(mob, blackboard, target, directDirection);
                    if (pendingOutcome != null) {
                        var outcome = pendingOutcome;
                        pendingOutcome = null;
                        return outcome;
                    }
                    return ActionOutcome.running();
                }
            }
        }

        if (!path.isEmpty()) {
            while (
                pathIndex < path.size()
                    && hasReachedWaypoint(mob, path.get(pathIndex), shouldUseCrawlingNow)
            ) {
                pathIndex++;
            }

            if (pathIndex < path.size()) {
                var waypointBlock = path.get(pathIndex);
                var waypointCenter = Vec3.atBottomCenterOf(waypointBlock);

                var waypointIsTightPassage =
                    nodeCache.tunnelCanStandAt(mob.level(), mob, waypointBlock)
                        || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock)
                        || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                        || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below(2));

                var approachingTunnel = waypointIsTightPassage;
                if (!approachingTunnel) {
                    for (var lookahead = pathIndex; lookahead < Math.min(path.size(), pathIndex + 4); lookahead++) {
                        var la = path.get(lookahead);
                        if (
                            nodeCache.tunnelCanStandAt(mob.level(), mob, la)
                                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, la)
                        ) {
                            approachingTunnel = true;
                            break;
                        }
                    }
                }

                var tunnelTargetBlock = waypointBlock;
                if (approachingTunnel && !waypointIsTightPassage) {
                    for (var lookahead = pathIndex; lookahead < Math.min(path.size(), pathIndex + 4); lookahead++) {
                        var la = path.get(lookahead);
                        if (
                            nodeCache.tunnelCanStandAt(mob.level(), mob, la)
                                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, la)
                        ) {
                            tunnelTargetBlock = la;
                            break;
                        }
                    }
                }

                var waypoint = shouldUseCrawlingNow && !waypointIsTightPassage
                    ? snapToNearestWallFace(mob, waypointBlock, waypointCenter)
                    : waypointCenter;

                var direction = waypoint.subtract(mob.position());

                if ((waypointIsTightPassage || approachingTunnel) && mobOnSolidGround) {
                    var tunnelCenter = Vec3.atBottomCenterOf(tunnelTargetBlock);
                    var horiz = new Vec3(tunnelCenter.x - mob.getX(), 0, tunnelCenter.z - mob.getZ());
                    if (horiz.lengthSqr() > 0.09D) {
                        var toEntrance = horiz.normalize();
                        var move = getMove(mob, toEntrance, tunnelCenter);

                        var entranceYError = tunnelCenter.y - mob.getY();
                        var closeEnoughToClimb = horiz.lengthSqr() < 2.25D;
                        if (canCrawl && entranceYError > 0.25D && closeEnoughToClimb) {
                            var climbY = Mth.clamp(entranceYError * 0.6D, speed * 0.5D, speed);
                            move = new Vec3(move.x, climbY, move.z);
                            CrawlController.setWallCrawling(mob, true);
                            CrawlController.updateCrawlOrientation(mob, move);
                        } else if (canCrawl) {
                            if (closeEnoughToClimb) {
                                CrawlController.setWallCrawling(mob, true);
                                CrawlController.updateCrawlOrientation(mob, move);
                            }
                        }
                        mob.setDeltaMovement(move);
                        mob.hasImpulse = true;
                        faceMovementDirection(mob, move);
                        return ActionOutcome.running();
                    } else if (waypointIsTightPassage) {
                        pathIndex++;
                        if (pathIndex < path.size()) {
                            waypointBlock = path.get(pathIndex);
                            waypointCenter = Vec3.atBottomCenterOf(waypointBlock);
                            waypoint = waypointCenter;
                            direction = waypoint.subtract(mob.position());
                        }
                    }
                }

                if (direction.lengthSqr() > 0.0001D) {
                    applyPathMovement(
                        blackboard,
                        mob,
                        target,
                        waypointBlock,
                        waypoint,
                        direction,
                        shouldUseCrawlingNow
                    );
                    if (pendingOutcome != null) {
                        var outcome = pendingOutcome;
                        pendingOutcome = null;
                        return outcome;
                    }
                    return ActionOutcome.running();
                }
            }
        }

        var directDirection = target.position().subtract(mob.position());

        if (directDirection.lengthSqr() > 0.0001D) {
            applyFlatFallback(mob, blackboard, target, directDirection);
            if (pendingOutcome != null) {
                var outcome = pendingOutcome;
                pendingOutcome = null;
                return outcome;
            }
            return ActionOutcome.running();
        }

        halt(mob);
        faceTarget(mob, target);
        return ActionOutcome.running();
    }

    private @NotNull Vec3 getMove(E mob, Vec3 toEntrance, Vec3 tunnelCenter) {
        var absX = Math.abs(toEntrance.x);
        var absZ = Math.abs(toEntrance.z);
        Vec3 move;
        if (absZ > absX) {
            var xError = tunnelCenter.x - mob.getX();
            var xCorrect = Mth.clamp(xError * 2.0D, -speed * 0.5D, speed * 0.5D);
            move = new Vec3(xCorrect, mob.getDeltaMovement().y, Math.copySign(speed, toEntrance.z));
        } else {
            var zError = tunnelCenter.z - mob.getZ();
            var zCorrect = Mth.clamp(zError * 2.0D, -speed * 0.5D, speed * 0.5D);
            move = new Vec3(Math.copySign(speed, toEntrance.x), mob.getDeltaMovement().y, zCorrect);
        }
        return move;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
        if (canCrawl) {
            CrawlController.setWallCrawling(mob, false);
        }
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25D,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25D
        );
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    private void applyPathMovement(
        Blackboard blackboard,
        E mob,
        LivingEntity target,
        BlockPos waypointBlock,
        Vec3 waypoint,
        Vec3 direction,
        boolean shouldUseCrawlingNow
    ) {
        if (blockBreakCooldown > 0)
            blockBreakCooldown--;
        if (crowdPushCooldown > 0)
            crowdPushCooldown--;
        if (dangerLeapCooldown > 0)
            dangerLeapCooldown--;

        var movedSqr = mob.position().distanceToSqr(lastPos);
        lastPos = mob.position();

        if (movedSqr < 0.0025D)
            stuckTicks++;
        else
            stuckTicks = 0;

        var currentDistSq = mob.distanceToSqr(target);
        if (currentDistSq < lastDistSqToTarget - 0.1D) {
            lastDistSqToTarget = currentDistSq;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        if (noProgressTicks >= HARD_NO_PROGRESS_TICKS || ticksSinceStart >= ABSOLUTE_MAX_CHASE_TICKS) {
            var forwardDir = new Vec3(direction.x, 0.0D, direction.z);
            var blockingPositions = forwardDir.lengthSqr() > 0.01D
                ? findForwardBlockingPositions(mob, target, forwardDir.normalize())
                : List.<BlockPos>of();
            pendingOutcome = blockingPositions.isEmpty()
                ? ActionOutcome.failed(PlanFailureReason.FAILED_STUCK, mob.blockPosition())
                : ActionOutcome.failed(PlanFailureReason.FAILED_BLOCKED, mob.blockPosition(), blockingPositions);
            return;
        }

        if (noProgressTicks >= SOFT_NO_PROGRESS_TICKS && pendingOutcome == null) {
            var forwardDir = new Vec3(direction.x, 0.0D, direction.z);
            var blockingPositions = forwardDir.lengthSqr() > 0.01D
                ? findForwardBlockingPositions(mob, target, forwardDir.normalize())
                : List.<BlockPos>of();
            pendingOutcome = blockingPositions.isEmpty()
                ? ActionOutcome.blocked(PlanFailureReason.FAILED_STUCK, mob.blockPosition())
                : ActionOutcome.blocked(PlanFailureReason.FAILED_BLOCKED, mob.blockPosition(), blockingPositions);
        }

        var canAttach = canAttachToClimbSurface(mob, waypoint);
        var crawlIsBetterOption = canCrawl
            && !CrawlController.isWallCrawling(mob)
            && canAttach;

        if (mob.horizontalCollision && blockBreakCooldown <= 0 && !crawlIsBetterOption) {
            var forwardDir = new Vec3(direction.x, 0.0D, direction.z);
            if (forwardDir.lengthSqr() > 0.01D) {
                forwardDir = forwardDir.normalize();
                var toTarget = target.position().subtract(mob.position());
                var toTargetH = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (toTargetH.lengthSqr() > 0.01D && forwardDir.dot(toTargetH.normalize()) > 0.5D) {
                    if (tryBreakBlockingPathBlock(mob, blackboard, target, forwardDir)) {
                        blockBreakCooldown = 10;
                        noProgressTicks = 0;
                        stuckTicks = 0;
                        repathCooldown = 0;
                        faceTarget(mob, target);
                        return;
                    }
                }
            }
        }

        if (noProgressTicks > 5 && blockBreakCooldown <= 0 && !crawlIsBetterOption) {
            var forwardDir = new Vec3(direction.x, 0.0D, direction.z);
            if (forwardDir.lengthSqr() > 0.01D) {
                forwardDir = forwardDir.normalize();
                if (tryBreakBlockingPathBlock(mob, blackboard, target, forwardDir)) {
                    blockBreakCooldown = 10;
                    noProgressTicks = 0;
                    stuckTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }
        }

        if (
            noProgressTicks > 5 && blockBreakCooldown <= 0 && shouldUseCrawlingNow && target.getY() > mob.getY() + 0.5D
        ) {
            if (tryBreakOverheadObstacle(mob, blackboard)) {
                blockBreakCooldown = 10;
                noProgressTicks = 0;
                stuckTicks = 0;
                repathCooldown = 0;
                faceTarget(mob, target);
                return;
            }
        }

        if (noProgressTicks > 15 && crowdPushCooldown <= 0) {
            var forwardDir = new Vec3(direction.x, 0.0D, direction.z);
            if (forwardDir.lengthSqr() > 0.01D && tryPushThroughCrowd(mob, target, forwardDir.normalize())) {
                crowdPushCooldown = 15;
                stuckTicks = 0;
                faceTarget(mob, target);
                return;
            }
        }

        var waypointIsVerticalShaft =
            nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock);

        var waypointLeadsIntoVerticalShaft =
            waypointIsVerticalShaft
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below(2));

        var mobFeet = BlockPos.containing(
            mob.getX(),
            mob.getBoundingBox().minY,
            mob.getZ()
        );
        var mobInFluid = !mob.level().getBlockState(mobFeet).getFluidState().isEmpty();

        var verticalStepUp =
            canCrawl
                && shouldUseCrawlingNow
                && needsCrawlStepUp(mob, waypointBlock, mobFeet);

        if (shouldUseCrawlingNow) {
            var waypointIsTopSurface = nodeCache.canStandAt(mob.level(), mob, waypointBlock)
                && !nodeCache.isSafeClimbNode(mob.level(), waypointBlock, mob)
                && waypointBlock.getY() > mobFeet.getY();
            var horizToWp = new Vec3(waypoint.x - mob.getX(), 0.0D, waypoint.z - mob.getZ());
            if (
                waypointIsTopSurface
                    && waypoint.y > mob.getY() + 0.05D
                    && waypoint.y <= mob.getY() + 1.4D
                    && horizToWp.lengthSqr() < 1.6D
            ) {
                var overDir = horizToWp.lengthSqr() > 1.0e-4D ? horizToWp.normalize() : Vec3.ZERO;
                var crest = new Vec3(overDir.x * speed, Math.max(speed * 0.6D, 0.3D), overDir.z * speed);
                CrawlController.setWallCrawling(mob, true);
                CrawlController.updateCrawlOrientation(mob, crest);
                mob.setDeltaMovement(crest);
                mob.hasImpulse = true;
                faceMovementDirection(mob, crest);
                return;
            }
        }

        if (verticalStepUp) {
            var climbTarget = findUpcomingHigherPathNode(mobFeet);
            if (climbTarget == null) {
                climbTarget = waypointBlock;
            }

            var climbCenter = Vec3.atBottomCenterOf(climbTarget);
            var horizontalToClimb = new Vec3(
                climbCenter.x - mob.getX(),
                0.0D,
                climbCenter.z - mob.getZ()
            );

            Vec3 move;

            if (horizontalToClimb.lengthSqr() > 0.01D) {
                var horiz = horizontalToClimb.normalize().scale(speed * 0.75D);

                var corrected = removeBlockedHorizontalComponents(
                    mob,
                    new Vec3(
                        horiz.x,
                        Math.max(mob.getDeltaMovement().y, speed * 0.85D),
                        horiz.z
                    )
                );

                if (corrected.horizontalDistanceSqr() < 0.0001D) {
                    var raisedBox = mob.getBoundingBox().move(horiz.x, 1.05D, horiz.z);
                    if (mob.level().noBlockCollision(mob, raisedBox)) {
                        corrected = new Vec3(0.0D, Math.max(mob.getDeltaMovement().y, speed * 0.95D), 0.0D);
                    } else {
                        corrected = findCornerEscapeStepUpVelocity(mob, horizontalToClimb);
                    }
                }

                move = corrected;
            } else {
                var wallDir = findNearestWallDirection(mob);
                var intoWall = wallDir != null ? wallDir.scale(speed * 0.3D) : Vec3.ZERO;
                move = new Vec3(
                    intoWall.x,
                    Math.max(mob.getDeltaMovement().y, speed * 0.85D),
                    intoWall.z
                );
            }

            CrawlController.setWallCrawling(mob, true);
            CrawlController.updateCrawlOrientation(mob, move);
            mob.setDeltaMovement(move);
            mob.hasImpulse = true;
            faceMovementDirection(mob, move);

            return;
        }

        if (waypointLeadsIntoVerticalShaft && target.getY() < mob.getY() - 0.75D) {
            var shaftBlock = waypointIsVerticalShaft
                ? waypointBlock
                : nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                    ? waypointBlock.below()
                    : waypointBlock.below(2);

            var shaftCenter = Vec3.atBottomCenterOf(shaftBlock);
            var centerError = new Vec3(
                shaftCenter.x - mob.getX(),
                0.0D,
                shaftCenter.z - mob.getZ()
            );

            var centerDistSqr = centerError.lengthSqr();

            Vec3 shaftVelocity;

            if (centerDistSqr > 0.04D) {
                var centerMove = centerError.normalize().scale(speed * 0.65D);
                var yPull = mob.isNoGravity() ? -speed * 0.3D : mob.getDeltaMovement().y * 0.25D;
                shaftVelocity = new Vec3(
                    centerMove.x,
                    yPull,
                    centerMove.z
                );
            } else {
                CrawlController.setWallCrawling(mob, false);
                shaftVelocity = new Vec3(
                    centerError.x * 0.35D,
                    -speed * 0.85D,
                    centerError.z * 0.35D
                );
            }

            CrawlController.setWallCrawling(mob, true);
            CrawlController.updateCrawlOrientation(mob, shaftVelocity);
            mob.setDeltaMovement(shaftVelocity);
            mob.hasImpulse = true;
            faceMovementDirection(mob, shaftVelocity);
            return;
        }

        var waypointIsTunnel =
            nodeCache.tunnelCanStandAt(mob.level(), mob, waypointBlock);

        var mobIsInTunnel =
            nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeet)
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeet.below())
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeet.above())
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeet.below(2))
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeet)
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeet.below())
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeet.below(2));

        if (waypointIsTunnel || mobIsInTunnel) {
            var horizontal = new Vec3(direction.x, 0.0D, direction.z);
            var yError = waypoint.y - mob.getY();

            if (horizontal.lengthSqr() > 0.0001D) {
                var centerMove = horizontal.normalize().scale(speed);

                var vertical = 0.0D;

                if (yError > 0.20D) {
                    vertical = Mth.clamp(yError * 0.35D, 0.08D, 0.32D);
                } else if (yError < -0.20D) {
                    vertical = Mth.clamp(yError * 0.35D, -0.32D, -0.04D);
                } else {
                    vertical = mob.getDeltaMovement().y * 0.20D;
                }

                var descendingInTunnel = yError < -0.20D;

                CrawlController.setWallCrawling(mob, !descendingInTunnel);

                var tunnelSpeed = waypointIsTunnel ? speed : speed * 1.25D;

                var tunnelVelocity = new Vec3(
                    centerMove.normalize().x * tunnelSpeed,
                    vertical,
                    centerMove.normalize().z * tunnelSpeed
                );

                if (!descendingInTunnel) {
                    CrawlController.updateCrawlOrientation(mob, tunnelVelocity);
                }
                mob.setDeltaMovement(tunnelVelocity);
                mob.hasImpulse = true;
                faceMovementDirection(mob, tunnelVelocity);
                return;
            }

            if (Math.abs(yError) > 0.20D) {
                var vertical = Mth.clamp(yError * 0.35D, -0.32D, 0.32D);
                var tunnelVelocity = new Vec3(0.0D, vertical, 0.0D);

                var descending = yError < -0.20D;
                CrawlController.setWallCrawling(mob, !descending);
                if (descending) {
                    vertical = Math.min(vertical, -speed * 0.6D);
                    tunnelVelocity = new Vec3(0.0D, vertical, 0.0D);
                }
                CrawlController.updateCrawlOrientation(mob, tunnelVelocity);
                mob.setDeltaMovement(tunnelVelocity);
                mob.hasImpulse = true;
                faceMovementDirection(mob, tunnelVelocity);
                return;
            }
        }

        var waypointIsGroundOnly = nodeCache.canStandAt(mob.level(), mob, waypointBlock)
            && !nodeCache.isSafeClimbNode(mob.level(), waypointBlock, mob)
            && waypointBlock.getY() <= mobFeet.getY();
        var canAttachToWall = shouldUseCrawlingNow
            && !waypointIsGroundOnly
            && canAttachToClimbSurface(mob, waypoint);

        if (canAttachToWall) {
            var waypointY = waypoint.y;
            var targetAbove = target.getY() > mob.getY() + 0.75D;
            var yError = waypointY - mob.getY();
            var horizontal = new Vec3(direction.x, 0.0D, direction.z);

            var wallNudge = findNearestWallDirection(mob);
            var intoWall = wallNudge != null ? wallNudge.scale(speed * 0.35D) : Vec3.ZERO;

            Vec3 crawlVelocity;
            if (targetAbove) {
                crawlVelocity = new Vec3(intoWall.x, speed * 0.85D, intoWall.z);
            } else if (yError < -0.10D) {
                var verticalSpeed = Mth.clamp(yError * 0.45D, -speed, -speed * 0.15D);
                if (horizontal.lengthSqr() > 0.01D) {
                    var moveDir = horizontal.normalize().scale(speed);
                    crawlVelocity = new Vec3(
                        moveDir.x + intoWall.x,
                        verticalSpeed,
                        moveDir.z + intoWall.z
                    );
                } else {
                    crawlVelocity = new Vec3(intoWall.x, verticalSpeed, intoWall.z);
                    if (crawlVelocity.horizontalDistanceSqr() < 0.0001D) {
                        CrawlController.setWallCrawling(mob, false);
                        faceTarget(mob, target);
                        return;
                    }
                }
            } else if (yError > 0.10D) {
                var verticalSpeed = Mth.clamp(yError * 0.45D, 0.0D, speed);
                if (horizontal.lengthSqr() > 0.01D) {
                    var moveDir = horizontal.normalize().scale(speed);
                    crawlVelocity = new Vec3(moveDir.x + intoWall.x, verticalSpeed, moveDir.z + intoWall.z);
                } else {
                    crawlVelocity = new Vec3(intoWall.x, verticalSpeed, intoWall.z);
                }
            } else if (horizontal.lengthSqr() > 0.01D) {
                var moveDir = horizontal.normalize().scale(speed);
                crawlVelocity = new Vec3(moveDir.x + intoWall.x, intoWall.y, moveDir.z + intoWall.z);
            } else {
                crawlVelocity = NavigationQueries.computeWallCrawlVelocity(mob, waypoint, speed);
            }

            if (crawlVelocity.lengthSqr() < 1e-6D)
                crawlVelocity = Vec3.ZERO;

            CrawlController.setWallCrawling(mob, true);
            CrawlController.updateCrawlOrientation(mob, crawlVelocity);
            mob.setDeltaMovement(crawlVelocity);
            mob.hasImpulse = true;
            faceMovementDirection(mob, crawlVelocity);
            return;
        }

        if (canCrawl) {
            CrawlController.setWallCrawling(mob, false);
        }

        if (shouldUseCrawlingNow) {
            var toWaypoint = new Vec3(direction.x, 0.0D, direction.z);
            if (toWaypoint.lengthSqr() > 0.01D) {
                CrawlController.setWallCrawling(mob, false);
                var move = toWaypoint.normalize().scale(speed);
                var yVel = Math.min(mob.getDeltaMovement().y, -0.15D);
                mob.setDeltaMovement(move.x, yVel, move.z);
                mob.hasImpulse = true;
                faceMovementDirection(mob, move);
                return;
            }
        }

        var horizontal = new Vec3(direction.x, 0.0D, direction.z);

        if (horizontal.lengthSqr() < 0.01D) {
            if (shouldUseCrawlingNow && direction.y > 0.1D) {
                var wallDir = findNearestWallDirection(mob);
                if (wallDir != null) {
                    CrawlController.setWallCrawling(mob, true);
                    var pushVelocity = new Vec3(wallDir.x * speed * 0.3D, speed * 0.6D, wallDir.z * speed * 0.3D);
                    CrawlController.updateCrawlOrientation(mob, pushVelocity);
                    mob.setDeltaMovement(pushVelocity);
                    mob.hasImpulse = true;
                    faceMovementDirection(mob, pushVelocity);
                    return;
                }
            }
            halt(mob);
            faceMovementDirection(mob, horizontal);
            return;
        }

        if (!shouldUseCrawlingNow && waypointBlock.getY() < mob.blockPosition().getY()) {
            BlockPos lookAheadBlock = waypointBlock;
            for (var i = pathIndex; i < Math.min(path.size(), pathIndex + 6); i++) {
                var candidate = path.get(i);
                if (candidate.getY() <= mob.blockPosition().getY()) {
                    lookAheadBlock = candidate;
                } else {
                    break;
                }
            }
            var lookAheadCenter = Vec3.atBottomCenterOf(lookAheadBlock);
            var toGoal = new Vec3(lookAheadCenter.x - mob.getX(), 0.0D, lookAheadCenter.z - mob.getZ());
            if (toGoal.lengthSqr() > 0.01D) {
                var stepDown = toGoal.normalize().scale(speed);
                var yVel = Math.min(mob.getDeltaMovement().y, -0.15D);

                mob.setDeltaMovement(stepDown.x, yVel, stepDown.z);
                mob.hasImpulse = true;
                faceMovementDirection(mob, stepDown);
                return;
            }
        }

        var forward = horizontal.normalize();
        var movement = MovementController.steerAwayFromDangerEntities(mob, forward.scale(speed));

        if (detourTicks > 0) {
            detourTicks--;
            var detourMove = detourDirection.scale(speed);
            var detourSafe = MovementController.findSafeMovement(mob, detourMove, steerBias);
            if (!detourSafe.equals(Vec3.ZERO)) {
                mob.setDeltaMovement(detourSafe.x, mob.getDeltaMovement().y, detourSafe.z);
                mob.hasImpulse = true;
                faceTarget(mob, target);
                return;
            }
            detourTicks = 0;
        }

        if (stuckTicks > 10) {
            if (
                shouldUseCrawlingNow
                    && CrawlController.isWallCrawling(mob)
                    && target.getY() < mob.getY() - 1.0D
                    && waypointBlock.getY() < mob.blockPosition().getY()
            ) {
                CrawlController.setWallCrawling(mob, false);

                var downForward = new Vec3(direction.x, 0.0D, direction.z);
                if (downForward.lengthSqr() > 0.0001D) {
                    downForward = downForward.normalize().scale(speed * 0.6D);
                }

                mob.setDeltaMovement(
                    downForward.x,
                    -Math.max(0.18D, speed * 0.45D),
                    downForward.z
                );

                mob.hasImpulse = true;
                stuckTicks = 0;
                repathCooldown = 0;
                faceTarget(mob, target);
                return;
            }

            var targetBelow = target.getY() < mob.getY() - 1.0D;

            if (!targetBelow && mob.onGround() && isStairBlockAhead(mob, forward)) {
                mob.setDeltaMovement(forward.x * speed * 0.9D, 0.32D, forward.z * speed * 0.9D);
                mob.hasImpulse = true;
                stuckTicks = 0;
                faceTarget(mob, target);
                return;
            }

            if (targetBelow && blockBreakCooldown <= 0) {
                if (tryBreakBlockingPathBlock(mob, blackboard, target, forward)) {
                    blockBreakCooldown = 10;
                    stuckTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            if (targetBelow && stuckTicks > 15) {
                var toTarget = target.position().subtract(mob.position());
                var nudge = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (nudge.lengthSqr() > 0.0001D) {
                    var walkOff = nudge.normalize().scale(speed);
                    mob.setDeltaMovement(walkOff.x, mob.getDeltaMovement().y, walkOff.z);
                    mob.hasImpulse = true;
                    stuckTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            var left = new Vec3(-forward.z, 0.0D, forward.x);
            var right = new Vec3(forward.z, 0.0D, -forward.x);

            if (!targetBelow && blockBreakCooldown <= 0) {
                if (tryBreakBlockingPathBlock(mob, blackboard, target, forward)) {
                    blockBreakCooldown = 10;
                    stuckTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            if (
                stuckTicks >= 8
                    && dangerLeapCooldown <= 0
                    && mob.onGround()
                    && MovementController.hasNearbyDangerEntity(mob)
            ) {
                var leapDirection = new Vec3(movement.x, 0.0D, movement.z);
                if (leapDirection.lengthSqr() < 0.0001D)
                    leapDirection = forward;

                if (TraversalQueries.hasSafeLandingAfterLeap(mob, leapDirection, 3.0D)) {
                    var leap = leapDirection.normalize().scale(0.75D);
                    mob.setDeltaMovement(leap.x, 0.75D, leap.z);
                    mob.hasImpulse = true;
                    dangerLeapCooldown = 30;
                    stuckTicks = 0;
                    detourTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            if (!targetBelow && TraversalQueries.isSafeAhead(mob, left, 1.25D)) {
                detourDirection = left;
                detourTicks = 20;
                stuckTicks = 0;
            } else if (!targetBelow && TraversalQueries.isSafeAhead(mob, right, 1.25D)) {
                detourDirection = right;
                detourTicks = 20;
                stuckTicks = 0;
            } else if (mob.onGround() && target.getY() >= mob.getY() - 0.5D) {
                mob.setDeltaMovement(movement.x * 0.8D, 0.42D, movement.z * 0.8D);
                mob.hasImpulse = true;
                stuckTicks = 0;
                repathCooldown = 0;
                faceTarget(mob, target);
                return;
            }
        }

        if (mobInFluid) {
            var climbBoost = 0.0D;
            if (mob.horizontalCollision) {
                var aboveFeet = mobFeet.above();
                var headClear = mob.level()
                    .getBlockState(aboveFeet)
                    .getCollisionShape(mob.level(), aboveFeet)
                    .isEmpty();
                if (headClear) {
                    climbBoost = 0.4D;
                }
            }

            var yVel = climbBoost > 0.0D
                ? Math.max(mob.getDeltaMovement().y, climbBoost)
                : mob.getDeltaMovement().y;

            var safeFluidMove = MovementController.findSafeMovement(mob, movement, steerBias);
            if (safeFluidMove.equals(Vec3.ZERO)) {
                mob.setDeltaMovement(0.0D, yVel, 0.0D);
            } else {
                mob.setDeltaMovement(safeFluidMove.x, yVel, safeFluidMove.z);
            }
            mob.hasImpulse = true;
            faceTarget(mob, target);
            return;
        }

        var safe = MovementController.findSafeMovement(mob, movement, steerBias);

        if (safe.equals(Vec3.ZERO)) {
            var targetBelow = target.getY() < mob.getY() - 1.5D;
            if (targetBelow) {
                var toTarget = target.position().subtract(mob.position());
                var nudge = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (nudge.lengthSqr() > 0.0001D) {
                    var walkOff = nudge.normalize().scale(speed);
                    mob.setDeltaMovement(walkOff.x, mob.getDeltaMovement().y, walkOff.z);
                    mob.hasImpulse = true;
                    faceTarget(mob, target);
                    return;
                }
            }
            halt(mob);
            faceTarget(mob, target);
            return;
        }

        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;
        faceTarget(mob, target);
    }

    private void applyFlatFallback(E mob, Blackboard blackboard, LivingEntity target, Vec3 direction) {
        var movedSqr = mob.position().distanceToSqr(lastPos);
        lastPos = mob.position();
        if (movedSqr < 0.0025D)
            stuckTicks++;
        else
            stuckTicks = 0;

        if (blockBreakCooldown > 0)
            blockBreakCooldown--;
        if (crowdPushCooldown > 0)
            crowdPushCooldown--;

        var horizontal = new Vec3(direction.x, 0.0D, direction.z);
        var forward = horizontal.lengthSqr() > 0.01D ? horizontal.normalize() : Vec3.ZERO;

        var currentDistSqFlat = mob.distanceToSqr(target);
        if (currentDistSqFlat < lastDistSqToTarget - 0.1D) {
            lastDistSqToTarget = currentDistSqFlat;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        if (noProgressTicks >= HARD_NO_PROGRESS_TICKS || ticksSinceStart >= ABSOLUTE_MAX_CHASE_TICKS) {
            var blockingPositions = forward.lengthSqr() > 0.0001D
                ? findForwardBlockingPositions(mob, target, forward)
                : List.<BlockPos>of();
            pendingOutcome = blockingPositions.isEmpty()
                ? ActionOutcome.failed(PlanFailureReason.FAILED_STUCK, mob.blockPosition())
                : ActionOutcome.failed(PlanFailureReason.FAILED_BLOCKED, mob.blockPosition(), blockingPositions);
            return;
        }

        if (noProgressTicks >= SOFT_NO_PROGRESS_TICKS && pendingOutcome == null) {
            var blockingPositions = forward.lengthSqr() > 0.0001D
                ? findForwardBlockingPositions(mob, target, forward)
                : List.<BlockPos>of();
            pendingOutcome = blockingPositions.isEmpty()
                ? ActionOutcome.blocked(PlanFailureReason.FAILED_STUCK, mob.blockPosition())
                : ActionOutcome.blocked(PlanFailureReason.FAILED_BLOCKED, mob.blockPosition(), blockingPositions);
        }

        if (mob.horizontalCollision && blockBreakCooldown <= 0 && !forward.equals(Vec3.ZERO)) {
            var toTarget = target.position().subtract(mob.position());
            var toTargetH = new Vec3(toTarget.x, 0.0D, toTarget.z);
            if (toTargetH.lengthSqr() > 0.01D && forward.dot(toTargetH.normalize()) > 0.5D) {
                if (tryBreakBlockingPathBlock(mob, blackboard, target, forward)) {
                    blockBreakCooldown = 10;
                    stuckTicks = 0;
                    noProgressTicks = 0;
                    faceTarget(mob, target);
                    return;
                }
            }
        }

        if ((stuckTicks > 10 || noProgressTicks > 20) && blockBreakCooldown <= 0 && !forward.equals(Vec3.ZERO)) {
            if (tryBreakBlockingPathBlock(mob, blackboard, target, forward)) {
                blockBreakCooldown = 10;
                stuckTicks = 0;
                noProgressTicks = 0;
                faceTarget(mob, target);
                return;
            }
        }

        if ((stuckTicks > 15 || noProgressTicks > 25) && crowdPushCooldown <= 0 && !forward.equals(Vec3.ZERO)) {
            if (tryPushThroughCrowd(mob, target, forward)) {
                crowdPushCooldown = 15;
                stuckTicks = 0;
                faceTarget(mob, target);
                return;
            }
        }

        if (!forward.equals(Vec3.ZERO)) {
            var movement = MovementController.steerAwayFromDangerEntities(mob, forward.scale(speed));
            var safe = MovementController.findSafeMovement(mob, movement, steerBias);

            if (!safe.equals(Vec3.ZERO)) {
                mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
                mob.hasImpulse = true;
            } else {
                halt(mob);
            }
        } else {
            halt(mob);
        }

        faceTarget(mob, target);
    }

    private boolean hasReachedWaypoint(E mob, BlockPos waypoint, boolean shouldUseCrawling) {
        var waypointCenter = Vec3.atBottomCenterOf(waypoint);

        if (!shouldUseCrawling) {
            var dx = mob.getX() - waypointCenter.x;
            var dz = mob.getZ() - waypointCenter.z;
            var horizontalDistSqr = dx * dx + dz * dz;
            var yError = waypointCenter.y - mob.getY();

            return horizontalDistSqr < 1.0D && yError > -2.0D && yError < 1.5D;
        }

        var dx = mob.getX() - waypointCenter.x;
        var dz = mob.getZ() - waypointCenter.z;
        var horizontalDistSqr = dx * dx + dz * dz;
        var yError = waypointCenter.y - mob.getY();

        var level = mob.level();
        var isTunnel = nodeCache.tunnelCanStandAt(level, mob, waypoint);
        var isVerticalShaft = nodeCache.verticalShaftCanCrawlAt(level, mob, waypoint);

        if (isVerticalShaft) {
            var shaftRadius = 0.40D;

            if (horizontalDistSqr > shaftRadius * shaftRadius)
                return false;

            return Math.abs(yError) < 0.55D;
        }

        var waypointAbove = waypoint.getY() > BlockPos.containing(
            mob.getX(),
            mob.getBoundingBox().minY,
            mob.getZ()
        ).getY();

        var reachRadius = isTunnel ? 0.6D : waypointAbove ? 0.45D : 1.15D;

        if (horizontalDistSqr > reachRadius * reachRadius)
            return false;
        if (yError > 0.45D)
            return false;
        return !(yError < -1.0D);
    }

    private Vec3 snapToNearestWallFace(E mob, BlockPos block, Vec3 center) {
        var level = mob.level();
        var halfMob = mob.getBbWidth() / 2.0D;
        var faceOffset = 0.5D - halfMob;

        var pushX = 0.0D;
        var pushZ = 0.0D;
        var solidFaces = 0;

        if (!level.getBlockState(block.east()).getCollisionShape(level, block.east()).isEmpty()) {
            pushX -= faceOffset;
            solidFaces++;
        }

        if (!level.getBlockState(block.west()).getCollisionShape(level, block.west()).isEmpty()) {
            pushX += faceOffset;
            solidFaces++;
        }

        if (!level.getBlockState(block.south()).getCollisionShape(level, block.south()).isEmpty()) {
            pushZ -= faceOffset;
            solidFaces++;
        }

        if (!level.getBlockState(block.north()).getCollisionShape(level, block.north()).isEmpty()) {
            pushZ += faceOffset;
            solidFaces++;
        }

        if (solidFaces == 0) {
            return center;
        }

        return new Vec3(center.x + pushX, center.y, center.z + pushZ);
    }

    private Vec3 findNearestWallDirection(E mob) {
        var level = mob.level();
        var box = mob.getBoundingBox();
        var probe = (mob.getBbWidth() / 2.0D) + 0.6D;
        var standingBox = box.move(0.0D, 1.0D, 0.0D);

        Vec3 best = null;
        var bestDist = Double.MAX_VALUE;

        var dirs = new Vec3[] {
            new Vec3(1, 0, 0),
            new Vec3(-1, 0, 0),
            new Vec3(0, 0, 1),
            new Vec3(0, 0, -1)
        };
        for (var dir : dirs) {
            var hitCurrent = !level.noBlockCollision(mob, box.move(dir.scale(probe)));
            var hitStanding = !level.noBlockCollision(mob, standingBox.move(dir.scale(probe)));
            if (hitCurrent || hitStanding) {
                var dist = probe;
                for (var d = 0.1D; d <= probe; d += 0.1D) {
                    if (!level.noBlockCollision(mob, box.move(dir.scale(d)))) {
                        dist = d;
                        break;
                    }
                }
                if (dist < bestDist) {
                    bestDist = dist;
                    best = dir;
                }
            }
        }
        return best;
    }

    private boolean isStairBlockAhead(E mob, Vec3 forward) {
        var level = mob.level();
        var checkPos = mob.position().add(forward.scale(0.6D));
        var feetY = mob.getBoundingBox().minY;

        var feet = BlockPos.containing(checkPos.x, feetY, checkPos.z);
        var head = feet.above();

        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(head);

        if (feetState.getCollisionShape(level, feet).isEmpty())
            return false;
        if (!headState.getCollisionShape(level, head).isEmpty())
            return false;

        var landing = head.above();
        var landingState = level.getBlockState(landing);
        return landingState.getCollisionShape(level, landing).isEmpty();
    }

    private boolean canAttachToClimbSurface(E mob, Vec3 waypoint) {
        if (NavigationQueries.needsWallCrawl(mob, waypoint))
            return true;
        if (mob.horizontalCollision)
            return true;

        var level = mob.level();
        var box = mob.getBoundingBox();
        var checkDistance = (mob.getBbWidth() / 2.0D) + 0.5D;

        if (!level.noBlockCollision(mob, box.move(0.0D, 0.0D, -checkDistance)))
            return true;
        if (!level.noBlockCollision(mob, box.move(0.0D, 0.0D, checkDistance)))
            return true;
        if (!level.noBlockCollision(mob, box.move(-checkDistance, 0.0D, 0.0D)))
            return true;
        if (!level.noBlockCollision(mob, box.move(checkDistance, 0.0D, 0.0D)))
            return true;

        if (waypoint.y > mob.getY() + 0.5D) {
            var toWaypoint = new Vec3(waypoint.x - mob.getX(), 0.0D, waypoint.z - mob.getZ());

            if (toWaypoint.lengthSqr() < 0.25D) {
                var standingBox = box.move(0.0D, 1.0D, 0.0D);
                var sideProbe = (mob.getBbWidth() / 2.0D) + 0.6D;
                if (!level.noBlockCollision(mob, standingBox.move(sideProbe, 0.0D, 0.0D)))
                    return true;
                if (!level.noBlockCollision(mob, standingBox.move(-sideProbe, 0.0D, 0.0D)))
                    return true;
                if (!level.noBlockCollision(mob, standingBox.move(0.0D, 0.0D, sideProbe)))
                    return true;
                return !level.noBlockCollision(mob, standingBox.move(0.0D, 0.0D, -sideProbe));
            } else if (toWaypoint.lengthSqr() > 0.0001D) {
                var probeDir = toWaypoint.normalize();
                var probeDistance = (mob.getBbWidth() / 2.0D) + 1.0D;
                if (!level.noBlockCollision(mob, box.move(probeDir.scale(probeDistance))))
                    return true;
                var standingBox = box.move(0.0D, 1.0D, 0.0D);
                return !level.noBlockCollision(mob, standingBox.move(probeDir.scale(probeDistance)));
            }
        }

        return false;
    }

    /**
     * Traces the block position(s) directly ahead of the mob in {@code forward}'s direction (feet, head, and — when the
     * target is below — the downward path) that are actually solid, without filtering by breakability the way
     * {@link #canBreakPathBlock} does. This exists purely to attach real obstruction data to
     * {@link PlanFailureReason#FAILED_BLOCKED} feedback: rather than the planner and {@code BreakToTargetAction}
     * independently re-deriving a guess at what's in the way, they can act on precisely what this trace found. Returns
     * an empty list if nothing solid was found ahead (in which case the caller falls back to the more generic
     * {@link PlanFailureReason#FAILED_STUCK}).
     */
    private List<BlockPos> findForwardBlockingPositions(E mob, LivingEntity target, Vec3 forward) {
        var checkPos = mob.position().add(forward.scale(0.9D));
        var feet = BlockPos.containing(checkPos.x, mob.getBoundingBox().minY, checkPos.z);
        var head = feet.above();
        var targetBelow = target.getY() < mob.getY() - 1.0D;

        var level = mob.level();
        var found = new ArrayList<BlockPos>(3);

        if (targetBelow) {
            var downForward = feet.below();
            if (isSolidObstruction(level, downForward))
                found.add(downForward);
        }
        if (isSolidObstruction(level, feet))
            found.add(feet);
        if (isSolidObstruction(level, head))
            found.add(head);

        return found.isEmpty() ? List.of() : List.copyOf(found);
    }

    private static boolean isSolidObstruction(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Detects the "target sealed inside a structure" case — a villager house being the common example — and, when so,
     * returns the exact wall block to break through, or {@code null} if this isn't that case.
     * <p>
     * Two things keep this from mis-firing the way a naive check would:
     * <ul>
     * <li><b>Only engages on a genuine detour.</b> {@code path} (the current A* / crawl route, if any) is measured
     * against the straight-line distance to the target. A* / crawl are left alone — and free to route through a door or
     * gap — as long as what they find is a reasonably direct route; this only overrides them when the route is empty
     * (no route at all) or a large multiple of straight-line distance (routing around the entire structure, which is
     * what produced the reported "looping").</li>
     * <li><b>Only engages when there's no nearby opening.</b> {@link #findClearRaycastObstruction} samples several rays
     * from around the mob's eye position, not just straight ahead. If any of them is clear, there's a gap (a window, a
     * doorway) close by that pathfinding should be using instead, so this returns {@code null} and defers to normal
     * pathfinding rather than forcing a break next to a walkable opening.</li>
     * </ul>
     * When it does engage, the returned block is the one the raycast actually hit — the true wall between the mob and
     * the target's eyes — rather than a block re-derived from the mob's current facing/movement direction, which is
     * what let this pick an irrelevant corner block when the target sat in a corner nook.
     */
    private BlockPos findEncasingWallBreakTarget(E mob, LivingEntity target, List<BlockPos> path, int fromIndex) {
        var straightDistSqr = mob.distanceToSqr(target);
        if (straightDistSqr > ENCASED_CHECK_MAX_DIST_SQ) {
            return null;
        }

        var isLikelyEncased = path.isEmpty();
        if (!isLikelyEncased && straightDistSqr > MIN_STRAIGHT_DIST_FOR_DETOUR_SQR) {
            var pathLength = computePathLength(path, fromIndex);
            isLikelyEncased = pathLength > Math.sqrt(straightDistSqr) * DETOUR_RATIO_THRESHOLD;
        }

        if (!isLikelyEncased) {
            return null;
        }

        return findClearRaycastObstruction(mob, target);
    }

    /**
     * Sample-based line-of-sight check between the mob and the target's eyes. Rays are cast from the mob's own eye
     * position plus four small lateral offsets, rather than just one straight-ahead ray, so a nearby opening (a window
     * a block to the side, a doorway just off-axis) reads as "there's a gap" and returns {@code null} instead of this
     * method confidently reporting whatever wall block happens to sit on the single central ray.
     *
     * @return the first solid, breakable block hit by every sampled ray, or {@code null} if any sampled ray is clear (a
     *         gap exists nearby) or none of the hits are breakable
     */
    private BlockPos findClearRaycastObstruction(E mob, LivingEntity target) {
        var eyePos = mob.getEyePosition();
        var to = target.getEyePosition();

        var origins = new Vec3[] {
            eyePos,
            eyePos.add(0.3D, 0.0D, 0.0D),
            eyePos.add(-0.3D, 0.0D, 0.0D),
            eyePos.add(0.0D, 0.0D, 0.3D),
            eyePos.add(0.0D, 0.0D, -0.3D)
        };

        BlockPos obstruction = null;

        for (var from : origins) {
            var hit = mob.level()
                .clip(
                    new ClipContext(
                        from,
                        to,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        mob
                    )
                );

            if (hit.getType() == HitResult.Type.MISS) {
                return null;
            }

            if (obstruction == null && hit.getType() == HitResult.Type.BLOCK) {
                var pos = hit.getBlockPos();
                if (canBreakPathBlock(mob, pos)) {
                    obstruction = pos;
                } else if (canBreakPathBlock(mob, pos.above())) {
                    obstruction = pos.above();
                }
            }
        }

        return obstruction;
    }

    private static double computePathLength(List<BlockPos> path, int fromIndex) {
        if (path.size() <= fromIndex + 1) {
            return 0.0D;
        }

        var total = 0.0D;
        for (var i = fromIndex; i < path.size() - 1; i++) {
            total += Math.sqrt(path.get(i).distSqr(path.get(i + 1)));
        }
        return total;
    }

    private boolean tryBreakBlockingPathBlock(E mob, Blackboard blackboard, LivingEntity target, Vec3 forward) {
        var checkPos = mob.position().add(forward.scale(0.9D));
        var feet = BlockPos.containing(checkPos.x, mob.getBoundingBox().minY, checkPos.z);
        var head = feet.above();
        var targetBelow = target.getY() < mob.getY() - 1.0D;

        BlockPos toBreak = null;

        if (targetBelow) {
            var downForward = feet.below();
            if (canBreakPathBlock(mob, downForward)) {
                toBreak = downForward;
            } else {
                var downCurrent = mob.blockPosition().below();
                if (canBreakDownPathBlock(mob, downCurrent)) {
                    toBreak = downCurrent;
                }
            }
        }

        if (toBreak == null && canBreakPathBlock(mob, feet)) {
            toBreak = feet;
        }
        if (toBreak == null && canBreakPathBlock(mob, head)) {
            toBreak = head;
        }

        if (toBreak != null) {
            blackboard.set(AiKeys.BREAK_TO_TARGET_SCAN, toBreak);
            blackboard.set(AiKeys.BREAK_TO_TARGET_TRIGGER, Boolean.TRUE);
            return true;
        }
        return false;
    }

    /**
     * Checks straight up from the mob's head for a breakable obstruction — the counterpart to
     * {@link #tryBreakBlockingPathBlock}'s horizontal-only probe, for a mob wall-crawling nearly straight up toward a
     * target directly overhead. A roof eave overhanging the wall the mob is climbing sits directly above its head, not
     * ahead of it on the horizontal plane, so it's otherwise never found no matter how long the climb stalls there.
     * Reuses {@link #canBreakPathBlock}'s breakability rules and the same {@link AiKeys#BREAK_TO_TARGET_SCAN}/
     * {@link AiKeys#BREAK_TO_TARGET_TRIGGER} hand-off to {@code BreakToTargetAction} that the horizontal path uses.
     */
    private boolean tryBreakOverheadObstacle(E mob, Blackboard blackboard) {
        var head = BlockPos.containing(mob.getX(), mob.getBoundingBox().maxY, mob.getZ());

        for (var dy = 0; dy <= 2; dy++) {
            var check = head.above(dy);
            if (canBreakPathBlock(mob, check)) {
                blackboard.set(AiKeys.BREAK_TO_TARGET_SCAN, check);
                blackboard.set(AiKeys.BREAK_TO_TARGET_TRIGGER, Boolean.TRUE);
                return true;
            }
        }
        return false;
    }

    /**
     * Fallback for when the mob isn't making progress and there's no breakable block ahead — the obstruction is a press
     * of other living entities rather than a wall, which is common when a target is inside a villager house crammed
     * with villagers and iron golems. Vanilla entity-entity separation is too weak to reliably part a dense cluster
     * like that on its own, especially against iron golems' large hitbox, so this explicitly shoves nearby blocking
     * entities out of the mob's path and gives the mob a forward impulse to follow through.
     * <p>
     * Excludes the current {@code target} (shoving it away would be counterproductive) and other alien entities
     * (hive-mates shouldn't be knocked around).
     *
     * @return {@code true} if at least one blocking entity was found and pushed
     */
    private boolean tryPushThroughCrowd(E mob, LivingEntity target, Vec3 forward) {
        if (forward.lengthSqr() < 0.0001D) {
            return false;
        }

        var probeBox = mob.getBoundingBox().inflate(0.2D).move(forward.x * 0.7D, 0.0D, forward.z * 0.7D);

        var blockers = mob.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                probeBox,
                e -> e != mob && e != target && e.isAlive() && !(e instanceof AbstractAlienEntity)
            );

        if (blockers.isEmpty()) {
            return false;
        }

        for (var blocker : blockers) {
            var away = blocker.position().subtract(mob.position());
            var awayHorizontal = new Vec3(away.x, 0.0D, away.z);
            var pushDir = awayHorizontal.lengthSqr() > 0.0001D ? awayHorizontal.normalize() : forward;
            blocker.push(pushDir.x * 0.4D, 0.05D, pushDir.z * 0.4D);
        }

        mob.setDeltaMovement(forward.x * speed * 0.6D, mob.getDeltaMovement().y, forward.z * speed * 0.6D);
        mob.hasImpulse = true;

        return true;
    }

    private boolean canBreakPathBlock(E mob, BlockPos pos) {
        var level = mob.level();
        var state = level.getBlockState(pos);
        if (state.isAir())
            return false;
        if (!state.is(ModTags.WEAK_BLOCKS))
            return false;
        if (state.getDestroySpeed(level, pos) < 0.0F)
            return false;
        if (state.getCollisionShape(level, pos).isEmpty())
            return false;
        var below = pos.below();
        return level.getBlockState(below).getCollisionShape(level, below).isEmpty()
            || pos.getY() >= mob.blockPosition().getY();
    }

    private boolean canBreakDownPathBlock(E mob, BlockPos pos) {
        var level = mob.level();
        var state = level.getBlockState(pos);
        if (state.isAir())
            return false;
        if (!state.is(ModTags.WEAK_BLOCKS))
            return false;
        if (state.getDestroySpeed(level, pos) < 0.0F)
            return false;
        if (state.getCollisionShape(level, pos).isEmpty())
            return false;

        var landingFeet = pos.below();
        var landingGround = landingFeet.below();
        if (!level.getBlockState(landingFeet).getCollisionShape(level, landingFeet).isEmpty())
            return false;
        if (level.getBlockState(landingGround).getCollisionShape(level, landingGround).isEmpty())
            return false;
        return TraversalQueries.isSafeBlock(level, landingFeet, mob) && TraversalQueries.isSafeBlock(
            level,
            landingGround,
            mob
        );
    }

    private void halt(E mob) {
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.hasImpulse = false;
    }

    private void faceTarget(E mob, LivingEntity target) {
        var dx = target.getX() - mob.getX();
        var dz = target.getZ() - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    private void faceMovementDirection(E mob, Vec3 movement) {
        if (movement.horizontalDistanceSqr() < 0.0001D)
            return;
        var yaw = (float) (Math.atan2(movement.z, movement.x) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.getLookControl()
            .setLookAt(
                mob.getX() + movement.x,
                mob.getEyeY() + movement.y,
                mob.getZ() + movement.z
            );
    }

    /**
     * Builds the fallback chain of {@link PhasedPathSession.Phase}s tried for a repath, in order, all sharing one
     * per-tick node budget: for a crawling mob, the primary crawl-aware route, then (if a tunnel entry was found) a
     * route to that entry specifically, then a relaxed-goal-radius crawl route, then a plain ground A* route as a last
     * resort; for a non-crawling mob, just the plain ground A* route. Each phase is lazily constructed — a fallback
     * phase's {@link IncrementalPathSession} isn't built (and doesn't pay for its own initial node) unless the chain
     * actually reaches it.
     */
    private List<PhasedPathSession.Phase> buildPhases(
        E mob,
        BlockPos searchStart,
        BlockPos crawlGoal,
        BlockPos nearbyTunnelEntry,
        LivingEntity target,
        int fluidGoalRadius
    ) {
        var normalAstar = new PhasedPathSession.Phase(
            "NORMAL_ASTAR",
            () -> IncrementalPathSession.normal(
                mob,
                searchStart,
                target.blockPosition(),
                64,
                Math.max(fluidGoalRadius, 1)
            )
        );
        if (!canCrawl) {
            return List.of(
                normalAstar
            );
        }

        List<PhasedPathSession.Phase> phases = new ArrayList<>(4);

        phases.add(
            new PhasedPathSession.Phase(
                "PRIMARY_CRAWL",
                () -> IncrementalPathSession.crawling(mob, searchStart, crawlGoal, 96, fluidGoalRadius, sessionCache)
            )
        );

        if (nearbyTunnelEntry != null && !nearbyTunnelEntry.equals(crawlGoal)) {
            phases.add(
                new PhasedPathSession.Phase(
                    "TUNNEL_ENTRY_CRAWL",
                    () -> IncrementalPathSession.crawling(
                        mob,
                        searchStart,
                        nearbyTunnelEntry,
                        96,
                        fluidGoalRadius,
                        sessionCache
                    )
                )
            );
        }

        phases.add(
            new PhasedPathSession.Phase(
                "RELAXED_CRAWL",
                () -> IncrementalPathSession.crawling(
                    mob,
                    searchStart,
                    crawlGoal,
                    96,
                    Math.max(fluidGoalRadius, 1),
                    sessionCache
                )
            )
        );

        phases.add(
            normalAstar
        );

        return phases;
    }

    /**
     * Runs the exact same fallback chain as {@link #buildPhases} synchronously in one call, for when
     * {@code enableIncrementalPathfinding} is disabled. Uses {@link #nodeCache} (the per-tick live cache) rather than
     * {@link #sessionCache}, matching this action's original pre-incremental-pathfinding behavior exactly.
     */
    private List<BlockPos> findPathSynchronously(
        E mob,
        BlockPos searchStart,
        BlockPos crawlGoal,
        BlockPos nearbyTunnelEntry,
        LivingEntity target,
        int fluidGoalRadius
    ) {
        if (!canCrawl) {
            return AStarPathfinder.INSTANCE.findPath(
                mob,
                searchStart,
                target.blockPosition(),
                64,
                Math.max(fluidGoalRadius, 1)
            );
        }

        var found = CrawlTraversalEvaluator.INSTANCE.findPath(
            mob,
            searchStart,
            crawlGoal,
            96,
            fluidGoalRadius,
            nodeCache
        );

        if (found.isEmpty() && nearbyTunnelEntry != null && !nearbyTunnelEntry.equals(crawlGoal)) {
            found = CrawlTraversalEvaluator.INSTANCE.findPath(
                mob,
                searchStart,
                nearbyTunnelEntry,
                96,
                fluidGoalRadius,
                nodeCache
            );
        }
        if (found.isEmpty()) {
            found = CrawlTraversalEvaluator.INSTANCE.findPath(
                mob,
                searchStart,
                crawlGoal,
                96,
                Math.max(fluidGoalRadius, 1),
                nodeCache
            );
        }
        if (found.isEmpty()) {
            found = AStarPathfinder.INSTANCE.findPath(
                mob,
                searchStart,
                target.blockPosition(),
                64,
                Math.max(fluidGoalRadius, 1)
            );
        }

        return found;
    }

    private BlockPos findBestTunnelBiasedGoal(E mob, LivingEntity target) {
        var level = mob.level();
        var targetPos = target.blockPosition();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (
            var pos : BlockPos.betweenClosed(
                targetPos.offset(-8, -1, -8),
                targetPos.offset(8, 1, 8)
            )
        ) {
            var isTunnelish =
                nodeCache.tunnelCanStandAt(level, mob, pos)
                    || nodeCache.verticalShaftCanCrawlAt(level, mob, pos);

            if (!isTunnelish && !nodeCache.canStandAt(level, mob, pos)) {
                continue;
            }

            if (!isTunnelish && !hasClearPathToTarget(mob, target, pos)) {
                continue;
            }

            var nearTunnel =
                isTunnelish
                    || nodeCache.tunnelCanStandAt(level, mob, pos.north())
                    || nodeCache.tunnelCanStandAt(level, mob, pos.south())
                    || nodeCache.tunnelCanStandAt(level, mob, pos.east())
                    || nodeCache.tunnelCanStandAt(level, mob, pos.west());

            var score =
                pos.distSqr(targetPos)
                    + pos.distSqr(mob.blockPosition()) * 0.01D
                    + (nearTunnel ? -8.0D : 0.0D);

            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }

        return best;
    }

    private boolean hasClearPathToTarget(E mob, LivingEntity target, BlockPos pos) {
        var level = mob.level();

        var from = Vec3.atBottomCenterOf(pos).add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
        var to = target.getEyePosition();

        var hit = level.clip(
            new ClipContext(
                from,
                to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mob
            )
        );

        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * Returns the nearest tunnel entry the mob could use, or {@code null}.
     * <p>
     * Cost tiers, cheapest first: (1) the cached result from a recent scan, revalidated with two checks; (2) the
     * {@link TunnelEntryRegistry} — a few chunk-bucket lookups over known entries; (3) a full expanding-ring world
     * scan, at most once every {@value #TUNNEL_RESCAN_TICKS} ticks. The old implementation ran tier 3 over the entire
     * 65x5x65 volume (~21k positions) every single tick.
     */
    private BlockPos findNearbyTunnelEntry(E mob) {
        var level = mob.level();
        var origin = mob.blockPosition();

        if (tunnelRescanCooldown > 0 && tunnelScanOrigin != null && origin.distSqr(tunnelScanOrigin) <= 16.0D) {
            tunnelRescanCooldown--;

            if (cachedTunnelEntry == null) {
                return null;
            }

            if (
                nodeCache.tunnelCanStandAt(level, mob, cachedTunnelEntry)
                    || nodeCache.verticalShaftCanCrawlAt(level, mob, cachedTunnelEntry)
            ) {
                return cachedTunnelEntry;
            }

            TunnelEntryRegistry.unregister(level, cachedTunnelEntry);
        }

        tunnelScanOrigin = origin;
        tunnelRescanCooldown = TUNNEL_RESCAN_TICKS;

        var known = TunnelEntryRegistry.findNearestValid(
            level,
            mob,
            origin,
            TUNNEL_SCAN_RADIUS,
            TUNNEL_SCAN_MIN_DY,
            TUNNEL_SCAN_MAX_DY,
            nodeCache
        );
        if (known != null) {
            cachedTunnelEntry = known;
            return known;
        }

        cachedTunnelEntry = scanForTunnelEntry(mob, origin);
        return cachedTunnelEntry;
    }

    /**
     * Expanding-square-ring scan for the nearest tunnel entry. The first ring containing a hit holds the nearest entry
     * (to within ring granularity), so the common cases — an entry close by, or open terrain rejected cheaply by the
     * reordered {@code tunnelCanStandAt} — terminate after a small fraction of the full volume. Every hit is registered
     * so future lookups (by this mob or any other) resolve from the registry instead.
     */
    private BlockPos scanForTunnelEntry(E mob, BlockPos origin) {
        var level = mob.level();
        var cursor = new BlockPos.MutableBlockPos();

        for (var r = 0; r <= TUNNEL_SCAN_RADIUS; r++) {
            BlockPos best = null;
            var bestDist = Double.MAX_VALUE;

            for (var dx = -r; dx <= r; dx++) {
                var onXEdge = Math.abs(dx) == r;

                for (var dz = -r; dz <= r; dz++) {
                    if (!onXEdge && Math.abs(dz) != r) {
                        continue;
                    }

                    for (var dy = TUNNEL_SCAN_MIN_DY; dy <= TUNNEL_SCAN_MAX_DY; dy++) {
                        cursor.setWithOffset(origin, dx, dy, dz);

                        if (
                            !nodeCache.tunnelCanStandAt(level, mob, cursor)
                                && !nodeCache.verticalShaftCanCrawlAt(level, mob, cursor)
                        ) {
                            continue;
                        }

                        var hit = cursor.immutable();
                        TunnelEntryRegistry.register(level, hit);

                        var dist = hit.distSqr(origin);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = hit;
                        }
                    }
                }
            }

            if (best != null) {
                return best;
            }
        }

        return null;
    }

    /**
     * A single-block rise onto walkable ground is an ordinary step-up (a hill/slope/stair) that normal ground movement
     * and the stair-jump recovery handle. It must NOT engage the gravity-suppressed wall-climb: with gravity off there
     * is nothing to arrest the forced upward velocity, so on open slopes the mob overshoots, cannot settle, and
     * oscillates/floats in the air. Only rises of 2+ blocks, or nodes with no walkable floor (true wall-cling nodes),
     * count as needing a vertical climb.
     */
    private boolean isOrdinaryStepUp(E mob, BlockPos node, BlockPos mobFeet) {
        return node.getY() - mobFeet.getY() == 1
            && nodeCache.canStandAt(mob.level(), mob, node);
    }

    private boolean needsCrawlStepUp(E mob, BlockPos waypointBlock, BlockPos mobFeet) {
        var currentY = mobFeet.getY();

        if (waypointBlock.getY() > currentY && !isOrdinaryStepUp(mob, waypointBlock, mobFeet)) {
            return true;
        }

        if (path == null || path.isEmpty()) {
            return false;
        }

        var maxLookahead = Math.min(path.size(), pathIndex + 4);

        for (var i = pathIndex; i < maxLookahead; i++) {
            var candidate = path.get(i);

            if (candidate.getY() > currentY && !isOrdinaryStepUp(mob, candidate, mobFeet)) {
                var dx = candidate.getX() + 0.5D - mob.getX();
                var dz = candidate.getZ() + 0.5D - mob.getZ();
                var horizontalDistSqr = dx * dx + dz * dz;

                return horizontalDistSqr < 4.0D;
            }
        }

        return false;
    }

    private BlockPos findUpcomingHigherPathNode(BlockPos mobFeet) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        var currentY = mobFeet.getY();
        var maxLookahead = Math.min(path.size(), pathIndex + 4);

        for (var i = pathIndex; i < maxLookahead; i++) {
            var candidate = path.get(i);

            if (candidate.getY() > currentY) {
                return candidate;
            }
        }

        return null;
    }

    private Vec3 removeBlockedHorizontalComponents(E mob, Vec3 desired) {
        var level = mob.level();
        var box = mob.getBoundingBox();

        var x = desired.x;
        var z = desired.z;

        var probe = Math.max(0.08D, mob.getBbWidth() * 0.25D);

        if (Math.abs(x) > 0.0001D) {
            var xProbe = box.move(Math.copySign(probe, x), 0.0D, 0.0D);
            if (!level.noBlockCollision(mob, xProbe)) {
                x = 0.0D;
            }
        }

        if (Math.abs(z) > 0.0001D) {
            var zProbe = box.move(0.0D, 0.0D, Math.copySign(probe, z));
            if (!level.noBlockCollision(mob, zProbe)) {
                z = 0.0D;
            }
        }

        return new Vec3(x, desired.y, z);
    }

    /**
     * Scans upcoming path nodes for a wall that A* routed over (consecutive nodes with a Y step of 2+). If the bottom
     * block of that wall face is a breakable weak block, sets BREAK_TO_TARGET_SCAN + BREAK_TO_TARGET_TRIGGER so
     * BreakToTargetAction will chip it out on the next tree tick, letting the mob walk through at ground level instead
     * of climbing over.
     * <p>
     * Only called on a fresh repath and only when no break is already pending, so it won't thrash. The 2-block
     * threshold means normal step-ups and hills are never considered.
     */
    private void checkPathForBreakableWall(E mob, Blackboard blackboard, List<BlockPos> path, int fromIndex) {
        if (fromIndex < 0 || fromIndex >= path.size()) {
            return;
        }

        var scanLimit = Math.min(path.size(), fromIndex + 8);
        var baseY = path.get(fromIndex).getY();

        for (var i = fromIndex + 1; i < scanLimit; i++) {
            var node = path.get(i);
            var rise = node.getY() - baseY;

            if (rise < 2) {
                continue;
            }

            for (var wallY = baseY; wallY < node.getY(); wallY++) {
                var candidate = new BlockPos(node.getX(), wallY, node.getZ());
                if (canBreakPathBlock(mob, candidate)) {
                    blackboard.set(AiKeys.BREAK_TO_TARGET_SCAN, candidate);
                    blackboard.set(AiKeys.BREAK_TO_TARGET_TRIGGER, Boolean.TRUE);
                    return;
                }
            }
            return;
        }
    }

    private Vec3 findCornerEscapeStepUpVelocity(E mob, Vec3 desiredHorizontal) {
        var level = mob.level();
        var box = mob.getBoundingBox();

        var desired = new Vec3(desiredHorizontal.x, 0.0D, desiredHorizontal.z);
        if (desired.lengthSqr() < 0.0001D) {
            desired = new Vec3(mob.getLookAngle().x, 0.0D, mob.getLookAngle().z);
        }

        if (desired.lengthSqr() < 0.0001D) {
            return new Vec3(0.0D, speed * 0.85D, 0.0D);
        }

        desired = desired.normalize();

        var candidates = new Vec3[] {
            new Vec3(desired.x, 0.0D, desired.z),
            new Vec3(-desired.z, 0.0D, desired.x),
            new Vec3(desired.z, 0.0D, -desired.x),
            new Vec3(desired.x - desired.z, 0.0D, desired.z + desired.x),
            new Vec3(desired.x + desired.z, 0.0D, desired.z - desired.x)
        };

        Vec3 best = Vec3.ZERO;
        double bestScore = -Double.MAX_VALUE;

        var stepClearY = 1.05D;

        for (var candidate : candidates) {
            if (candidate.lengthSqr() < 0.0001D) {
                continue;
            }

            var dir = candidate.normalize();
            var testMove = dir.scale(speed * 0.55D);
            var probeBox = box.move(testMove.x, stepClearY, testMove.z);

            if (!level.noBlockCollision(mob, probeBox)) {
                continue;
            }

            var score = dir.dot(desired);

            if (score > bestScore) {
                bestScore = score;
                best = testMove;
            }
        }

        return new Vec3(
            best.x,
            Math.max(mob.getDeltaMovement().y, speed * 0.9D),
            best.z
        );
    }
}
