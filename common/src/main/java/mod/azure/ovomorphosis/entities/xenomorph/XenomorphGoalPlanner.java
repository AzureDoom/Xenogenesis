package mod.azure.ovomorphosis.entities.xenomorph;

import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.api.goal.GoalUrgency;
import com.azure.azurecortex.goap.*;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

import java.util.Optional;

import mod.azure.ovomorphosis.ai.actions.FleeFireAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.roles.XenoRole;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.TargetClassifier;
import mod.azure.ovomorphosis.data.OvomorphosisSavedData;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * GOAP planner for {@link XenomorphEntity}.
 * <h3>Anti-thrash design</h3> Four mechanisms prevent goal oscillation:
 * <ol>
 * <li><b>Min-commit lock</b> — {@link GoalExecutor#shouldReplan} suppresses the planner until
 * {@link PlannedGoal#canReplan} is true (default 40 ticks). The entity's tick method must call {@code shouldReplan}
 * before invoking this planner.</li>
 * <li><b>Hysteresis bonus</b> — the currently active goal type receives a flat {@link #HYSTERESIS_BONUS} score
 * addition, raising the bar a challenger must clear to displace it. Emergency-tier situations still win because their
 * base scores are much higher than the bonus.</li>
 * <li><b>Per-goal failure cooldowns</b> — {@link GoalFailureCooldowns} tracks which goal types have recently failed and
 * applies a linearly decaying penalty to their scores, persisting well beyond the 80-tick {@link PlanFeedback}
 * freshness window.</li>
 * <li><b>Emergency override tier</b> — goals scored at or above {@link #EMERGENCY_SCORE_THRESHOLD} are tagged
 * {@link GoalUrgency#EMERGENCY}, which causes {@link GoalExecutor#shouldReplan} to bypass the min-commit lock on the
 * caller side.</li>
 * </ol>
 * <h3>Failure recording</h3> When the feedback switch fires for path/stuck/blocked failures the planner now calls
 * {@link GoalFailureCooldowns#recordFailure} in addition to the existing score penalty. This means a goal that fails
 * repeatedly is suppressed for a full {@link GoalFailureCooldowns#DEFAULT_DURATION} (200 ticks) rather than just the
 * 80-tick feedback window.
 */
public final class XenomorphGoalPlanner implements GoalPlanner<XenomorphEntity, AiGoalType> {

    /** Health fraction at/below which survival overrides everything else ("critical" tier). */
    private static final float RETREAT_HEALTH_FRACTION = 0.30f;

    /**
     * Health fraction above which the mob is considered "wounded" rather than critical (between this and
     * {@link #RETREAT_HEALTH_FRACTION} is the contextual-retreat band). Above this fraction the mob is healthy enough
     * to stay aggressive regardless of opponent.
     */
    private static final float WOUNDED_HEALTH_FRACTION = 0.60f;

    /** Max local light level a hive position can have and still count as a viable dark hideout. */
    private static final int DARK_HAVEN_MAX_LIGHT = 4;

    /**
     * How stale {@link CommonBlackboardKeys#LAST_SEEN_TICK} can be before INVESTIGATE gives up on extrapolating an
     * interception point and just walks to the raw last-seen block instead. Beyond this, the target could plausibly be
     * almost anywhere, so guessing a specific heading stops being worth the confidence it implies.
     */
    private static final int MAX_PREDICTION_STALENESS_TICKS = 60;

    /**
     * Minimum horizontal speed (blocks/tick) the target must have had when last seen for INVESTIGATE to bother
     * extrapolating ahead at all. Below this they were essentially standing still, so the last-seen block already is
     * the best guess and a "predicted" point would just be jitter.
     */
    private static final double MIN_PREDICTION_SPEED = 0.02D;

    /** Floor on how far ahead of the last-seen block to search, once extrapolation is worth doing at all. */
    private static final double MIN_PREDICTION_DISTANCE = 2.0D;

    /**
     * Ceiling on how far ahead of the last-seen block to search, regardless of how fast the target was moving or how
     * long they've been out of sight — keeps a lucky sprint-away from sending the mob on a long, low-confidence beeline
     * instead of a search.
     */
    private static final double MAX_PREDICTION_DISTANCE = 8.0D;

    /**
     * Per-eligible-planning-cycle chance of a healthy, actively-pursued mob choosing to feign retreat toward a dark
     * ambush spot instead of continuing to press the fight (see {@link AiGoalType#LURE_TARGET}). Deliberately low —
     * combined with {@link AiKeys#LURE_COOLDOWN} below, this is meant to surface only occasionally across a long fight,
     * not on a predictable schedule.
     */
    private static final float LURE_CHANCE = 0.08f;

    /** Below this squared distance the pursuer is already close enough that fighting beats fleeing toward a lure. */
    private static final double LURE_MIN_PURSUIT_DIST_SQ = 3.0D * 3.0D;

    /** Beyond this squared distance the target isn't credibly "in pursuit" — too far off to be actively chasing. */
    private static final double LURE_MAX_PURSUIT_DIST_SQ = 20.0D * 20.0D;

    /** Minimum ticks between LURE_TARGET selections (30s), set only when it's actually the winning goal. */
    private static final int LURE_COOLDOWN_TICKS = 600;

    /**
     * Multiplier applied to straight-line distance to approximate the ordinary route's real cost for
     * {@link HiveMemory#findVentShortcut} — actual pathfinding winds around obstacles and (for a wall-crawler) through
     * tunnels, so straight-line distance alone understates it. This is a heuristic, not a literal A* node count; see
     * {@code PlaceResinAction}/{@code HiveMemory} docs for why splicing vent edges into the real pathfinder wasn't
     * attempted.
     */
    private static final double ORDINARY_ROUTE_ESTIMATE_MULTIPLIER = 1.6D;

    /** Minimum straight-line distance to the target before a vent shortcut is even worth evaluating. */
    private static final double VENT_MIN_TARGET_DIST = 15.0D;

    /**
     * Radius (blocks) scanned by {@link HiveMemory#syncVentBlocksNear} — see the vent-sync cooldown below.
     * <p>
     * Deliberately wide relative to {@link #VENT_MIN_TARGET_DIST}: a freshly-summoned xenomorph's hive doesn't exist
     * until its very first tick, and gets created at wherever <em>it</em> happens to be standing at that moment (see
     * {@code XenomorphEntity#ensureHiveAssignment}) — not wherever a player may have already built vent infrastructure.
     * A vent placed before that first tick is therefore never linked to any hive at placement time
     * ({@code VentBlock#onPlace} finds no hive yet to register with), so this scan, run around both the mob and its
     * target once it has one, is the only way such a pre-existing vent ever gets discovered at all.
     */
    private static final int VENT_SYNC_RADIUS = 32;

    /**
     * Minimum ticks between {@link HiveMemory#syncVentBlocksNear} scans. That scan is a real block-state scan (not a
     * cheap registry lookup), so it's only run occasionally — mainly as a safety net for vent blocks that predate the
     * placement hook (e.g. hand-placed before this scan existed, or before the mob's hive existed at all — see
     * {@link #VENT_SYNC_RADIUS}); ordinarily a vent block registers itself immediately on placement and there's nothing
     * left for this scan to find.
     */
    private static final int VENT_SYNC_COOLDOWN_TICKS = 150;

    /** Minimum ticks between VENT_TRAVERSAL selections, set only once it's actually the winning goal. */
    private static final int VENT_TRAVERSAL_COOLDOWN_TICKS = 200;

    /**
     * How far a known nest breach can be from the mob and still be worth traveling to repair — wider than most
     * awareness ranges elsewhere in this file since, unlike vent-shortcut opportunities, the mob genuinely needs to
     * travel there rather than only benefit from one it happens to already be near.
     */
    private static final double BREACH_AWARENESS_RANGE = 48.0D;

    /**
     * Score bonus applied to EXPAND_HIVE when a nest breach is known (see {@code HiveMemory#recordBreach}). Pushes a
     * typical idle hiveScore (~10-20) up to a level comparable to (but still below) typical combat/defense scores, so
     * repair work reliably wins over idling/wandering without ever preempting an active fight.
     */
    private static final float BREACH_REPAIR_BONUS = 50f;

    /** Squared distance at which the mob is considered to have "arrived" at its dark hideout. */
    private static final double DARK_HAVEN_ARRIVAL_RANGE_SQR = 6.0 * 6.0;

    private static final float BOOST_BREAK = 55f;

    private static final float BOOST_INVEST = 40f;

    private static final float BOOST_LIGHTS = 50f;

    private static final float BOOST_HIVE = 45f;

    private static final float BOOST_RETREAT = 60f;

    private static final float PENALTY_FAILED = 40f;

    private static final float HYSTERESIS_BONUS = 20f;

    private static final float EMERGENCY_SCORE_THRESHOLD = 85f;

    /**
     * Base suppression window (ticks) for an {@code EXPAND_HIVE} failure caused by the hive (or a known breach)
     * actually being physically unreachable ({@code FAILED_NO_PATH}/{@code FAILED_STUCK}/{@code FAILED_BLOCKED}), at
     * streak 1. Multiplied by {@link AiKeys#HIVE_STUCK_STREAK} for each consecutive such failure — see that key's docs
     * for why a single fixed window isn't enough on its own.
     */
    private static final int HIVE_STUCK_BASE_COOLDOWN_TICKS = 100;

    /** Ceiling on the escalated cooldown from {@link #HIVE_STUCK_BASE_COOLDOWN_TICKS}, regardless of streak length. */
    private static final int HIVE_STUCK_MAX_COOLDOWN_TICKS = 1200;

    /** Ceiling on {@link AiKeys#HIVE_STUCK_STREAK} itself — matches {@link #HIVE_STUCK_MAX_COOLDOWN_TICKS}. */
    private static final int HIVE_STUCK_MAX_STREAK = HIVE_STUCK_MAX_COOLDOWN_TICKS / HIVE_STUCK_BASE_COOLDOWN_TICKS;

    /**
     * If more than this many ticks have passed since {@link AiKeys#HIVE_STUCK_LAST_FAIL_TICK}, the next failure starts
     * a fresh streak instead of extending the old one — the gap means the mob evidently hasn't been stuck in a retry
     * loop this whole time (it either succeeded, or simply hasn't attempted {@code EXPAND_HIVE} in a while).
     */
    private static final int HIVE_STUCK_STREAK_RESET_TICKS = 300;

    @Override
    public PlannedGoal<XenomorphEntity, AiGoalType> chooseGoal(
        XenomorphEntity mob,
        Blackboard blackboard,
        CooldownTracker cooldowns
    ) {
        var tick = (int) mob.level().getGameTime();
        var gfc = GoalFailureCooldowns.getOrCreate(blackboard);
        gfc.evictExpired(tick);

        var feedback = readFeedback(blackboard, tick);
        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        var hasTarget = target != null && target.isAlive();
        var healthFraction = mob.getHealth() / mob.getMaxHealth();
        var criticalHealth = healthFraction <= RETREAT_HEALTH_FRACTION;
        var woundedHealth = !criticalHealth && healthFraction <= WOUNDED_HEALTH_FRACTION;
        var healthyAggressive = healthFraction > WOUNDED_HEALTH_FRACTION;

        var memory = blackboard.get(AiKeys.HIVE_MEMORY);
        if (memory != null) {
            var centerRepaired = memory.recomputeNeedsIfDue(mob.level(), tick);
            if (centerRepaired && mob.level() instanceof ServerLevel serverLevel) {
                OvomorphosisSavedData.markHiveDirty(serverLevel);
            }
        }
        var nearWeb = memory != null
            && memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 20.0D).isPresent();
        var hasWebInRange = memory != null
            && memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 80.0D).isPresent();

        var darkHaven = memory != null
            ? memory.findNearestDarkOwnedWebCross(mob.level(), mob.blockPosition(), 80.0D, DARK_HAVEN_MAX_LIGHT)
            : Optional.<BlockPos>empty();
        var atDarkHaven = darkHaven.isPresent()
            && mob.blockPosition().distSqr(darkHaven.get()) <= DARK_HAVEN_ARRIVAL_RANGE_SQR;

        var ambientLight = mob.level().getMaxLocalRawBrightness(mob.blockPosition());
        var tooBright = ambientLight > 4;

        var nearbyLightBlock = mob.level().getBrightness(LightLayer.BLOCK, mob.blockPosition()) > 4;

        TargetClassifier.classify(mob, blackboard);
        var targetIsRanged = Boolean.TRUE.equals(blackboard.get(CommonBlackboardKeys.TARGET_IS_RANGED));
        var targetIsIsolated = Boolean.TRUE.equals(blackboard.get(CommonBlackboardKeys.TARGET_IS_ISOLATED));
        var targetIsNearHive = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_NEAR_HIVE));
        var targetIsValidHost = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_VALID_HOST));
        var targetTooDangerous = Boolean.TRUE.equals(
            blackboard.get(AiKeys.TARGET_IS_TOO_DANGEROUS_TO_GRAB)
        );

        var huntScore = 0f;
        var ambushScore = 0f;
        var breakScore = 0f;
        var lightsScore = tooBright && nearbyLightBlock ? 35f : 0f;
        var hiveScore = 10f;
        var defendScore = 0f;
        var retreatScore = 0f;
        var investigateScore = 0f;
        var seekDarknessScore = 0f;
        var ambushFromDarknessScore = 0f;
        var wanderScore = 25f;
        var targetFacingMob = false;

        if (hasTarget) {
            var distSq = mob.distanceToSqr(target);
            targetFacingMob = isTargetFacingMob(target, mob);

            huntScore = 70f + (targetFacingMob ? 20f : 0f);

            if (targetIsRanged) {
                huntScore -= 20f;
                ambushScore += 25f;
            }
            if (targetIsIsolated && targetIsValidHost && !targetTooDangerous) {
                hiveScore += 20f;
            }
            if (targetIsNearHive || nearWeb) {
                defendScore = 85f;
                if (memory != null) {
                    memory.recordThreat(target.blockPosition(), tick);
                }
            }
            if (!targetFacingMob && distSq > 8.0 * 8.0) {
                ambushScore += 25f;
                huntScore -= 15f;
            }
            if (distSq <= 12.0 * 12.0) {
                breakScore = 20f;
            }
        }

        if (tooBright && !hasTarget) {
            seekDarknessScore = 30f + ambientLight * 2f;
        }
        if (hasTarget && tooBright) {
            ambushFromDarknessScore = 45f + ambientLight * 2f;
            huntScore -= 20f;
        }

        var opponentIsThreatening = hasTarget
            && (targetTooDangerous || targetIsRanged || target.getType().is(ModTags.DANGER_ENTITIES));
        var opponentIsWeak = hasTarget && targetIsIsolated && !opponentIsThreatening;

        if (healthyAggressive) {
            /* NO-OP */
        } else if (woundedHealth) {
            if (opponentIsThreatening) {
                retreatScore = hasWebInRange ? 55f : 30f;
                seekDarknessScore += ambientLight > 2 ? 20f : 10f;
                huntScore -= 15f;
            } else if (hasTarget && !opponentIsWeak) {
                retreatScore = hasWebInRange ? 20f : 10f;
            }
        } else if (criticalHealth) {
            retreatScore = darkHaven.isPresent() ? 95f : (hasWebInRange ? 80f : 45f);
            seekDarknessScore += 35f + (ambientLight > 2 ? 15f : 0f);
            huntScore = Math.max(0f, huntScore - 40f);
            ambushScore = Math.max(0f, ambushScore - 20f);

            if (atDarkHaven && hasTarget) {
                ambushFromDarknessScore += opponentIsWeak ? 60f : 25f;
            }
        }

        var pursuitDistSq = hasTarget ? mob.distanceToSqr(target) : 0.0;
        var targetIsPursuing = hasTarget
            && targetFacingMob
            && pursuitDistSq >= LURE_MIN_PURSUIT_DIST_SQ
            && pursuitDistSq <= LURE_MAX_PURSUIT_DIST_SQ;

        var lureScore = 0f;
        if (
            healthyAggressive
                && targetIsPursuing
                && darkHaven.isPresent()
                && cooldowns.ready(AiKeys.LURE_COOLDOWN)
                && mob.getRandom().nextFloat() < LURE_CHANCE
        ) {
            lureScore = 65f;
        }
        HiveMemory.VentRoute ventRoute = null;
        if (memory != null && hasTarget && cooldowns.ready(AiKeys.VENT_SYNC_COOLDOWN)) {
            var foundNearMob = memory.syncVentBlocksNear(mob.level(), mob.blockPosition(), VENT_SYNC_RADIUS);
            var foundNearTarget = memory.syncVentBlocksNear(mob.level(), target.blockPosition(), VENT_SYNC_RADIUS);
            if ((foundNearMob || foundNearTarget) && mob.level() instanceof ServerLevel serverLevel) {
                OvomorphosisSavedData.markHiveDirty(serverLevel);
            }
            cooldowns.set(AiKeys.VENT_SYNC_COOLDOWN, VENT_SYNC_COOLDOWN_TICKS);
        }

        if (
            memory != null
                && hasTarget
                && cooldowns.ready(AiKeys.VENT_TRAVERSAL_COOLDOWN)
                && mob.distanceToSqr(target) >= VENT_MIN_TARGET_DIST * VENT_MIN_TARGET_DIST
        ) {
            var ordinaryEstimate = Math.sqrt(mob.distanceToSqr(target)) * ORDINARY_ROUTE_ESTIMATE_MULTIPLIER;
            ventRoute = memory
                .findVentShortcut(mob.level(), mob.blockPosition(), target.blockPosition(), ordinaryEstimate)
                .orElse(null);
        }

        var ventScore = ventRoute != null ? 150f : 0f;

        var lastSeenPos = blackboard.get(CommonBlackboardKeys.LAST_SEEN_POS);
        if (lastSeenPos != null && !hasTarget) {
            investigateScore = 35f;
        }

        if (!hasTarget && !tooBright) {
            hiveScore = 20f;
        }

        if (tooBright && nearbyLightBlock) {
            lightsScore = 20f + ambientLight * 3f;
        }

        if (feedback != null && feedback.isFresh(tick)) {
            var reason = feedback.reason();
            var failedGoal = feedback.failedGoalType();

            switch (reason) {
                case FAILED_NO_PATH, FAILED_STUCK, FAILED_BLOCKED -> {
                    breakScore += BOOST_BREAK;
                    investigateScore += BOOST_INVEST * 0.5f;
                    if (failedGoal == AiGoalType.HUNT_TARGET) {
                        huntScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 160);
                    }
                    if (failedGoal == AiGoalType.AMBUSH_TARGET) {
                        ambushScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.AMBUSH_TARGET, tick, 120);
                    }
                    if (failedGoal == AiGoalType.EXPAND_HIVE) {
                        hiveScore -= PENALTY_FAILED;
                        recordHiveStuckFailure(blackboard, gfc, tick);
                    }
                }
                case FAILED_TARGET_LOST -> {
                    investigateScore += BOOST_INVEST;
                    huntScore -= PENALTY_FAILED * 0.5f;
                    gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 80);
                }
                case FAILED_UNSUITABLE_CONDITIONS -> {
                    lightsScore += BOOST_LIGHTS;
                    if (failedGoal == AiGoalType.EXPAND_HIVE) {
                        hiveScore -= PENALTY_FAILED;
                        wanderScore += 15f;
                        gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 150);
                    } else {
                        huntScore -= PENALTY_FAILED * 0.5f;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 60);
                    }
                }
                case FAILED_NO_VALID_PLACEMENT -> {
                    hiveScore -= PENALTY_FAILED;
                    wanderScore += 25f;
                    gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 60);
                }
                case FAILED_MISSING_INFRASTRUCTURE -> {
                    hiveScore += BOOST_HIVE;
                    gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 120);
                }
                case FAILED_DANGER -> {
                    retreatScore += BOOST_RETREAT;
                    huntScore -= PENALTY_FAILED;
                    ambushScore -= PENALTY_FAILED;
                    gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 200);
                    gfc.recordFailure(AiGoalType.AMBUSH_TARGET, tick, 200);
                }
                case FAILED_COOLDOWN -> {
                    if (failedGoal == AiGoalType.HUNT_TARGET) {
                        huntScore -= PENALTY_FAILED * 0.5f;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 40);
                    }
                    if (failedGoal == AiGoalType.EXPAND_HIVE) {
                        hiveScore -= PENALTY_FAILED * 0.5f;
                        gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 40);
                    }
                }
                case FAILED_PRECONDITION -> {
                    if (failedGoal == AiGoalType.KILL_LIGHTS) {
                        lightsScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.KILL_LIGHTS, tick, 200);
                    }
                    if (failedGoal == AiGoalType.BREAK_OBSTACLE) {
                        breakScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.BREAK_OBSTACLE, tick, 200);
                    }
                }
                case FAILED_OBSTACLE_UNBREAKABLE -> {
                    investigateScore += BOOST_INVEST;
                    ambushScore += 20f;
                    seekDarknessScore += 25f;
                    breakScore -= PENALTY_FAILED;
                    gfc.recordFailure(AiGoalType.BREAK_OBSTACLE, tick, 200);
                    if (failedGoal == AiGoalType.HUNT_TARGET) {
                        huntScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 120);
                    }
                }
                default -> {}
            }
        }

        huntScore -= gfc.getPenalty(AiGoalType.HUNT_TARGET, tick);
        ambushScore -= gfc.getPenalty(AiGoalType.AMBUSH_TARGET, tick);
        breakScore -= gfc.getPenalty(AiGoalType.BREAK_OBSTACLE, tick);
        hiveScore -= gfc.getPenalty(AiGoalType.EXPAND_HIVE, tick);
        lightsScore -= gfc.getPenalty(AiGoalType.KILL_LIGHTS, tick);
        retreatScore -= gfc.getPenalty(AiGoalType.RETREAT_TO_RESIN, tick);
        investigateScore -= gfc.getPenalty(AiGoalType.INVESTIGATE, tick);
        seekDarknessScore -= gfc.getPenalty(AiGoalType.SEEK_DARKNESS, tick);
        ambushFromDarknessScore -= gfc.getPenalty(AiGoalType.AMBUSH_FROM_DARKNESS, tick);
        lureScore -= gfc.getPenalty(AiGoalType.LURE_TARGET, tick);
        ventScore -= gfc.getPenalty(AiGoalType.VENT_TRAVERSAL, tick);

        FleeFireAction.tickFireAttackerMemory(blackboard, tick);

        var fireAttacker = blackboard.get(CommonBlackboardKeys.LAST_FIRE_ATTACKER);
        var fireUserIsTarget = Boolean.TRUE.equals(blackboard.get(CommonBlackboardKeys.TARGET_IS_FIRE_USER));
        var fireDangerActive = FleeFireAction.isFireDangerActive(blackboard, tick);

        if (fireAttacker != null && target != null) {
            var isSame = fireAttacker == target;
            blackboard.set(CommonBlackboardKeys.TARGET_IS_FIRE_USER, isSame);
            fireUserIsTarget = isSame;
        }

        if (fireDangerActive && hasTarget) {
            if (fireUserIsTarget) {
                huntScore -= 25f;
                ambushScore += 35f;
                lightsScore += 15f;
                if (nearWeb) {
                    defendScore = Math.max(0f, defendScore - 20f);
                    retreatScore += 20f;
                }
            } else {
                retreatScore += 15f;
                ambushScore += 10f;
            }
        }

        if (memory != null) {
            if (memory.hasFewHosts()) {
                huntScore += 15f;
                if (hasTarget && targetIsValidHost && !targetTooDangerous) {
                    hiveScore += 15f;
                }
            }

            if (memory.hasHostSurplusWithLittleResin()) {
                hiveScore += 30f;
            }

            if (memory.isHeavilyIlluminated()) {
                lightsScore += 30f;
            }

            if (memory.isUnderSustainedAttack(tick)) {
                defendScore += 25f;
                huntScore += 10f;
            }

            if (memory.isCrowded() && memory.hasRoomToExpand()) {
                hiveScore += 20f;
            }

            switch (memory.nestMaturity()) {
                case HATCHLING, GROWING -> hiveScore += 10f;
                case THRIVING -> defendScore += 10f;
                default -> {}
            }
        }

        var nearestBreach = memory != null
            ? memory.findNearestPendingBreach(mob.level(), mob.blockPosition(), BREACH_AWARENESS_RANGE)
            : Optional.<BlockPos>empty();
        if (nearestBreach.isPresent()) {
            hiveScore += BREACH_REPAIR_BONUS;
        }

        huntScore = Math.max(0f, huntScore);
        ambushScore = Math.max(0f, ambushScore);
        breakScore = Math.max(0f, breakScore);
        lightsScore = Math.max(0f, lightsScore);
        hiveScore = Math.max(0f, hiveScore);
        defendScore = Math.max(0f, defendScore);
        retreatScore = Math.max(0f, retreatScore);
        investigateScore = Math.max(0f, investigateScore);
        seekDarknessScore = Math.max(0f, seekDarknessScore);
        ambushFromDarknessScore = Math.max(0f, ambushFromDarknessScore);
        lureScore = Math.max(0f, lureScore);
        ventScore = Math.max(0f, ventScore);

        var activeGoalType = blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL_TYPE);
        if (activeGoalType != null) {
            if (activeGoalType.equals(AiGoalType.HUNT_TARGET)) {
                huntScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.AMBUSH_TARGET)) {
                ambushScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.BREAK_OBSTACLE)) {
                breakScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.KILL_LIGHTS)) {
                lightsScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.EXPAND_HIVE)) {
                hiveScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.DEFEND_HIVE)) {
                defendScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.RETREAT_TO_RESIN)) {
                retreatScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.INVESTIGATE)) {
                investigateScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.WANDER)) {
                wanderScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.SEEK_DARKNESS)) {
                seekDarknessScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.AMBUSH_FROM_DARKNESS)) {
                ambushFromDarknessScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.LURE_TARGET)) {
                lureScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.VENT_TRAVERSAL)) {
                ventScore += HYSTERESIS_BONUS;
            }
        }

        record Candidate(
            AiGoalType type,
            float score
        ) {}

        var best = new Candidate(AiGoalType.WANDER, wanderScore);
        for (
            var c : new Candidate[] {
                new Candidate(AiGoalType.HUNT_TARGET, huntScore),
                new Candidate(AiGoalType.AMBUSH_TARGET, ambushScore),
                new Candidate(AiGoalType.BREAK_OBSTACLE, breakScore),
                new Candidate(AiGoalType.KILL_LIGHTS, lightsScore),
                new Candidate(AiGoalType.EXPAND_HIVE, hiveScore),
                new Candidate(AiGoalType.DEFEND_HIVE, defendScore),
                new Candidate(AiGoalType.RETREAT_TO_RESIN, retreatScore),
                new Candidate(AiGoalType.INVESTIGATE, investigateScore),
                new Candidate(AiGoalType.SEEK_DARKNESS, seekDarknessScore),
                new Candidate(AiGoalType.AMBUSH_FROM_DARKNESS, ambushFromDarknessScore),
                new Candidate(AiGoalType.LURE_TARGET, lureScore),
                new Candidate(AiGoalType.VENT_TRAVERSAL, ventScore),
            }
        ) {
            if (c.score() > best.score())
                best = c;
        }

        var chosen = best.type();
        var chosenScore = best.score();

        GoalUrgency urgency;
        boolean interruptible;
        String reason;
        LivingEntity chosenTarget = hasTarget ? target : null;
        BlockPos chosenDest = null;

        switch (chosen) {
            case HUNT_TARGET -> {
                urgency = chosenScore >= EMERGENCY_SCORE_THRESHOLD ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
                interruptible = false;
                reason = buildReason("Hunting target", feedback);
            }
            case AMBUSH_TARGET -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Ambushing — target unaware";
            }
            case BREAK_OBSTACLE -> {
                urgency = GoalUrgency.HIGH;
                interruptible = true;
                reason = buildReason("Breaking obstacle to reach target", feedback);
            }
            case KILL_LIGHTS -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Destroying light sources";
                chosenTarget = null;
            }
            case EXPAND_HIVE -> {
                urgency = GoalUrgency.LOW;
                interruptible = true;
                if (nearestBreach.isPresent()) {
                    reason = "Repairing a breach in the nest";
                    blackboard.set(AiKeys.HIVE_BREACH_DEST, nearestBreach.get());
                } else {
                    reason = "Expanding hive";
                    blackboard.remove(AiKeys.HIVE_BREACH_DEST);
                }
                chosenTarget = null;
            }
            case DEFEND_HIVE -> {
                urgency = GoalUrgency.EMERGENCY;
                interruptible = false;
                reason = "Target in hive — defending";
            }
            case RETREAT_TO_RESIN -> {
                urgency = criticalHealth ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
                interruptible = false;
                reason = criticalHealth
                    ? "Critical health — fleeing to known dark hive haven"
                    : "Wounded — retreating from a dangerous opponent";
                chosenTarget = null;
                if (memory != null) {
                    chosenDest = darkHaven.isPresent()
                        ? darkHaven.get()
                        : memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 80.0D).orElse(null);
                }
            }
            case INVESTIGATE -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                chosenDest = lastSeenPos != null
                    ? predictInterceptPosition(mob, blackboard, lastSeenPos, tick)
                    : (feedback != null ? feedback.failurePos() : null);
                reason = buildReason("Investigating last known position", feedback);
                chosenTarget = null;
            }
            case SEEK_DARKNESS -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Exposed or hurt — seeking darkness";
                chosenTarget = null;
            }
            case LURE_TARGET -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Feigning retreat to lure pursuer into a dark ambush";
                chosenDest = darkHaven.orElse(null);
                cooldowns.set(AiKeys.LURE_COOLDOWN, LURE_COOLDOWN_TICKS);
            }
            case VENT_TRAVERSAL -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Taking a known vent shortcut toward the target";
                if (ventRoute != null) {
                    chosenDest = ventRoute.entrance();
                    blackboard.set(AiKeys.VENT_ENTRANCE, ventRoute.entrance());
                    blackboard.set(AiKeys.VENT_EXIT, ventRoute.exit());
                    cooldowns.set(AiKeys.VENT_TRAVERSAL_COOLDOWN, VENT_TRAVERSAL_COOLDOWN_TICKS);
                }
            }
            case AMBUSH_FROM_DARKNESS -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Repositioning to dark ambush position before engaging";
            }
            default -> {
                urgency = GoalUrgency.LOW;
                interruptible = true;
                reason = "Idle wandering";
                chosenTarget = null;
            }
        }

        var role = deriveRole(chosen, mob, blackboard, hasTarget, targetIsNearHive, criticalHealth);
        blackboard.set(AiKeys.XENO_ROLE, role);

        return PlannedGoal.of(
            chosen,
            chosenScore,
            tick,
            40,
            200,
            chosenTarget,
            chosenDest,
            urgency,
            interruptible,
            reason
        );
    }

    @SuppressWarnings("unused")
    private static XenoRole deriveRole(
        AiGoalType chosen,
        XenomorphEntity mob,
        Blackboard blackboard,
        boolean hasTarget,
        boolean targetNearHive,
        boolean lowHealth
    ) {
        if (!mob.getPassengers().isEmpty())
            return XenoRole.CARRIER;
        return switch (chosen) {
            case HUNT_TARGET -> XenoRole.HUNTER;
            case AMBUSH_TARGET, SEEK_DARKNESS,
                AMBUSH_FROM_DARKNESS, LURE_TARGET -> XenoRole.STALKER;
            case VENT_TRAVERSAL -> hasTarget ? XenoRole.HUNTER : XenoRole.STALKER;
            case DEFEND_HIVE -> XenoRole.DEFENDER;
            case EXPAND_HIVE -> XenoRole.HIVE_SPREADER;
            case RETREAT_TO_RESIN -> XenoRole.RETREATER;
            case BREAK_OBSTACLE, INVESTIGATE -> hasTarget ? XenoRole.HUNTER : XenoRole.STALKER;
            default -> XenoRole.IDLE;
        };
    }

    @SuppressWarnings("unchecked")
    private static PlanFeedback<AiGoalType> readFeedback(Blackboard blackboard, int tick) {
        var full = (PlanFeedback<AiGoalType>) blackboard.get(CommonBlackboardKeys.LAST_PLAN_FEEDBACK);
        if (full != null)
            return full;

        var raw = (PlanFailureReason) blackboard.get(CommonBlackboardKeys.LAST_FAILURE_REASON);
        if (raw != null && raw != PlanFailureReason.NONE) {
            var activeGoal = (AiGoalType) blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL_TYPE);
            return PlanFeedback.of(raw, tick, null, activeGoal != null ? activeGoal : AiGoalType.NONE);
        }
        return null;
    }

    /**
     * Records a physically-unreachable-hive {@code EXPAND_HIVE} failure, escalating the goal's suppression window on
     * each consecutive occurrence (see {@link AiKeys#HIVE_STUCK_STREAK}'s docs) instead of always reapplying the same
     * fixed window. Without this, a hive that's genuinely out of reach — spawned far away, sealed behind maze geometry
     * the mob can't path through — got retried on essentially the same short cadence forever: the fixed window decayed
     * to nothing well before anything about the mob's situation had changed, so {@code EXPAND_HIVE} simply won again on
     * the next planning cycle and failed the same way.
     */
    private static void recordHiveStuckFailure(Blackboard blackboard, GoalFailureCooldowns<Object> gfc, int tick) {
        var lastFailTick = blackboard.get(AiKeys.HIVE_STUCK_LAST_FAIL_TICK);
        var priorStreak = blackboard.get(AiKeys.HIVE_STUCK_STREAK);

        var streak = (lastFailTick == null || priorStreak == null || tick
            - lastFailTick > HIVE_STUCK_STREAK_RESET_TICKS)
                ? 1
                : Math.min(HIVE_STUCK_MAX_STREAK, priorStreak + 1);

        blackboard.set(AiKeys.HIVE_STUCK_STREAK, streak);
        blackboard.set(AiKeys.HIVE_STUCK_LAST_FAIL_TICK, tick);

        var duration = Math.min(HIVE_STUCK_MAX_COOLDOWN_TICKS, HIVE_STUCK_BASE_COOLDOWN_TICKS * streak);
        gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, duration);
    }

    private static boolean isTargetFacingMob(LivingEntity target, XenomorphEntity mob) {
        var toMob = mob.position().subtract(target.position()).normalize();
        return target.getLookAngle().dot(toMob) > 0.5D;
    }

    /**
     * Extrapolates a believable search point beyond {@code lastSeenPos} using the target's heading when last seen
     * ({@code lastSeenPos + normalize(lastSeenVelocity) * predictionDistance}), instead of INVESTIGATE only ever
     * walking to the exact last-seen block. This is what lets a mob that loses sight of a target rounding a corner
     * plausibly cut them off further down a hallway rather than beelining for the doorway and only re-acquiring once
     * it's already there.
     * <p>
     * Falls back to the raw {@code lastSeenPos} whenever extrapolating isn't warranted: no recorded velocity, the
     * sighting is stale enough ({@link #MAX_PREDICTION_STALENESS_TICKS}) that the target could plausibly be almost
     * anywhere, the target was essentially stationary when last seen ({@link #MIN_PREDICTION_SPEED}), or the projected
     * point lands somewhere clearly not stand-able (e.g. embedded in the wall around the very corner that broke line of
     * sight).
     */
    private static BlockPos predictInterceptPosition(
        XenomorphEntity mob,
        Blackboard blackboard,
        BlockPos lastSeenPos,
        int tick
    ) {
        var velocity = blackboard.get(CommonBlackboardKeys.LAST_SEEN_VELOCITY);
        var lastSeenTick = blackboard.get(CommonBlackboardKeys.LAST_SEEN_TICK);

        if (velocity == null || lastSeenTick == null)
            return lastSeenPos;

        var elapsed = tick - lastSeenTick;
        if (elapsed < 0 || elapsed > MAX_PREDICTION_STALENESS_TICKS)
            return lastSeenPos;

        var horizSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizSpeed < MIN_PREDICTION_SPEED)
            return lastSeenPos;

        var predictionDistance = Mth.clamp(horizSpeed * elapsed, MIN_PREDICTION_DISTANCE, MAX_PREDICTION_DISTANCE);
        var dirX = velocity.x / horizSpeed;
        var dirZ = velocity.z / horizSpeed;

        var predicted = new BlockPos(
            Mth.floor(lastSeenPos.getX() + dirX * predictionDistance),
            lastSeenPos.getY(),
            Mth.floor(lastSeenPos.getZ() + dirZ * predictionDistance)
        );

        return isStandable(mob.level(), predicted) ? predicted : lastSeenPos;
    }

    /** {@code true} if {@code pos} is open space with solid footing beneath it — a plausible place to search. */
    private static boolean isStandable(Level level, BlockPos pos) {
        var below = pos.below();
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    private static String buildReason(String base, PlanFeedback<AiGoalType> feedback) {
        if (feedback == null || feedback.isNone())
            return base;
        return base + " [after " + feedback.reason().name() + "]";
    }
}
