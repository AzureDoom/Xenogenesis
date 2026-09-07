package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import mod.azure.ovomorphosis.ai.actions.xenomorph.PlaceResinAction;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.infection.EggmorphTracker;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Per-mob cache of known {@code RESIN_WEB_CROSS} positions.
 */
public final class HiveMemory {

    private UUID hiveId = UUID.randomUUID();

    private static final int MAX_ENTRIES = 256;

    private static final int MISS_THRESHOLD = 8;

    private static final String NBT_KEY = "pos";

    private final Deque<BlockPos> ownedWebCrosses =
        new ArrayDeque<>();

    public UUID getHiveId() {
        return hiveId;
    }

    private int missCount = 0;

    /**
     * Center of the shared hive dome. Claimed once, by whichever xenomorph first attempts to expand the hive with no
     * existing structure recorded; every subsequent {@link PlaceResinAction} activation — by any xenomorph — builds
     * toward this same dome/tunnel network rather than each mob starting its own.
     */
    private BlockPos domeCenter = null;

    /**
     * Running count of dome-shell blocks successfully placed, used as a cheap completion proxy (see
     * {@link PlaceResinAction}).
     */
    private int domeFillCount = 0;

    /** Once {@code true}, {@link PlaceResinAction} switches from building the dome shell to extending tunnels. */
    private boolean domeComplete = false;

    /**
     * Live count of resin structure blocks (per {@link ModTags#RESIN} — dome shell, tunnels, web crosses; vents are
     * tracked separately via {@link #ventNodes}) currently standing anywhere near this hive. Incremented by
     * {@code AbstractResinBlock#onPlace} and decremented by {@code AbstractResinBlock#onRemove} for every such block
     * attributed to this hive, regardless of who placed or broke it. Never allowed below zero.
     */
    private int structureBlockCount = 0;

    /**
     * Once {@code true}, this hive has had at least one resin structure block or vent block registered to it at some
     * point. Guards {@link #isFullyDestroyed()} so a brand-new hive that simply hasn't built anything yet isn't
     * mistaken for one that was built up and then torn back down to nothing.
     */
    private boolean everHadStructure = false;

    /** Tunnels currently being carved outward from the dome. Persisted so tunnel state survives a chunk/mob reload. */
    private final List<TunnelState> activeTunnels = new ArrayList<>();

    /**
     * Mutable cursor for a single in-progress tunnel: where its tip currently is, which direction it's heading, and how
     * many more steps it has before it terminates naturally.
     */
    public static final class TunnelState {

        private BlockPos tip;

        private double dirX;

        private double dirY;

        private double dirZ;

        private int remainingSteps;

        public TunnelState(BlockPos tip, double dirX, double dirY, double dirZ, int remainingSteps) {
            this.tip = tip.immutable();
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            this.remainingSteps = remainingSteps;
        }

        public BlockPos tip() {
            return tip;
        }

        public double dirX() {
            return dirX;
        }

        public double dirY() {
            return dirY;
        }

        public double dirZ() {
            return dirZ;
        }

        public int remainingSteps() {
            return remainingSteps;
        }

        /** Advances the cursor to a new tip/direction and consumes one step of its remaining budget. */
        public void advance(BlockPos newTip, double newDirX, double newDirY, double newDirZ) {
            this.tip = newTip.immutable();
            this.dirX = newDirX;
            this.dirY = newDirY;
            this.dirZ = newDirZ;
            this.remainingSteps--;
        }

        public boolean isExhausted() {
            return remainingSteps <= 0;
        }
    }

    /** Safety cap mirroring {@link #MAX_ENTRIES}; a hive's vent network is expected to stay well under this. */
    private static final int MAX_VENT_ENTRIES = 128;

    /**
     * Transitive linking radius: two vent blocks within this many blocks of each other (directly, or through a chain of
     * other vent blocks each within range of the next) are considered part of the same vent network, and are therefore
     * valid entrance/exit pairs for each other. Deliberately generous relative to block-to-block adjacency since vent
     * blocks placed during hive construction (see {@code PlaceResinAction}) won't always end up literally touching.
     */
    private static final double VENT_LINK_RADIUS = 6.0D;

    /**
     * Flat cost added to a vent route's distance estimate, representing the time/risk of the crawl-through itself. This
     * is what the "10 + ventTraversalCost + 12 vs. 80" comparison in {@link #findVentShortcut} actually is — everything
     * else is straight-line distance. Kept modest rather than representing the literal traversal-tick duration
     * ({@code VentTraversalAction}'s own timing is separate) — a cost too close to that duration would make vents
     * mathematically unable to ever win at the shorter distances they're most useful for.
     */
    private static final double VENT_TRAVERSAL_COST = 20.0D;

    /**
     * A vent route qualifies once its total is under {@code ordinaryEstimate * VENT_SHORTCUT_MARGIN}. Set above 1.0
     * deliberately: {@code ordinaryEstimate} is only a straight-line-distance proxy for the real walked/crawled path
     * (see {@code XenomorphGoalPlanner#ORDINARY_ROUTE_ESTIMATE_MULTIPLIER}), which is itself an underestimate of the
     * real route around obstacles — so a vent route that comes out slightly *more* than the straight-line estimate is
     * still very plausibly a real shortcut, not just noise.
     */
    private static final double VENT_SHORTCUT_MARGIN = 1.2D;

    private final Map<BlockPos, HiveVentNode> ventNodes = new LinkedHashMap<>();

