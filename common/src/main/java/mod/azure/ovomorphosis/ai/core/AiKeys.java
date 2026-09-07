package mod.azure.ovomorphosis.ai.core;

import com.azure.azurecortex.api.blackboard.BlackboardKey;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import net.minecraft.core.BlockPos;

import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.roles.XenoRole;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphGoalPlanner;

/**
 * Ovomorphosis-specific blackboard keys — hive/vent/resin/grab mechanics, and the facehugger/xenomorph concepts
 * AzureCortex has no generic notion of.
 * <p>
 * Everything that was generic across any AzureCortex-based agent (target tracking, goal/plan bookkeeping, fire reaction
 * state, dodge/lunge cooldowns, ...) has moved to {@link CommonBlackboardKeys} as part of the AzureCortex extraction —
 * that class now defines the exact same keys this class used to, under the same string names, so no blackboard data is
 * invalidated by the switch. Update call sites from {@code AiKeys.X} to {@code CommonBlackboardKeys.X} for anything not
 * listed below:
 * <ul>
 * <li>{@code TARGET}, {@code GOAL_TARGET}, {@code LAST_SEEN_POS}, {@code LAST_SEEN_VELOCITY}, {@code LAST_SEEN_TICK},
 * {@code LAST_KNOWN_TARGET_POS}, {@code GOAL_DESTINATION}, {@code DESTINATION}</li>
 * <li>{@code ACTIVE_GOAL}, {@code ACTIVE_GOAL_TYPE}, {@code LAST_GOAL_REASON}, {@code GOAL_REPLAN},
 * {@code PASSIVE_DECISION}, {@code FAILED_GOAL_COUNT}, {@code GOAL_FAILURE_COOLDOWNS}, {@code LAST_PLAN_FEEDBACK},
 * {@code LAST_FAILURE_REASON}, {@code PLAN_WORLD_STATE}</li>
 * <li>{@code DODGE_COOLDOWN}, {@code LUNGE_COOLDOWN}, {@code SWIM_STRANDED_SINCE_TICK}</li>
 * <li>{@code FIRE_TOLERANCE}, {@code FIRE_FLEE_COOLDOWN}, {@code LAST_FIRE_POS}, {@code LAST_FIRE_ATTACKER},
 * {@code TARGET_IS_FIRE_USER}, {@code FIRE_DANGER_UNTIL_TICK}</li>
 * <li>{@code TARGET_IS_RANGED}, {@code TARGET_IS_ISOLATED}, {@code TARGET_IS_ARMORED}</li>
 * </ul>
 * Note that most of the keys above changed from raw {@code String} constants to typed {@link BlackboardKey} constants
 * as part of the move — see {@link CommonBlackboardKeys} for the exact types, and update any
 * {@code blackboard.get}/{@code blackboard.set} call using the old string-and-cast form to the typed accessor.
 * <p>
 * Everything remaining below is unchanged: still Ovomorphosis-specific, still declared here.
 */
public final class AiKeys {

    private AiKeys() {}

    // --- Facehugger ---

    /** Cooldown between leap-and-attach attempts. */
    public static final String GRAB_COOLDOWN = "grab_cooldown";

    /**
     * Type: {@link Boolean}. {@code true} when target passes the facehugger host validity test (can be infected).
     */
    public static final BlackboardKey<Boolean> TARGET_IS_VALID_HOST = BlackboardKey.of(
        "target_is_valid_host",
        Boolean.class
    );

    /**
     * Type: {@link Boolean}. {@code true} when the target is a danger entity, ranged and at distance, heavily armored,
     * or on the grab blacklist. Suppresses grab and carry scoring.
     */
    public static final BlackboardKey<Boolean> TARGET_IS_TOO_DANGEROUS_TO_GRAB = BlackboardKey.of(
        "target_is_too_dangerous_to_grab",
        Boolean.class
    );

    // --- Chestburster ---

    /**
     * Food positions the chestburster has decided not to pursue. Type not yet pinned down precisely at the original
     * call sites (likely a position collection) — kept as a raw {@code String} rather than guessed into a typed
     * {@link BlackboardKey}; worth typing properly once its actual value shape is confirmed.
     */
    public static final String IGNORED_FOOD = "burster_ignored_food";

    // --- Xenomorph: hive ---

    /** Cooldown between resin block placements. */
    public static final String RESIN_PLACE_COOLDOWN = "resin_place_cooldown";

