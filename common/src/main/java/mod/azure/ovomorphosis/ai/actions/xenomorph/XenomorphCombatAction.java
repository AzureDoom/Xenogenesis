package mod.azure.ovomorphosis.ai.actions.xenomorph;

import com.azure.azurecortex.action.combat.MeleeHitResolver;
import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.navigation.crawl.CrawlController;
import com.azure.azurecortex.navigation.movement.MovementController;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

public final class XenomorphCombatAction<E extends Mob, G> implements Action<E, G> {

    private enum Phase {
        STALK,
        STRIKE,
        CIRCLE_OUT,
        THREAT_RESPONSE
    }

    private final String cooldownKey;

    private final int cooldownTicks;

    private final int priority;

    private final Consumer<E> strikeAnimation;

    private Phase phase = Phase.STALK;

    private int phaseAge = 0;

    private boolean didStrike = false;

    private boolean wasCrawlingOnStart = false;

    private int stalkLateralBias = 1;

    private int circleDir = 1;

    private final int[] stalkSteerBias = { 0 };

    /**
     * Consecutive times this action has bailed into {@link Phase#THREAT_RESPONSE} without landing a strike in between.
     * THREAT_RESPONSE never transitions back into STRIKE on its own — it just circles and then ends the action (success
     * or failure), handing control back to the tree. Since a player actively fighting the mob is, by definition, almost
     * always looking at it, {@code isTargetFacingMob} was true on effectively every re-engagement, so the action would
     * bail into THREAT_RESPONSE again on every restart — an infinite loop that never reaches STRIKE. Deliberately NOT
     * reset in {@link #start}, since it needs to persist across the multiple start/stop cycles a real encounter goes
     * through; only a landed hit resets it.
     */
    private int threatResponseStreak = 0;

    private static final int MAX_CONSECUTIVE_THREAT_RESPONSES = 1;