    /**
     * A found vent shortcut: enter at {@code entrance}, emerge at {@code exit}. Both are guaranteed to be in the same
     * linked network (see {@link #relinkVentCluster}), and were valid, unblocked, live vent blocks at query time.
     */
    public record VentRoute(
        BlockPos entrance,
        BlockPos exit
    ) {}

    /**
     * Records {@code pos} as a vent block belonging to this hive. Safe to call redundantly. Triggers
     * {@link #relinkVentCluster} so the new block (and anything it bridges together) is immediately reflected in
     * {@link #findVentShortcut} results.
     *
     * @param pos the vent block's position (stored as an immutable copy)
     */
    public void registerVentBlock(BlockPos pos) {
        var immutable = pos.immutable();
        if (ventNodes.containsKey(immutable) || ventNodes.size() >= MAX_VENT_ENTRIES)
            return;

        ventNodes.put(immutable, new HiveVentNode(immutable, List.of(), 0L, false));
        everHadStructure = true;
        relinkVentCluster(immutable);
    }

    /**
     * Removes {@code pos} from this hive's known vent blocks (called when a vent block is broken), then re-links the
     * remainder so no stale reference to it lingers in another node's {@link HiveVentNode#linkedExits()}.
     */
    public void unregisterVentBlock(BlockPos pos) {
        if (ventNodes.remove(pos.immutable()) != null) {
            relinkAllVentClusters();
        }
    }

    /**
     * Scans a bounded box around {@code origin} for {@link ModTags#VENT_BLOCKS}-tagged positions not yet known to this
     * hive, and registers any found. This is a safety net for vent blocks that predate/bypass the normal placement hook
     * (e.g. hand-placed before this scan existed, or placed by worldgen/a structure) — ordinarily, placing a tagged
     * vent block registers it immediately via its block class, and this never has anything to find.
     * <p>
     * Bounded to a modest box (not a full sphere) and meant to be called sparingly (gate behind a cooldown) since it is
     * a real block-state scan, not a cheap registry lookup like {@link #findNearestOwnedWebCross}.
     * <p>
     * Callers must mark the owning {@code OvomorphosisSavedData} dirty (e.g.
     * {@code OvomorphosisSavedData.markHiveDirty}) when this returns {@code true} — mutating this object in place
     * doesn't do that on its own, and a discovered vent that's never flushed to disk will appear to work for the rest
     * of the session but silently vanish on reload.
     *
     * @param level  the level to scan
     * @param origin center of the scan box
     * @param radius horizontal radius of the scan box, in blocks; the vertical range is a fixed, smaller band
     * @return {@code true} if at least one new vent block was found and registered
     */
    public boolean syncVentBlocksNear(Level level, BlockPos origin, int radius) {
        var mutable = new BlockPos.MutableBlockPos();
        var verticalRadius = Math.min(radius, 6);
        var foundAny = false;

        for (var x = -radius; x <= radius; x++) {
            for (var z = -radius; z <= radius; z++) {
                for (var y = -verticalRadius; y <= verticalRadius; y++) {
                    mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);

                    if (ventNodes.containsKey(mutable))
                        continue;

                    if (level.getBlockState(mutable).is(ModTags.VENT_BLOCKS)) {
                        registerVentBlock(mutable);
                        foundAny = true;
                    }
                }
            }
        }