    /** Cooldown between carry-to-web actions. */
    public static final String CARRY_COOLDOWN = "carry_cooldown";

    /** Cooldown between light-source destruction scans. */
    public static final String LIGHT_SCAN_COOLDOWN = "light_scan_cooldown";

    // --- Xenomorph: fire reaction ---

    /**
     * Type: {@link BlockPos}. Cached result of the last {@code FleeFireAction.findNearestFire} scan, reused between
     * {@link #FIRE_SCAN_COOLDOWN} windows so the tree's per-tick fire-flee precheck doesn't re-run the full 9x8x9 block
     * scan every single tick for every mob. {@code null} means the last scan found nothing nearby.
     */
    public static final BlackboardKey<BlockPos> FIRE_SCAN_RESULT = BlackboardKey.of(
        "fire_scan_result",
        BlockPos.class
    );

    /**
     * Cooldown between {@code FleeFireAction.findNearestFire} re-scans. Split out from {@link #FIRE_SCAN_RESULT} for
     * the same reason as {@code BREAK_TO_TARGET_SCAN_COOLDOWN}: cooldowns are strictly {@link String}-keyed and can't
     * share a name with a typed blackboard value.
     */
    public static final String FIRE_SCAN_COOLDOWN = "fire_scan_cooldown";

    /**
     * Cooldown between {@code LURE_TARGET} selections. Deliberately long relative to how rarely the goal scores highly
     * in the first place — see {@link XenomorphGoalPlanner}'s lure scoring — so a false-retreat-into-ambush doesn't
     * repeat often enough for a player to recognize it as a scripted mechanic.
     */
    public static final String LURE_COOLDOWN = "lure_cooldown";

    /**
     * Type: {@link BlockPos}. Vent entrance position chosen by the planner for {@link AiGoalType#VENT_TRAVERSAL}.
     */
    public static final BlackboardKey<BlockPos> VENT_ENTRANCE = BlackboardKey.of("vent_entrance", BlockPos.class);

    /**
     * Type: {@link BlockPos}. Vent exit position paired with {@link #VENT_ENTRANCE} for the current
     * {@link AiGoalType#VENT_TRAVERSAL}.
     */
    public static final BlackboardKey<BlockPos> VENT_EXIT = BlackboardKey.of("vent_exit", BlockPos.class);

    /** Cooldown between {@code VENT_TRAVERSAL} selections. */
    public static final String VENT_TRAVERSAL_COOLDOWN = "vent_traversal_cooldown";

    /** Cooldown between {@code HiveMemory#syncVentBlocksNear} scans — see that method's docs for why it's gated. */
    public static final String VENT_SYNC_COOLDOWN = "vent_sync_cooldown";

    public static final String HIVE_SYNC_COOLDOWN = "hive_sync_cooldown";

    /**
     * Type: {@link BlockPos}. Position of a known hive breach the mob is currently en route to repair, set only while
     * {@link AiGoalType#EXPAND_HIVE} actually wins with one pending (see {@link XenomorphGoalPlanner}) and read by the
     * behavior tree's travel-to-breach branch. Deliberately a dedicated key rather than reusing
     * {@link CommonBlackboardKeys#GOAL_DESTINATION} — that key is only ever updated when a goal supplies one, so it
     * would go stale (keep pointing at an already-repaired breach) the moment {@code EXPAND_HIVE} won without a pending
     * breach; this key is explicitly set-or-cleared every time {@code EXPAND_HIVE} wins instead.
     */
    public static final BlackboardKey<BlockPos> HIVE_BREACH_DEST = BlackboardKey.of(
        "hive_breach_dest",
        BlockPos.class
    );

    /** Type: {@link HiveMemory}. Shared hive state read by wander (dark preference) and hive-building actions. */
    public static final BlackboardKey<HiveMemory> HIVE_MEMORY = BlackboardKey.of("hive_memory", HiveMemory.class);