    public XenomorphCombatAction(
        String cooldownKey,
        int cooldownTicks,
        int priority,
        Consumer<E> strikeAnimation
    ) {
        this.cooldownKey = cooldownKey;
        this.cooldownTicks = cooldownTicks;
        this.priority = priority;
        this.strikeAnimation = strikeAnimation;
    }

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 1);
        phase = Phase.STALK;
        phaseAge = 0;
        didStrike = false;
        stalkLateralBias = mob.getRandom().nextBoolean() ? 1 : -1;
        stalkSteerBias[0] = 0;
        wasCrawlingOnStart = CrawlController.wasRecentlyWallCrawling(mob);
        mob.hasImpulse = true;

        if (wasCrawlingOnStart) {
            CrawlController.setWallCrawling(mob, true);
            CrawlController.updateWallCrawlingPhysics(mob);
        }
    }

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionOutcome.failed();

        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        if (target == null || !target.isAlive())
            return ActionOutcome.failed(PlanFailureReason.FAILED_TARGET_LOST);

        maintainCrawl(mob);
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        phaseAge++;

        return switch (phase) {
            case STALK -> tickStalk(mob, target);
            case STRIKE -> tickStrike(mob, target, blackboard, cooldowns);
            case CIRCLE_OUT -> tickCircleOut(mob, target, cooldowns, false);
            case THREAT_RESPONSE -> tickThreatResponse(mob, target, cooldowns);
        };
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
        wasCrawlingOnStart = false;
        mob.setAggressive(false);
    }

    @Override
    public boolean isInterruptible() {
        return false;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public String debugName() {
        return cooldownKey;
    }

    private ActionOutcome<G> tickStalk(E mob, LivingEntity target) {
        var distSq = mob.distanceToSqr(target);

        if (
            distSq <= 7D * 7D && isTargetFacingMob(target, mob)
                && threatResponseStreak < MAX_CONSECUTIVE_THREAT_RESPONSES
        ) {
            threatResponseStreak++;
            enterPhase(mob, Phase.THREAT_RESPONSE);
            return ActionOutcome.running();
        }

        if (distSq <= 5D * 5D) {
            enterPhase(mob, Phase.STRIKE);
            return ActionOutcome.running();
        }

        if (phaseAge > 140) {
            enterPhase(mob, Phase.STRIKE);
            return ActionOutcome.running();
        }

        if (phaseAge % 14 == 0 && mob.getRandom().nextFloat() < 0.3F) {
            stalkLateralBias = -stalkLateralBias;
        }

        var toTarget = target.position().subtract(mob.position()).normalize();
        var lateral = new Vec3(-toTarget.z, 0, toTarget.x).scale(0.22D * stalkLateralBias);
        var desired = toTarget.scale(0.58D).add(lateral);
        applyDangerSteering(mob, desired);

        return ActionOutcome.running();
    }

    private ActionOutcome<G> tickStrike(
        E mob,
        LivingEntity target,
        Blackboard blackboard,
        CooldownTracker cooldowns
    ) {
        if (phaseAge == 1) {
            mob.setAggressive(true);
            strikeAnimation.accept(mob);
        }

        if (!didStrike && phaseAge == 5) {
            if (MeleeHitResolver.tryStrike(mob, target, 2.8D)) {
                didStrike = true;
                threatResponseStreak = 0;

                if (!target.isAlive()) {
                    blackboard.set(CommonBlackboardKeys.TARGET, null);
                    mob.setTarget(null);
                    cooldowns.set(cooldownKey, cooldownTicks);
                    mob.setAggressive(false);
                    return ActionOutcome.success();
                }
            }
        }

        if (phaseAge >= 10) {
            circleDir = mob.getRandom().nextBoolean() ? 1 : -1;
            enterPhase(mob, Phase.CIRCLE_OUT);
        }

        return ActionOutcome.running();
    }

    private ActionOutcome<G> tickCircleOut(
        E mob,
        LivingEntity target,
        CooldownTracker cooldowns,
        boolean isThreatResponse
    ) {
        mob.setAggressive(false);

        var toTarget = target.position().subtract(mob.position());
        var dist = toTarget.length();

        var lateral = new Vec3(-toTarget.z, 0, toTarget.x).normalize().scale(0.48D * circleDir);

        var radialError = dist - 5D;
        Vec3 movement;
        if (Math.abs(radialError) > 1.2D) {
            var radialDir = radialError < 0
                ? toTarget.normalize().scale(-1)
                : toTarget.normalize();
            var correction = radialDir.scale(Math.min(Math.abs(radialError) * 0.08D, 0.48D * 0.5D));
            movement = lateral.add(correction).normalize().scale(0.48D);
        } else {
            movement = lateral;
        }

        if (phaseAge % 20 == 0 && mob.getRandom().nextFloat() < 0.2F) {
            circleDir = -circleDir;
        }

        var safe = MovementController.findSafeMovement(mob, movement, new int[] { 0 });
        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;

        int duration = isThreatResponse ? 24 : 14;
        if (phaseAge >= duration) {
            if (isThreatResponse && mob.getRandom().nextFloat() < 0.30F) {
                cooldowns.set(cooldownKey, cooldownTicks + 40);
                return ActionOutcome.failed(PlanFailureReason.FAILED_DANGER);
            }
            stalkLateralBias = circleDir;
            cooldowns.set(cooldownKey, cooldownTicks);
            return ActionOutcome.success();
        }

        return ActionOutcome.running();
    }

    private ActionOutcome<G> tickThreatResponse(E mob, LivingEntity target, CooldownTracker cooldowns) {
        if (phaseAge == 1) {
            mob.setAggressive(false);
            circleDir = mob.getRandom().nextBoolean() ? 1 : -1;
        }
        return tickCircleOut(mob, target, cooldowns, true);
    }

    private void enterPhase(E mob, Phase next) {
        phase = next;
        phaseAge = 0;
        mob.hasImpulse = true;
    }

    private void maintainCrawl(E mob) {
        if (wasCrawlingOnStart) {
            CrawlController.setWallCrawling(mob, true);
            CrawlController.updateWallCrawlingPhysics(mob);
        }
    }

    private static boolean isTargetFacingMob(LivingEntity target, Mob mob) {
        var toMob = mob.position().subtract(target.position()).normalize();
        return target.getLookAngle().dot(toMob) > 0.5D;
    }

    /**
     * Applies {@code desired} (the STALK phase's lateral-circling approach vector) with obstacle steering, the same way
     * {@link #tickCircleOut} already steers its own movement — {@code desired} previously reached
     * {@link Mob#setDeltaMovement} completely unclamped whenever no dangerous entity was nearby to steer away from,
     * which is the common case. STALK's lateral bias has no notion of walls, so in a corridor it would happily aim the
     * mob straight into one; without ever routing that vector through {@link MovementController#findSafeMovement} (as
     * every other movement-producing phase here does), nothing ever corrected it, and the mob would shove against the
     * wall until {@code phaseAge} eventually forced a phase change.
     */
    private void applyDangerSteering(E mob, Vec3 desired) {
        var danger = MovementController.steerAwayFromDangerEntities(mob, Vec3.ZERO);
        Vec3 candidate;
        if (danger.lengthSqr() > 0.0001D) {
            candidate = danger;
        } else {
            candidate = desired;
        }
        var safe = MovementController.findSafeMovement(mob, candidate, stalkSteerBias);
        var result = safe.equals(Vec3.ZERO) ? candidate : safe;
        mob.setDeltaMovement(result.x, mob.getDeltaMovement().y, result.z);
        mob.hasImpulse = true;
    }
}