        return foundAny;
    }

    /**
     * Recomputes {@link HiveVentNode#linkedExits()} for every vent block transitively within {@link #VENT_LINK_RADIUS}
     * of {@code seed} (i.e. the whole connected network {@code seed} belongs to), so each node's linked-exits list
     * stays an accurate cache rather than going stale as more vent blocks register nearby over time.
     */
    private void relinkVentCluster(BlockPos seed) {
        var radiusSq = VENT_LINK_RADIUS * VENT_LINK_RADIUS;

        var component = createComponent(seed, radiusSq);

        for (var pos : component) {
            var others = new ArrayList<BlockPos>(component.size() - 1);
            for (var other : component) {
                if (!other.equals(pos))
                    others.add(other);
            }

            var existing = ventNodes.get(pos);
            if (existing != null) {
                ventNodes.put(pos, existing.withLinkedExits(others));
            }
        }
    }

    private @NotNull ArrayList<BlockPos> createComponent(BlockPos seed, double radiusSq) {
        var component = new ArrayList<BlockPos>();
        var visited = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(seed);
        visited.add(seed);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            component.add(current);

            for (var candidate : ventNodes.keySet()) {
                if (visited.contains(candidate))
                    continue;
                if (current.distSqr(candidate) <= radiusSq) {
                    visited.add(candidate);
                    queue.add(candidate);
                }
            }
        }
        return component;
    }

    /** Re-links every currently-known vent network from scratch. Used after a bulk change such as eviction/load. */
    private void relinkAllVentClusters() {
        var pending = new HashSet<>(ventNodes.keySet());

        while (!pending.isEmpty()) {
            var seed = pending.iterator().next();
            relinkVentCluster(seed);

            var node = ventNodes.get(seed);
            if (node != null) {
                node.linkedExits().forEach(pending::remove);
            }
            pending.remove(seed);
        }
    }

    /**
     * Removes vent entries whose live block no longer carries {@link ModTags#VENT_BLOCKS} (broken, replaced, etc.),
     * then re-links the remaining networks so stale entries can't linger in some other node's
     * {@link HiveVentNode#linkedExits()} list.
     */
    public void evictStaleVentBlocks(Level level) {
        var removedAny = ventNodes.keySet().removeIf(pos -> !level.getBlockState(pos).is(ModTags.VENT_BLOCKS));
        if (removedAny) {
            relinkAllVentClusters();
        }
    }

    /** Marks {@code pos} as used at {@code tick} — currently informational only (surfaced for future tuning/debug). */
    public void markVentUsed(BlockPos pos, long tick) {
        var node = ventNodes.get(pos);
        if (node != null) {
            ventNodes.put(pos, node.withLastUsedTick(tick));
        }
    }

    /**
     * Marks {@code pos} as temporarily unusable (or usable again) — set by {@code VentTraversalAction} while a player
     * has line of sight on this position, so no mob (this one or another) tries to use a watched vent and make the
     * traversal look like obvious teleportation.
     */
    public void setVentBlocked(BlockPos pos, boolean blocked) {
        var node = ventNodes.get(pos);
        if (node != null) {
            ventNodes.put(pos, node.withBlocked(blocked));
        }
    }

    /**
     * Finds the cheapest known vent shortcut from {@code from} to {@code to}, if any beats {@code ordinaryEstimate} (an
     * approximate cost of the normal route, supplied by the caller — see {@code XenomorphGoalPlanner}) by at least
     * {@link #VENT_SHORTCUT_MARGIN}. Only considers vent blocks this hive itself placed/registered, which is what keeps
     * vent travel a territory perk of a mature hive rather than a global shortcut network — an isolated xenomorph with
     * no built-up vent network simply never has a candidate here.
     *
     * @param level            the level to validate live vent blocks against
     * @param from             the mob's current position
     * @param to               the mob's destination (typically the target's position)
     * @param ordinaryEstimate an approximate cost of the ordinary route, in the same units as distance (blocks)
     * @return the cheapest qualifying route, or empty if none beats the ordinary route by enough to be worth it
     */
    public Optional<VentRoute> findVentShortcut(Level level, BlockPos from, BlockPos to, double ordinaryEstimate) {
        if (ventNodes.isEmpty())
            return Optional.empty();

        BlockPos bestEntrance = null;
        BlockPos bestExit = null;
        var bestTotal = Double.MAX_VALUE;

        for (var entry : ventNodes.entrySet()) {
            var entrance = entry.getKey();
            var node = entry.getValue();

            if (node.blocked() || node.linkedExits().isEmpty())
                continue;
            if (!level.getBlockState(entrance).is(ModTags.VENT_BLOCKS))
                continue;

            var distToEntrance = Math.sqrt(from.distSqr(entrance));
            if (distToEntrance >= bestTotal)
                continue;

            for (var exit : node.linkedExits()) {
                var exitNode = ventNodes.get(exit);
                if (exitNode == null || exitNode.blocked())
                    continue;
                if (!level.getBlockState(exit).is(ModTags.VENT_BLOCKS))
                    continue;

                var total = distToEntrance + VENT_TRAVERSAL_COST + Math.sqrt(exit.distSqr(to));
                if (total < bestTotal) {
                    bestTotal = total;
                    bestEntrance = entrance;
                    bestExit = exit;
                }
            }
        }

        if (bestEntrance == null || bestTotal >= ordinaryEstimate * VENT_SHORTCUT_MARGIN)
            return Optional.empty();

        return Optional.of(new VentRoute(bestEntrance, bestExit));
    }

    /** Vent positions known to this hive within {@code maxRange} of {@code origin} — used to place ambient rattle. */
    public Collection<BlockPos> getVentPositionsNear(BlockPos origin, double maxRange) {
        var maxRangeSq = maxRange * maxRange;
        List<BlockPos> result = new ArrayList<>();
        for (var pos : ventNodes.keySet()) {
            if (origin.distSqr(pos) <= maxRangeSq) {
                result.add(pos);
            }
        }
        return result;
    }

    /**
     * Records a single cross-block position. Oldest entry is evicted when the deque is full.
     *
     * @param pos the position to remember (stored as an immutable copy)
     */
    public void trackOwnedWebCross(BlockPos pos) {
        if (ownedWebCrosses.contains(pos))
            return;

        if (ownedWebCrosses.size() >= MAX_ENTRIES) {
            ownedWebCrosses.pollFirst();
        }

        ownedWebCrosses.addLast(pos.immutable());
    }

    /**
     * Returns the shared dome center, if one has been claimed yet.
     *
     * @return the dome center, or empty if no xenomorph has started building yet
     */
    public Optional<BlockPos> getDomeCenter() {
        return Optional.ofNullable(domeCenter);
    }

    /**
     * Claims {@code pos} as the shared dome center. A no-op if a center is already claimed — first claim wins, so all
     * xenomorphs converge on one structure rather than each mob starting its own dome wherever it happens to be.
     *
     * @param pos the candidate center (typically the claiming mob's current position)
     */
    public void claimDomeCenter(BlockPos pos) {
        if (domeCenter == null) {
            domeCenter = pos.immutable();
        }
    }

    /**
     * Search radius (in blocks, horizontal axes) {@link #resolveOpenCenter} expands outward through when
     * {@code preferred} itself doesn't qualify as a usable dome center.
     */
    private static final int CENTER_SEARCH_RADIUS = 12;

    /** Vertical half-range {@link #resolveOpenCenter} searches, applied both above and below {@code preferred}. */
    private static final int CENTER_SEARCH_VERTICAL_RADIUS = 6;

    /**
     * Vertical clearance, in blocks starting at the candidate position itself, that must all be diggable/replaceable
     * for a position to count as open enough to serve as a dome center. This is what "the center of the dome area"
     * actually needs to mean in practice: not merely a coordinate, but a spot with room to stand in and be reached,
     * rather than one that happens to land inside solid rock or bedrock.
     */
    private static final int CENTER_HEADROOM = 3;

    /**
     * Finds the best available dome-center position at or near {@code preferred}: somewhere with enough open headroom
     * to actually be reached and built around, instead of embedded in solid/unbreakable material. {@code preferred}
     * (typically the claiming mob's own position) is checked first and returned as-is if it already qualifies, since
     * that's the common case; otherwise this searches outward in expanding cubes up to {@link #CENTER_SEARCH_RADIUS}
     * for the closest qualifying position. If nothing in range qualifies (e.g. the whole area is solid bedrock), falls
     * back to {@code preferred} itself rather than failing outright — {@link #ensureCenterClear} still guarantees that
     * fallback isn't left solid.
     *
     * @param level     the level to check block state against
     * @param preferred the candidate center to prefer
     * @return the chosen center, as an immutable position
     */
    public static BlockPos resolveOpenCenter(Level level, BlockPos preferred) {
        var immutablePreferred = preferred.immutable();

        if (isOpenEnoughForCenter(level, immutablePreferred))
            return immutablePreferred;

        BlockPos best = null;
        var bestDistSq = Double.MAX_VALUE;

        for (var x = -CENTER_SEARCH_RADIUS; x <= CENTER_SEARCH_RADIUS; x++) {
            for (var y = -CENTER_SEARCH_VERTICAL_RADIUS; y <= CENTER_SEARCH_VERTICAL_RADIUS; y++) {
                for (var z = -CENTER_SEARCH_RADIUS; z <= CENTER_SEARCH_RADIUS; z++) {
                    var candidate = immutablePreferred.offset(x, y, z);

                    if (!isOpenEnoughForCenter(level, candidate))
                        continue;

                    var distSq = candidate.distSqr(immutablePreferred);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = candidate.immutable();
                    }
                }
            }
        }

        return best != null ? best : immutablePreferred;
    }

    /**
     * {@code true} if {@code pos} and {@link #CENTER_HEADROOM} blocks directly above it are all either naturally
     * replaceable or otherwise diggable (i.e. not bedrock or another unbreakable block). Guards against a claimed dome
     * center landing somewhere that can never actually be opened up into standable space.
     */
    private static boolean isOpenEnoughForCenter(Level level, BlockPos pos) {
        for (var y = 0; y < CENTER_HEADROOM; y++) {
            var check = pos.above(y);
            var state = level.getBlockState(check);

            if (state.canBeReplaced())
                continue;

            if (state.getDestroySpeed(level, check) < 0f)
                return false;
        }
        return true;
    }

    /**
     * Forces the block at {@code center} itself to air, guaranteeing a freshly claimed dome center is never left
     * embedded inside a solid block. Every system that treats {@link #getDomeCenter()} as a destination — wandering
     * back to the hive, tunnel-direction math, breach/light scans — assumes that exact position is standable/enterable,
     * not just "somewhere in open space nearby".
     */
    public static void ensureCenterClear(Level level, BlockPos center) {
        if (!level.getBlockState(center).isAir()) {
            level.setBlockAndUpdate(center, Blocks.CAVE_AIR.defaultBlockState());
        }
    }

    /** @return {@code true} once the dome shell is considered fully built and tunnel-building should begin */
    public boolean isDomeComplete() {
        return domeComplete;
    }

    /**
     * Records that {@code count} dome-shell blocks were just placed, and flips {@link #isDomeComplete()} once the
     * running total reaches {@code completionThreshold}.
     */
    public void recordDomeBlocksPlaced(int count, int completionThreshold) {
        domeFillCount += count;
        if (domeFillCount >= completionThreshold) {
            domeComplete = true;
        }
    }

    /**
     * Records that a resin structure block was just placed and now belongs to this hive. Called from
     * {@code AbstractResinBlock#onPlace} for every non-vent {@link ModTags#RESIN} block attributed to this hive.
     */
    public void incrementStructureBlockCount() {
        structureBlockCount++;
        everHadStructure = true;
    }

    /**
     * Records that a resin structure block belonging to this hive was destroyed. Called from
     * {@code AbstractResinBlock#onRemove}. Floored at zero so an out-of-order or duplicate removal can never make the
     * count go negative.
     */
    public void decrementStructureBlockCount() {
        if (structureBlockCount > 0) {
            structureBlockCount--;
        }
    }

    /**
     * @return {@code true} once this hive has, at some point, actually had structure ({@link #everHadStructure}), and
     *         every resin structure block and vent it had has since been destroyed — meaning nothing of it remains
     *         standing in the world for a newly created xenomorph to find. Used by
     *         {@code OvomorphosisSavedData#removeHiveIfDestroyed} to drop the hive from save data once this becomes
     *         true, so future xenomorphs can't bind to a hive that no longer physically exists.
     */
    public boolean isFullyDestroyed() {
        return everHadStructure && structureBlockCount <= 0 && ventNodes.isEmpty();
    }

    /** @return the mutable list of tunnels currently being carved; callers may add/remove entries directly */
    public List<TunnelState> getActiveTunnels() {
        return activeTunnels;
    }

    private static final double NEEDS_SCAN_RADIUS = 64.0D;

    private static final long NEEDS_RECOMPUTE_INTERVAL_TICKS = 100L;

    private static final int LIGHT_SAMPLE_LIMIT = 8;

    private static final int FEW_HOSTS_THRESHOLD = 2;

    private static final int HOST_SURPLUS_THRESHOLD = 4;

    private static final int LITTLE_RESIN_THRESHOLD = 6;

    private static final int ILLUMINATED_THRESHOLD = 8;

    private static final int CROWDED_POPULATION_THRESHOLD = 6;

    private static final int EXPANSION_SECTORS = 8;

    private static final int THRIVING_XENO_THRESHOLD = 3;

    private static final int SUSTAINED_ATTACK_THRESHOLD = 3;

    private static final int THREAT_FRESH_WINDOW_TICKS = 600;

    private static final int MAX_THREAT_RECORDS = 12;

    private int xenoCount = 0;

    private int ovomorphCount = 0;

    private int restrainedHostCount = 0;

    private int hiveLightLevel = 0;

    private long needsRecomputedAtTick = Long.MIN_VALUE;

    /** A single recorded incursion — used to detect repeated attacks on the hive rather than one-off skirmishes. */
    public record ThreatRecord(
        BlockPos pos,
        long tick
    ) {}

    private final Deque<ThreatRecord> recentThreats = new ArrayDeque<>();

    /**
     * A single recorded hive breach — a dome-shell resin block that was removed (broken by a player, an explosion,
     * etc.). See {@link #recordBreach}/{@link #findNearestPendingBreach}. Recorded regardless of whether the dome is
     * complete yet, though in practice it matters most post-completion — {@code PlaceResinAction} already re-fills any
     * gap in the shell as a matter of course while the dome is still under construction.
     */
    public record BreachRecord(
        BlockPos pos,
        long tick
    ) {}

    private static final int MAX_PENDING_BREACHES = 32;

    /**
     * A removed block only counts as a "nest breach" (as opposed to, say, a tunnel wall or something unrelated) if it's
     * within this distance band of the dome center — matching {@code PlaceResinAction}'s own
     * {@code DOME_RADIUS}/{@code DOME_SHELL_THICKNESS} band, widened slightly to tolerate imprecision. Tunnels aren't
     * covered by this feature: they don't have a fixed, known shape the way the dome shell does, so there's no cheap
     * way to tell "a tunnel wall is missing" from "this was never part of a tunnel to begin with".
     */
    private static final double BREACH_MIN_DIST_FROM_CENTER = 6.0D;

    private static final double BREACH_MAX_DIST_FROM_CENTER = 13.0D;

    private final Deque<BreachRecord> pendingBreaches = new ArrayDeque<>();

    /**
     * Coarse lifecycle stage of the hive, derived from its structural completeness and population rather than stored
     * directly — always current, never stale. Used to bias baseline behavior: a {@code HATCHLING} hive should bootstrap
     * population aggressively, while a {@code THRIVING} one has more established territory worth holding.
     */
    public enum NestMaturity {
        /** No dome has been claimed/started yet. */
        HATCHLING,
        /** Dome shell is under construction. */
        GROWING,
        /** Dome complete, but population and tunnel network are still small. */
        ESTABLISHED,
        /** Dome complete, tunnels extending, and population past {@link #THRIVING_XENO_THRESHOLD}. */
        THRIVING
    }

    /**
     * Refreshes {@link #xenoCount}, {@link #ovomorphCount}, {@link #restrainedHostCount}, and {@link #hiveLightLevel}
     * by scanning around the dome center, and re-validates the dome center itself — but only if
     * {@link #NEEDS_RECOMPUTE_INTERVAL_TICKS} have passed since the last refresh. Safe to call every planning cycle
     * from any xenomorph sharing this hive; the throttle is on the shared instance, so many mobs calling this
     * frequently still only triggers one actual scan per interval.
     *
     * @param level       the level to scan (must be the hive's dimension)
     * @param currentTick the current game tick, used both to throttle and to evict stale threat records
     * @return {@code true} if the dome center was moved/re-cleared this call — the caller should mark the owning save
     *         data dirty so the repaired position actually persists
     */
    public boolean recomputeNeedsIfDue(Level level, long currentTick) {
        if (domeCenter == null)
            return false;
        if (currentTick - needsRecomputedAtTick < NEEDS_RECOMPUTE_INTERVAL_TICKS)
            return false;
        needsRecomputedAtTick = currentTick;

        var centerRepaired = revalidateDomeCenterIfNeeded(level);

        var aabb = AABB.ofSize(
            Vec3.atCenterOf(domeCenter),
            NEEDS_SCAN_RADIUS * 2,
            NEEDS_SCAN_RADIUS * 2,
            NEEDS_SCAN_RADIUS * 2
        );

        xenoCount = level.getEntitiesOfClass(XenomorphEntity.class, aabb).size();
        ovomorphCount = level.getEntitiesOfClass(OvomorphEntity.class, aabb).size();
        restrainedHostCount = EggmorphTracker.countActiveNear(domeCenter, NEEDS_SCAN_RADIUS);
        hiveLightLevel = computeHiveLightLevel(level);

        evictStaleThreats(currentTick);

        return centerRepaired;
    }

    /**
     * Re-checks the claimed {@link #domeCenter} against {@link #isOpenEnoughForCenter} and repairs it in place — via
     * {@link #resolveOpenCenter} and {@link #ensureCenterClear} — if it no longer qualifies. Covers two cases: a center
     * that was persisted before dome-center validation existed (loaded straight off disk by {@link #load} with no
     * checks applied), and one that qualified when claimed but has since been buried again (terrain regen, a player
     * filling it in, etc.). Piggybacks on {@link #recomputeNeedsIfDue}'s existing throttle rather than needing its own
     * cooldown state, since a stale/buried center isn't something that needs checking every single tick.
     *
     * @return {@code true} if the center was actually moved/re-cleared
     */
    private boolean revalidateDomeCenterIfNeeded(Level level) {
        if (domeCenter == null || isOpenEnoughForCenter(level, domeCenter))
            return false;

        var repaired = resolveOpenCenter(level, domeCenter);
        ensureCenterClear(level, repaired);
        domeCenter = repaired;
        return true;
    }

    private int computeHiveLightLevel(Level level) {
        if (domeCenter == null)
            return 0;

        var brightest = level.getMaxLocalRawBrightness(domeCenter);
        var sampled = 0;
        for (var pos : ownedWebCrosses) {
            if (sampled >= LIGHT_SAMPLE_LIMIT)
                break;
            brightest = Math.max(brightest, level.getMaxLocalRawBrightness(pos));
            sampled++;
        }
        return brightest;
    }

    public int xenoCount() {
        return xenoCount;
    }

    public int ovomorphCount() {
        return ovomorphCount;
    }

    public int restrainedHostCount() {
        return restrainedHostCount;
    }

    public int hiveLightLevel() {
        return hiveLightLevel;
    }

    /** Few hosts currently being converted — the hive should prioritize hunting/capturing over other goals. */
    public boolean hasFewHosts() {
        return restrainedHostCount < FEW_HOSTS_THRESHOLD;
    }

    /**
     * Plenty of hosts already restrained, but not much resin infrastructure to route more captures toward — the hive
     * should prioritize construction (placing resin / expanding) over capturing yet more hosts it has nowhere to put.
     */
    public boolean hasHostSurplusWithLittleResin() {
        return restrainedHostCount >= HOST_SURPLUS_THRESHOLD
            && getOwnedWebCrosses().size() < LITTLE_RESIN_THRESHOLD;
    }

    /** The hive's core has been exposed to enough light that killing light sources should take priority. */
    public boolean isHeavilyIlluminated() {
        return hiveLightLevel > ILLUMINATED_THRESHOLD;
    }

    /**
     * Rough count of distinct horizontal compass sectors ({@value #EXPANSION_SECTORS} total) that currently have an
     * active tunnel heading into them. This is necessarily an undercount of the hive's <em>total</em> explored
     * territory — a finished or blocked tunnel is removed from {@link #activeTunnels} by {@link PlaceResinAction} once
     * exhausted — but it's a cheap, self-correcting proxy for "does the hive still have obviously unclaimed directions
     * to expand into right now" without needing separate persisted state.
     */
    private int exploredExpansionSectorCount() {
        var sectors = new HashSet<Integer>();
        for (var tunnel : activeTunnels) {
            var angle = Math.atan2(tunnel.dirZ(), tunnel.dirX());
            if (angle < 0)
                angle += 2 * Math.PI;
            var sector = (int) (angle / (2 * Math.PI / EXPANSION_SECTORS)) % EXPANSION_SECTORS;
            sectors.add(sector);
        }
        return sectors.size();
    }

    /** {@code true} if there's at least one horizontal direction not currently claimed by an active tunnel. */
    public boolean hasRoomToExpand() {
        return exploredExpansionSectorCount() < EXPANSION_SECTORS;
    }

    /**
     * The hive's combined xenomorph + ovomorph population has grown large relative to
     * {@link #CROWDED_POPULATION_THRESHOLD}. Combine with {@link #hasRoomToExpand()} to decide whether the response
     * should be "extend tunnels outward" specifically, versus just generally needing more room.
     */
    public boolean isCrowded() {
        return (xenoCount + ovomorphCount) >= CROWDED_POPULATION_THRESHOLD;
    }

    /**
     * Records a single incursion near the hive (e.g. a hostile target found near hive territory during goal planning).
     * Bounded to {@link #MAX_THREAT_RECORDS}, oldest evicted first — this only needs to answer "has this been happening
     * repeatedly lately", not keep a full history.
     */
    public void recordThreat(BlockPos pos, long tick) {
        if (recentThreats.size() >= MAX_THREAT_RECORDS) {
            recentThreats.pollFirst();
        }
        recentThreats.addLast(new ThreatRecord(pos.immutable(), tick));
    }

    /** @return how many recorded threats are still within {@link #THREAT_FRESH_WINDOW_TICKS} of {@code currentTick} */
    public int recentThreatCount(long currentTick) {
        var count = 0;
        for (var threat : recentThreats) {
            if (currentTick - threat.tick() <= THREAT_FRESH_WINDOW_TICKS)
                count++;
        }
        return count;
    }

    /** The hive has been attacked repeatedly and recently enough that defensive aggression should increase. */
    public boolean isUnderSustainedAttack(long currentTick) {
        return recentThreatCount(currentTick) >= SUSTAINED_ATTACK_THRESHOLD;
    }

    /**
     * Records that a dome-shell resin block at {@code pos} was removed — a nest breach — so a mob can be dispatched to
     * repair it (see {@code XenomorphGoalPlanner}'s EXPAND_HIVE breach-repair boost and {@code XenomorphTree}'s
     * travel-to-breach branch).
     * <p>
     * Silently ignored if the dome hasn't been claimed yet, or if {@code pos} isn't within the dome-shell distance band
     * ({@link #BREACH_MIN_DIST_FROM_CENTER}-{@link #BREACH_MAX_DIST_FROM_CENTER} from {@link #domeCenter}) — this
     * feature only covers the dome shell itself, not tunnels (see {@link #pendingBreaches}'s docs for why). Safe to
     * call redundantly for the same position.
     */
    public void recordBreach(Level level, BlockPos pos) {
        if (domeCenter == null)
            return;

        var distFromCenter = Math.sqrt(pos.distSqr(domeCenter));
        if (distFromCenter < BREACH_MIN_DIST_FROM_CENTER || distFromCenter > BREACH_MAX_DIST_FROM_CENTER)
            return;

        var immutable = pos.immutable();
        for (var breach : pendingBreaches) {
            if (breach.pos().equals(immutable))
                return;
        }

        if (pendingBreaches.size() >= MAX_PENDING_BREACHES) {
            pendingBreaches.pollFirst();
        }
        pendingBreaches.addLast(new BreachRecord(immutable, level.getGameTime()));
    }

    /**
     * Prunes any pending breach whose position has since been repaired (now genuinely resin again — see
     * {@link ModTags#RESIN}) or that no longer belongs to the dome-shell band at all (e.g. the dome was reset/moved),
     * then returns whichever remains that's within {@code maxRange} of {@code origin}, if any.
     */
    public Optional<BlockPos> findNearestPendingBreach(Level level, BlockPos origin, double maxRange) {
        pendingBreaches.removeIf(
            breach -> level.getBlockState(breach.pos()).is(ModTags.RESIN)
                || domeCenter == null
                || Math.sqrt(breach.pos().distSqr(domeCenter)) > BREACH_MAX_DIST_FROM_CENTER
        );

        var maxRangeSqr = maxRange * maxRange;
        BlockPos best = null;
        var bestDistSqr = Double.MAX_VALUE;

        for (var breach : pendingBreaches) {
            var distSqr = origin.distSqr(breach.pos());
            if (distSqr <= maxRangeSqr && distSqr < bestDistSqr) {
                best = breach.pos();
                bestDistSqr = distSqr;
            }
        }

        return Optional.ofNullable(best);
    }

    private void evictStaleThreats(long currentTick) {
        recentThreats.removeIf(threat -> currentTick - threat.tick() > THREAT_FRESH_WINDOW_TICKS);
    }

    /**
     * Coarse lifecycle stage derived from structural completeness and population — see {@link NestMaturity}. Recomputed
     * from existing state each call rather than stored, so it's always consistent with the current dome/
     * tunnel/population state without needing separate persistence or invalidation.
     */
    public NestMaturity nestMaturity() {
        if (domeCenter == null)
            return NestMaturity.HATCHLING;
        if (!domeComplete)
            return NestMaturity.GROWING;
        if (activeTunnels.isEmpty() && xenoCount < THRIVING_XENO_THRESHOLD)
            return NestMaturity.ESTABLISHED;
        return NestMaturity.THRIVING;
    }

    /**
     * Like {@link #findNearestOwnedWebCross}, but additionally requires the candidate to be dark enough to serve as a
     * genuine hideout rather than just "the closest bit of hive". This is what lets low-health retreat logic route a
     * wounded xenomorph toward known cover instead of merely running away — something a vanilla mob's health-based
     * fleeing has no equivalent of, since it has no memory of where its territory's dark spots are.
     * <p>
     * Returns {@code Optional.empty()} if no owned web cross in range meets the darkness threshold; callers should fall
     * back to {@link #findNearestOwnedWebCross} in that case rather than treating this as "no hive nearby at all".
     *
     * @param level    the level to read block/light state from
     * @param origin   the position to search outward from
     * @param maxRange maximum search radius in blocks
     * @param maxLight maximum local raw light level (0-15) a candidate position may have to still count as "dark"
     * @return the nearest sufficiently dark owned web cross within range, if any
     */
    public Optional<BlockPos> findNearestDarkOwnedWebCross(
        Level level,
        BlockPos origin,
        double maxRange,
        int maxLight
    ) {
        var maxRangeSqr = maxRange * maxRange;

        BlockPos best = null;
        var bestDistSqr = Double.MAX_VALUE;

        for (var pos : ownedWebCrosses) {
            var distSqr = origin.distSqr(pos);

            if (distSqr > maxRangeSqr)
                continue;

            if (
                !level.getBlockState(pos)
                    .is(BlockRegistry.RESIN_WEB_CROSS.get())
            ) {
                continue;
            }

            if (level.getMaxLocalRawBrightness(pos) > maxLight)
                continue;

            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = pos;
            }
        }

        return Optional.ofNullable(best);
    }

    public Optional<BlockPos> findNearestOwnedWebCross(
        Level level,
        BlockPos origin,
        double maxRange
    ) {
        var maxRangeSqr = maxRange * maxRange;

        BlockPos best = null;
        var bestDistSqr = Double.MAX_VALUE;
        var missesThisCall = 0;

        for (var pos : ownedWebCrosses) {
            var distSqr = origin.distSqr(pos);

            if (distSqr > maxRangeSqr)
                continue;

            if (
                !level.getBlockState(pos)
                    .is(BlockRegistry.RESIN_WEB_CROSS.get())
            ) {
                missesThisCall++;
                continue;
            }

            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = pos;
            }
        }

        missCount += missesThisCall;

        if (missCount >= MISS_THRESHOLD) {
            evictStaleOwnedWebCrosses(level);
            missCount = 0;
        }

        return Optional.ofNullable(best);
    }

    public void evictStaleOwnedWebCrosses(Level level) {
        ownedWebCrosses.removeIf(
            pos -> !level.getBlockState(pos)
                .is(BlockRegistry.RESIN_WEB_CROSS.get())
        );
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("hiveId", hiveId);
        var list = new ListTag();
        for (var pos : ownedWebCrosses) {
            var entry = new CompoundTag();
            entry.put(NBT_KEY, NbtUtils.writeBlockPos(pos));
            list.add(entry);
        }

        tag.put("blocks", list);

        if (domeCenter != null) {
            tag.put("domeCenter", NbtUtils.writeBlockPos(domeCenter));
        }
        tag.putInt("domeFillCount", domeFillCount);
        tag.putBoolean("domeComplete", domeComplete);
        tag.putInt("structureBlockCount", structureBlockCount);
        tag.putBoolean("everHadStructure", everHadStructure);

        var tunnelList = new ListTag();
        for (var tunnel : activeTunnels) {
            var entry = new CompoundTag();
            entry.put("tip", NbtUtils.writeBlockPos(tunnel.tip()));
            entry.putDouble("dx", tunnel.dirX());
            entry.putDouble("dy", tunnel.dirY());
            entry.putDouble("dz", tunnel.dirZ());
            entry.putInt("remaining", tunnel.remainingSteps());
            tunnelList.add(entry);
        }
        tag.put("tunnels", tunnelList);

        tag.putInt("xenoCount", xenoCount);
        tag.putInt("ovomorphCount", ovomorphCount);
        tag.putInt("restrainedHostCount", restrainedHostCount);
        tag.putInt("hiveLightLevel", hiveLightLevel);
        tag.putLong("needsRecomputedAtTick", needsRecomputedAtTick);

        var threatList = new ListTag();
        for (var threat : recentThreats) {
            var entry = new CompoundTag();
            entry.put("pos", NbtUtils.writeBlockPos(threat.pos()));
            entry.putLong("tick", threat.tick());
            threatList.add(entry);
        }
        tag.put("threats", threatList);

        var breachList = new ListTag();
        for (var breach : pendingBreaches) {
            var entry = new CompoundTag();
            entry.put("pos", NbtUtils.writeBlockPos(breach.pos()));
            entry.putLong("tick", breach.tick());
            breachList.add(entry);
        }
        tag.put("breaches", breachList);

        var ventList = new ListTag();
        for (var node : ventNodes.values()) {
            var entry = new CompoundTag();
            entry.put("pos", NbtUtils.writeBlockPos(node.position()));
            entry.putLong("lastUsed", node.lastUsedTick());
            ventList.add(entry);
        }
        tag.put("vents", ventList);

        return tag;
    }

    public static HiveMemory load(CompoundTag tag) {
        var memory = new HiveMemory();

        if (tag.hasUUID("hiveId")) {
            memory.hiveId = tag.getUUID("hiveId");
        }

        if (tag.contains("blocks", Tag.TAG_LIST)) {
            var list = tag.getList("blocks", Tag.TAG_COMPOUND);

            for (var i = 0; i < list.size(); i++) {
                memory.trackOwnedWebCross(NbtUtils.readBlockPos(list.getCompound(i)));
            }
        }

        if (tag.contains("domeCenter")) {
            memory.domeCenter = NbtUtils.readBlockPos(tag.getCompound("domeCenter")).immutable();
        }

        memory.domeFillCount =
            tag.getInt("domeFillCount");

        memory.domeComplete =
            tag.getBoolean("domeComplete");

        memory.structureBlockCount =
            tag.getInt("structureBlockCount");

        memory.everHadStructure =
            tag.getBoolean("everHadStructure");

        if (tag.contains("tunnels", Tag.TAG_LIST)) {
            var tunnelList =
                tag.getList("tunnels", Tag.TAG_COMPOUND);

            for (var i = 0; i < tunnelList.size(); i++) {
                var entry = tunnelList.getCompound(i);

                var tip = NbtUtils.readBlockPos(entry.getCompound("tip"));

                memory.activeTunnels.add(
                    new TunnelState(
                        tip,
                        entry.getDouble("dx"),
                        entry.getDouble("dy"),
                        entry.getDouble("dz"),
                        entry.getInt("remaining")
                    )
                );
            }
        }

        memory.xenoCount = tag.getInt("xenoCount");
        memory.ovomorphCount = tag.getInt("ovomorphCount");
        memory.restrainedHostCount = tag.getInt("restrainedHostCount");
        memory.hiveLightLevel = tag.getInt("hiveLightLevel");
        memory.needsRecomputedAtTick = tag.getLong("needsRecomputedAtTick");

        if (tag.contains("threats", Tag.TAG_LIST)) {
            var threatList = tag.getList("threats", Tag.TAG_COMPOUND);
            for (var i = 0; i < threatList.size(); i++) {
                var entry = threatList.getCompound(i);
                var pos = NbtUtils.readBlockPos(entry.getCompound("pos"));
                memory.recentThreats.addLast(new ThreatRecord(pos, entry.getLong("tick")));
            }
        }

        if (tag.contains("breaches", Tag.TAG_LIST)) {
            var breachList = tag.getList("breaches", Tag.TAG_COMPOUND);
            for (var i = 0; i < breachList.size(); i++) {
                var entry = breachList.getCompound(i);
                var pos = NbtUtils.readBlockPos(entry.getCompound("pos"));
                memory.pendingBreaches.addLast(new BreachRecord(pos, entry.getLong("tick")));
            }
        }

        if (tag.contains("vents", Tag.TAG_LIST)) {
            var ventList = tag.getList("vents", Tag.TAG_COMPOUND);
            for (var i = 0; i < ventList.size(); i++) {
                var entry = ventList.getCompound(i);
                var pos = NbtUtils.readBlockPos(entry.getCompound("pos"));
                memory.ventNodes.put(pos, new HiveVentNode(pos, List.of(), entry.getLong("lastUsed"), false));
            }
            memory.relinkAllVentClusters();
        }

        if (memory.structureBlockCount > 0 || !memory.ventNodes.isEmpty()) {
            memory.everHadStructure = true;
        }

        return memory;
    }

    public Collection<BlockPos> getOwnedWebCrosses() {
        return Collections.unmodifiableCollection(ownedWebCrosses);
    }
}