    /**
     * Type: {@link Integer}. Consecutive {@code EXPAND_HIVE} failures due to {@code FAILED_NO_PATH}/
     * {@code FAILED_STUCK}/{@code FAILED_BLOCKED} (i.e. the hive itself — or a breach in it — was physically
     * unreachable, as opposed to just having no candidate blocks nearby). {@link XenomorphGoalPlanner} escalates the
     * goal's suppression window with this streak so a hive that's genuinely out of reach (spawned far away, sealed
     * behind maze geometry the mob can't path through) stops pulling the mob back into the same failed attempt every
     * few seconds forever. Reset to 1 (a fresh streak) whenever {@link #HIVE_STUCK_LAST_FAIL_TICK} is stale enough that
     * the mob evidently hasn't been actively failing this whole time.
     */
    public static final BlackboardKey<Integer> HIVE_STUCK_STREAK = BlackboardKey.of(
        "hive_stuck_streak",
        Integer.class
    );

    /** Type: {@link Integer}. Game tick of the most recent {@link #HIVE_STUCK_STREAK}-incrementing failure. */
    public static final BlackboardKey<Integer> HIVE_STUCK_LAST_FAIL_TICK = BlackboardKey.of(
        "hive_stuck_last_fail_tick",
        Integer.class
    );

    /** Type: {@link Boolean}. {@code true} when target is within 20 blocks of the nearest resin web cross. */
    public static final BlackboardKey<Boolean> TARGET_IS_NEAR_HIVE = BlackboardKey.of(
        "target_is_near_hive",
        Boolean.class
    );

    /**
     * The current soft intent role for this xenomorph. Type: {@link XenoRole}. Updated every planning cycle by
     * {@link XenomorphGoalPlanner}. AzureCortex's {@code RoleSelector} owns the actual assignment bookkeeping (hold
     * duration, reassignment eligibility) — this key just publishes the current result to the blackboard so tree nodes
     * and actions can read it without re-querying the selector every tick, exactly the pattern AzureCortex's own
     * Memory-and-Roles wiki page recommends: AzureCortex reserves no {@code CommonBlackboardKeys} entry for role
     * concepts, since they're inherently mod-specific.
     */
    public static final BlackboardKey<XenoRole> XENO_ROLE = BlackboardKey.of("xeno_role", XenoRole.class);

    // --- Xenomorph: obstacle breaking ---

    /**
     * Type: {@link Boolean}. Set by {@code BreakToTargetAction} when it determines a block needs to be broken to reach
     * the target. Cleared when the break completes or the target changes.
     */
    public static final BlackboardKey<Boolean> BREAK_TO_TARGET_TRIGGER = BlackboardKey.of(
        "break_to_target_trigger",
        Boolean.class
    );

    /**
     * Type: {@link BlockPos}. The specific block {@code BreakToTargetAction} is currently targeting.
     */
    public static final BlackboardKey<BlockPos> BREAK_TO_TARGET_SCAN = BlackboardKey.of(
        "break_to_target_scan",
        BlockPos.class
    );

    /**
     * Cooldown between obstruction re-scans in {@code BreakToTargetAction}. Split out from
     * {@link #BREAK_TO_TARGET_SCAN} — that key now holds a typed {@link BlockPos} value on the blackboard, and
     * {@link com.azure.azurecortex.runtime.CooldownTracker} is strictly {@link String}-keyed, so the same name can no
     * longer serve both roles the way it did back when both were plain strings.
     */
    public static final String BREAK_TO_TARGET_SCAN_COOLDOWN = "break_to_target_scan_cooldown";

    /**
     * Type: {@link Boolean}. Set by {@code BreakToTargetAction} whenever it finishes resolving an attempt entered
     * purely because {@code ACTIVE_GOAL_TYPE == BREAK_OBSTACLE} (as opposed to a fresh, concrete
     * {@link #BREAK_TO_TARGET_TRIGGER}). {@code ACTIVE_GOAL_TYPE} stays {@code BREAK_OBSTACLE} for the planner's whole
     * commit window regardless of whether the obstruction was already cleared, so without this flag the tree would
     * re-enter {@code BreakToTargetAction} on that stale goal type every tick — forever restarting an action with
     * nothing left to do, and never falling through to movement/combat branches. Cleared by the goal-applying code
     * whenever a fresh goal is committed. A genuinely new {@link #BREAK_TO_TARGET_TRIGGER} (set by a movement action
     * detecting a real, current obstruction) always bypasses this flag entirely — it only gates the stale-goal-type
     * fallback path.
     */
    public static final BlackboardKey<Boolean> BREAK_TO_TARGET_EXHAUSTED = BlackboardKey.of(
        "break_to_target_exhausted",
        Boolean.class
    );
}
