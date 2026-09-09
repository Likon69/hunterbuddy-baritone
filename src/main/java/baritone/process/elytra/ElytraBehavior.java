/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.process.ElytraProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import baritone.utils.PathRenderer;
import baritone.utils.accessor.IFireworkRocketEntity;
import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.babbaj.pathfinder.PathSegment;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static baritone.utils.BaritoneMath.fastCeil;
import static baritone.utils.BaritoneMath.fastFloor;

public final class ElytraBehavior implements Helper {
    private final Baritone baritone;
    private final IPlayerContext ctx;

    // Render stuff
    private final List<Pair<Vec3, Vec3>> clearLines;
    private final List<Pair<Vec3, Vec3>> blockedLines;
    private List<Vec3> simulationLine;
    private BlockPos aimPos;
    private List<BetterBlockPos> visiblePath;

    // :sunglasses:
    public final NetherPathfinderContext context;
    /**
     * A second native context holding the same chunks as {@link #context}, except that every chunk outside the
     * corridor another mod pushed through {@link ElytraProcess#setPathCorridor} is inserted as solid, so a search
     * in it cannot leave the corridor. Only the path search uses it; the raytraces, {@link #passable} and
     * {@link NetherPathfinderContext#hasChunk} stay on {@link #context}, which knows the real terrain. Built
     * with the behavior if a corridor is set at that moment and {@code null} otherwise, so a corridor pushed
     * after that only takes effect from the next destination on.
     */
    final NetherPathfinderContext corridorContext;
    /**
     * The chunks currently inserted as solid in {@link #corridorContext} for being outside the corridor, so the
     * ring mask does not re-insert 32 KiB of solid for the same chunk every time it runs. Game thread only. Kept
     * honest by every path that changes a chunk's state in that context: a chunk that flips into the corridor
     * or gets admitted around a search start leaves the set, and a cull drops the far keys the native side has
     * just forgotten.
     */
    private final LongOpenHashSet maskedKeys = new LongOpenHashSet();
    /**
     * The player's chunk the last time the ring mask ran, so that it runs again as soon as the player has moved
     * to another chunk rather than only when the corridor is pushed.
     */
    private long lastRingCenter = ChunkPos.INVALID_CHUNK_POS;
    public final PathManager pathManager;
    private final ElytraProcess process;

    private static final int FIREWORK_COOLDOWN_TICKS = 10;
    /**
     * Remaining cool-down ticks between firework usage
     */
    private int remainingFireworkTicks;

    /**
     * Remaining cool-down ticks after the player's position and rotation are reset by the server
     */
    private int remainingSetBackTicks;

    public boolean landingMode;

    /**
     * The most recent minimum number of firework boost ticks, equivalent to {@code 10 * (1 + Flight)}
     * <p>
     * Updated every time a firework is automatically used
     */
    private int minimumBoostTicks;
    /**
     * Whether the entity of the last rocket we lit has been seen attached to us. Only for the verbose log:
     * a rocket that never shows up is one the server ignored.
     */
    private boolean fireworkSeenSinceLit = true;

    private BlockStateInterface bsi;
    private final BlockStateOctreeInterface boi;
    public final BetterBlockPos destination;
    private final boolean appendDestination;

    private final ExecutorService solverExecutor;
    private Future<Solution> solver;
    private Solution pendingSolution;
    private boolean solveNextTick;

    private long timeLastCacheCull = 0L;

    // auto swap
    private int invTickCountdown = 0;
    private final Queue<Runnable> invTransactionQueue = new LinkedList<>();

    public ElytraBehavior(Baritone baritone, ElytraProcess process, BlockPos destination, boolean appendDestination) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.clearLines = new CopyOnWriteArrayList<>();
        this.blockedLines = new CopyOnWriteArrayList<>();
        this.pathManager = this.new PathManager();
        this.process = process;
        this.destination = new BetterBlockPos(destination);
        this.appendDestination = appendDestination;
        this.solverExecutor = Executors.newSingleThreadExecutor();

        this.context = new NetherPathfinderContext(Baritone.settings().elytraNetherSeed.value);
        this.boi = new BlockStateOctreeInterface(context);
        // Same seed as the main context: with elytraPredictTerrain on, a chunk the client has not received is
        // generated from it, and the two contexts must not disagree about what is there.
        this.corridorContext = process.hasCorridor() && Baritone.settings().elytraCorridor.value
                ? new NetherPathfinderContext(Baritone.settings().elytraNetherSeed.value)
                : null;
    }

    public final class PathManager {

        public NetherPath path;
        private boolean completePath;
        private boolean recalculating;

        private int maxPlayerNear;
        private int ticksNearUnchanged;
        private int playerNear;

        // hb1: pathNextSegment resumes from the last node of the existing path, not from the
        // player. When that node falls into a pocket the native x4 pathfinder can't leave, the
        // segment computation fails every tick; the old behaviour was to keep retrying from the
        // same unreachable node until the player's own flight carried them within 16 blocks of
        // it, at which point the failure was reinterpreted as "arrived" and the bot landed there,
        // however far short of the real destination. These three track that a segment failed so
        // the next tick can restart the search from the player instead of the stuck node.
        private BetterBlockPos failedSegmentStart;
        private long failedSegmentTick;
        private boolean restartFromPlayer;
        private boolean segmentDiagnosticsPending;

        public PathManager() {
            // lol imagine initializing fields normally
            this.clear();
        }

        public void tick() {
            // Recalculate closest path node
            this.updatePlayerNear();
            final int prevMaxNear = this.maxPlayerNear;
            this.maxPlayerNear = Math.max(this.maxPlayerNear, this.playerNear);

            if (this.maxPlayerNear == prevMaxNear && ctx.player().isFallFlying()) {
                this.ticksNearUnchanged++;
            } else {
                this.ticksNearUnchanged = 0;
            }

            // Obstacles are more important than an incomplete path, handle those first.
            this.pathfindAroundObstacles();
            this.attemptNextSegment();
        }

        public CompletableFuture<Void> pathToDestination() {
            return this.pathToDestination(ctx.playerFeet());
        }

        public CompletableFuture<Void> pathToDestination(final BlockPos from) {
            final long start = System.nanoTime();
            return this.path0(from, ElytraBehavior.this.destination, UnaryOperator.identity())
                    .thenRun(() -> {
                        final double distance = this.path.get(0).distanceTo(this.path.get(this.path.size() - 1));
                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute path to destination");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public CompletableFuture<Void> pathRecalcSegment(final OptionalInt upToIncl) {
            if (this.recalculating) {
                throw new IllegalStateException("already recalculating");
            }

            this.recalculating = true;
            final List<BetterBlockPos> after = upToIncl.isPresent() ? this.path.subList(upToIncl.getAsInt() + 1, this.path.size()) : Collections.emptyList();
            final boolean complete = this.completePath;

            return this.path0(ctx.playerFeet(), upToIncl.isPresent() ? this.path.get(upToIncl.getAsInt()) : ElytraBehavior.this.destination, segment -> {
                        if (!upToIncl.isPresent() || !segment.isFinished()) {
                            // Either this went all the way to the destination and speaks for itself, or the
                            // search ran out of time short of the rejoin node. Tacking the old tail onto a
                            // segment that never reached it would leave a blind jump in the path, straight
                            // through whatever lies between, that no obstacle check can ever fix: every
                            // recomputation ends the same way, and the solver just circles the last node it
                            // can reach. Leave the path unfinished instead and continue from where it got to.
                            return segment;
                        }
                        return segment.append(after.stream(), complete);
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to recompute segment");
                                repackNearPlayer("segment recomputation failed");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void pathNextSegment(final int afterIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            final List<BetterBlockPos> before = this.path.subList(0, afterIncl + 1);
            final long start = System.nanoTime();
            final BetterBlockPos pathStart = this.path.get(afterIncl);

            this.path0(pathStart, ElytraBehavior.this.destination, segment -> segment.prepend(before.stream()))
                    .thenRun(() -> {
                        final int recompute = this.path.size() - before.size() - 1;
                        final double distance = this.path.get(0).distanceTo(this.path.get(recompute));

                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                // hb1: this handler is not on the main thread (it runs on
                                // NetherPathfinderContext's own executor, same as path0/pathFindAsync) --
                                // nothing here may touch ctx.playerFeet(), ctx.world(), or
                                // pathRecalcSegment. repackNearPlayer already knows this and hops to the
                                // main thread itself; everything added below just sets fields, read back
                                // on the main thread next tick in attemptNextSegment, the same way
                                // recalculating already is.
                                repackNearPlayer("next segment computation failed");
                                if (pathStart.distanceSq(ElytraBehavior.this.destination) <= 48 * 48) {
                                    // Genuinely at the end: the same radius the landing-spot search
                                    // downstream uses to decide "close enough". A resumed search failing
                                    // this close to the destination really does mean there's nothing more
                                    // to compute, not a stuck resume point.
                                    logDirect("Failed to compute next segment");
                                    logVerbose("Player is near the segment start, therefore repeating this calculation is pointless. Marking as complete");
                                    completePath = true;
                                } else {
                                    // One line per distinct failing node instead of one per tick: the old
                                    // behaviour logged this every single retry, up to 100+ lines a second
                                    // while the failure held.
                                    if (!pathStart.equals(this.failedSegmentStart)) {
                                        logDirect("Failed to compute next segment");
                                    } else {
                                        logVerbose("Failed to compute next segment (still stuck at the same resume point)");
                                    }

                                    logVerbose(String.format(
                                            "Segment failure diagnostics: resume point %s %s %s, %.1f blocks from player, %.1f from destination, native call took %.4f seconds",
                                            SettingsUtil.maybeCensor(pathStart.x),
                                            SettingsUtil.maybeCensor(pathStart.y),
                                            SettingsUtil.maybeCensor(pathStart.z),
                                            Math.sqrt(ctx.player().distanceToSqr(pathStart.getCenter())),
                                            Math.sqrt(pathStart.distanceSq(ElytraBehavior.this.destination)),
                                            (System.nanoTime() - start) / 1e9d
                                    ));

                                    this.failedSegmentStart = pathStart;
                                    this.failedSegmentTick = ctx.player().tickCount;
                                    this.restartFromPlayer = true;
                                    // hasChunk/passable need the main thread (world access) -- this flag
                                    // just says there's a fresh failedSegmentStart worth reporting on next
                                    // tick; attemptNextSegment logs the rest and clears it.
                                    this.segmentDiagnosticsPending = true;
                                }
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void clear() {
            this.path = NetherPath.emptyPath();
            this.completePath = true;
            this.recalculating = false;
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
            this.failedSegmentStart = null;
            this.restartFromPlayer = false;
        }

        private void setPath(final UnpackedSegment segment) {
            List<BetterBlockPos> path = segment.collect();
            if (ElytraBehavior.this.appendDestination) {
                BlockPos dest = ElytraBehavior.this.destination;
                BlockPos last = !path.isEmpty() ? path.get(path.size() - 1) : null;
                if (last != null && ElytraBehavior.this.clearView(Vec3.atLowerCornerOf(dest), Vec3.atLowerCornerOf(last), false)) {
                    path.add(new BetterBlockPos(dest));
                } else {
                    logDirect("unable to land at " + ElytraBehavior.this.destination);
                    process.landingSpotIsBad(new BetterBlockPos(ElytraBehavior.this.destination));
                }
            }
            this.path = new NetherPath(path);
            this.completePath = segment.isFinished();
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
            this.failedSegmentStart = null;
            this.restartFromPlayer = false;
        }

        public NetherPath getPath() {
            return this.path;
        }

        public int getNear() {
            return this.playerNear;
        }

        // mickey resigned
        private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            return this.search(src, dst)
                    .thenApply(UnpackedSegment::from)
                    .thenApply(operator)
                    .thenAcceptAsync(this::setPath, ctx.minecraft()::execute);
        }

        /**
         * The search behind every path computation: inside the corridor first when one is set, on the full map
         * otherwise or when the corridor has no way through. Called on the game thread (every caller is a tick or
         * a takeoff state), which {@link #corridorAdmit} relies on; the continuations run on the native contexts'
         * executors and touch nothing but the segments and the log.
         * <p>
         * A failure has to reach the callers' {@code whenComplete} handlers the way a plain {@code pathFindAsync}
         * failure does, as a {@link CompletionException} whose cause is a {@link PathCalculationException}: that
         * is what they test for, and anything else gets reported as an unhandled exception.
         */
        private CompletableFuture<PathSegment> search(final BlockPos src, final BlockPos dst) {
            final boolean x4 = Baritone.settings().elytraPathNodeSize.value >= 4;
            final boolean adaptive = Baritone.settings().elytraPathNodeAdaptive.value;
            final NetherPathfinderContext corridor = ElytraBehavior.this.corridorContext;
            if (corridor == null || !process.hasCorridor() || !Baritone.settings().elytraCorridor.value) {
                return this.searchIn(ElytraBehavior.this.context, "full map", src, dst, x4, adaptive);
            }
            ElytraBehavior.this.corridorAdmit(src);
            // handle() sees both outcomes of the corridor search and nothing else. An exceptionallyCompose chained
            // after a thenCompose fallback would also catch the fallback's own failure and run it a second time.
            return this.searchIn(corridor, "corridor", src, dst, x4, adaptive)
                    .handle((segment, ex) -> {
                        if (ex == null && !isStub(src, segment)) {
                            return CompletableFuture.completedFuture(segment);
                        }
                        final Throwable cause = ex == null ? null : unwrap(ex);
                        if (ex != null && !(cause instanceof PathCalculationException)) {
                            return CompletableFuture.<PathSegment>failedFuture(cause);
                        }
                        logVerbose("corridor: no path ("
                                + (ex == null ? "stub, " + segment.packed.length + " nodes" : cause.getMessage())
                                + "), falling back to the full map");
                        return this.searchIn(ElytraBehavior.this.context, "full map", src, dst, x4, adaptive);
                    })
                    .thenCompose(Function.identity());
        }

        /**
         * One search in one context: with wide nodes first if asked, and when that comes back a stub - or
         * doesn't come back at all - and the adaptive setting is on, the same search again with fine nodes. A
         * stub from the fine search is returned as it is; whether that means trying another context or flying
         * it is the caller's business.
         * <p>
         * The failure case matters as much as the stub. A wide search whose start sits in a node with any rock
         * in it has no start node and returns nothing at all, which is the ordinary state of affairs for a
         * takeoff out of a crevice or off the top of a fungus - and it used to propagate straight out to a
         * caller that ended the flight over it, without the fine search that would have found a way ever
         * being tried.
         */
        private CompletableFuture<PathSegment> searchIn(final NetherPathfinderContext where, final String label,
                                                       final BlockPos src, final BlockPos dst,
                                                       final boolean x4, final boolean adaptive) {
            return where.pathFindAsync(src, dst, x4)
                    .handle((segment, ex) -> {
                        if (ex != null) {
                            final Throwable cause = unwrap(ex);
                            if (x4 && adaptive && cause instanceof PathCalculationException) {
                                logVerbose(String.format("path: %s x4 (failed: %s)", label, cause.getMessage()));
                                return fineSearch(where, label, src, dst);
                            }
                            return CompletableFuture.<PathSegment>failedFuture(cause);
                        }
                        logVerbose(String.format("path: %s x%d (finished=%b, %d nodes)", label, x4 ? 4 : 2, segment.finished, segment.packed.length));
                        if (x4 && adaptive) {
                            if (isStub(src, segment)) {
                                return fineSearch(where, label, src, dst);
                            }
                            final double wide = detourRatio(src, segment);
                            if (wide > Baritone.settings().elytraPathDetourRatio.value) {
                                logVerbose(String.format("path: %s x4 wanders (%.2fx the straight line), trying x2", label, wide));
                                return fineSearch(where, label, src, dst).thenApply(fine -> {
                                    // Only take the fine path if it really is the straighter one. The wide
                                    // search is the fast one and flies better, so it keeps the segment unless
                                    // the gap it routed around turns out to be worth threading.
                                    //
                                    // "Straighter" has to mean the same journey. A fine search that gave up
                                    // early has a small ratio for having gone nowhere, and detourRatio's 0 for
                                    // "too short to judge" is smaller than anything: both used to win against
                                    // a wide path that actually reached the rejoin node (28 of 275 swaps in
                                    // the 16:35 flight), which put a stub in place of a finished segment.
                                    final double narrow = detourRatio(src, fine);
                                    final boolean comparable = narrow > 0
                                            && !isStub(src, fine)
                                            && (fine.finished || !segment.finished)
                                            // two unfinished searches measure two different journeys; the
                                            // fine one only counts if it got at least as close to the target
                                            && (fine.finished || lastNodeDistSq(fine, dst) <= lastNodeDistSq(segment, dst));
                                    if (comparable && narrow < wide) {
                                        logVerbose(String.format("path: %s x2 is straighter (%.2fx), taking it", label, narrow));
                                        return fine;
                                    }
                                    return segment;
                                });
                            }
                        }
                        return CompletableFuture.completedFuture(segment);
                    })
                    .thenCompose(Function.identity());
        }

        /** The same search with 2-block nodes, which fits through gaps and starts from places x4 cannot. */
        private CompletableFuture<PathSegment> fineSearch(final NetherPathfinderContext where, final String label,
                                                         final BlockPos src, final BlockPos dst) {
            return where.pathFindAsync(src, dst, false)
                    .thenApply(fine -> {
                        logVerbose(String.format("path: %s x2 (finished=%b, %d nodes)", label, fine.finished, fine.packed.length));
                        return fine;
                    });
        }

        /**
         * How far the segment travels for every block of progress it makes, as a multiple: {@code 1.0} is a
         * straight line to where it ended up, {@code 2.0} means it flew twice as far as it got.
         * <p>
         * This is what tells a detour from a route. With 4-block nodes the search only goes through openings
         * four blocks wide, so it routes around anything narrower - around a whole massif, sometimes, where a
         * gliding player would have slipped through the crevice. That is a <i>successful, finished</i> search,
         * so the stub test above never sees it and the fine search that would have found the gap never runs.
         * {@code 0} for a segment too short to judge: near the start every path wanders a little, and paying
         * for a second search over it would double the cost of the common case for nothing.
         */
        private static double detourRatio(final BlockPos src, final PathSegment segment) {
            if (segment.packed.length < 4) {
                return 0;
            }
            double travelled = 0;
            BetterBlockPos previous = BetterBlockPos.deserializeFromLong(segment.packed[0]);
            for (int i = 1; i < segment.packed.length; i++) {
                final BetterBlockPos node = BetterBlockPos.deserializeFromLong(segment.packed[i]);
                travelled += previous.distanceTo(node);
                previous = node;
            }
            final double dx = previous.x - src.getX();
            final double dy = previous.y - src.getY();
            final double dz = previous.z - src.getZ();
            final double straight = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (straight < 64) {
                return 0;
            }
            return travelled / straight;
        }

        /** Squared distance from the segment's last node to {@code target}; infinite for an empty segment. */
        private static double lastNodeDistSq(final PathSegment segment, final BlockPos target) {
            if (segment.packed.length == 0) {
                return Double.POSITIVE_INFINITY;
            }
            final BetterBlockPos last = BetterBlockPos.deserializeFromLong(segment.packed[segment.packed.length - 1]);
            final double dx = last.x - target.getX();
            final double dy = last.y - target.getY();
            final double dz = last.z - target.getZ();
            return dx * dx + dy * dy + dz * dz;
        }

        /**
         * An unfinished segment whose last node is still within 64 blocks (horizontally) of where it started.
         * The native search does not return null for "no way through": when its open set runs dry or the timeout
         * hits, it hands back the best node it reached, unfinished. Near the start that means it was boxed in,
         * which is worth retrying with finer nodes or on the full map. Far from the start it is a segment worth
         * flying: the destination normally sits hundreds of blocks past the loaded edge, so every search ends
         * unfinished at the fake-chunk cutoff, and calling those failures would leave nothing to fly at all.
         */
        private static boolean isStub(final BlockPos src, final PathSegment segment) {
            if (segment.finished) {
                return false;
            }
            if (segment.packed.length == 0) {
                return true;
            }
            final BetterBlockPos last = BetterBlockPos.deserializeFromLong(segment.packed[segment.packed.length - 1]);
            final double dx = last.x - src.getX();
            final double dz = last.z - src.getZ();
            return dx * dx + dz * dz < 64 * 64;
        }

        /**
         * The exception a stage actually failed with, out of the {@link CompletionException}s that chaining wraps
         * it in (once per stage it crossed).
         */
        private static Throwable unwrap(Throwable ex) {
            while (ex instanceof CompletionException && ex.getCause() != null) {
                ex = ex.getCause();
            }
            return ex;
        }

        private void pathfindAroundObstacles() {
            if (this.recalculating) {
                return;
            }

            if (!ctx.player().isFallFlying()) {
                // On the ground the path is not being flown, and every path we have there was computed on
                // purpose by a takeoff state - from the exit cube above a hole, say, so that the takeoff
                // rocket leaves along a line that is actually clear. The checks below only see that from
                // inside the hole none of its nodes are visible, and recompute it from our feet every tick
                // (the wide search failing each time for the rock in its start node): twenty native searches
                // a second for as long as we walk, and the takeoff then leaves along the path from the feet,
                // straight back into the wall the exit was chosen to avoid. Resume once we are flying.
                return;
            }

            if (process.inTakeoffGrace()) {
                // The path we are flying was computed by the takeoff, from a cube chosen so the first boost
                // leaves along something clear. The checks below recompute from the player's feet, and for
                // the first tick or two after the elytra opens the feet are still in the hole - so the path
                // is replaced by one aimed out of it, which is what the takeoff picked the cube to avoid.
                // Measured: five takeoffs out of five had their path replaced on the first airborne tick.
                return;
            }

            if (ctx.player().isInLava()) {
                // Nothing here can be acted on while we are in a pool: solveAngles hands the whole tick to
                // solveLavaEscape, which flies straight up and takes nothing from the path but a yaw. The
                // checks below would still run, and from inside lava the view to every node is blocked by
                // definition, so "no path points were visible" fires every tick and spends a full native
                // search on a path that is not being followed - for as long as we are stuck, which is
                // exactly when the game thread can least afford it. Resume when we are out.
                return;
            }

            int rangeStartIncl = playerNear;
            int rangeEndExcl = playerNear;
            while (rangeEndExcl < path.size() && context.hasChunk(new ChunkPos(path.get(rangeEndExcl)))) {
                rangeEndExcl++;
            }
            // rangeEndExcl now represents an index either not in the path, or just outside render distance
            if (rangeStartIncl >= rangeEndExcl) {
                // not loaded yet?
                return;
            }
            final BetterBlockPos rangeStart = path.get(rangeStartIncl);
            if (!ElytraBehavior.this.passable(rangeStart.x, rangeStart.y, rangeStart.z, false)) {
                // the node closest to us is now inside a block, so the terrain changed since this path was computed
                final BetterBlockPos feet = ctx.playerFeet();
                if (!ElytraBehavior.this.passable(feet.x, feet.y, feet.z, false)) {
                    // we're inside a block ourselves, recalculating from here can't succeed. while flying that
                    // means the cache is wrong about where we are, so refresh it
                    repackNearPlayer("the cache has us inside a block");
                    return;
                }
                int rejoin = rangeEndExcl - 1;
                while (rejoin > rangeStartIncl && !ElytraBehavior.this.passable(path.get(rejoin).x, path.get(rejoin).y, path.get(rejoin).z, false)) {
                    rejoin--;
                }
                final BetterBlockPos wallDest = ElytraBehavior.this.destination;
                final OptionalInt rejoinAt = rejoin > rangeStartIncl && path.get(rejoin).distanceSq(wallDest) < feet.distanceSq(wallDest)
                        ? OptionalInt.of(rejoin)
                        : OptionalInt.empty();
                this.pathRecalcSegment(rejoinAt)
                        .thenRun(() -> logVerbose("Recalculated segment, path node was inside a block"));
                return;
            }

            if (ElytraBehavior.this.process.state != ElytraProcess.State.LANDING && this.ticksNearUnchanged > 100) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1))
                        .thenRun(() -> {
                            logVerbose("Recalculating segment, no progress in last 100 ticks");
                        });
                this.ticksNearUnchanged = 0;
                return;
            }

            boolean canSeeAny = false;
            for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {
                if (ElytraBehavior.this.clearView(ctx.playerFeetAsVec(), this.path.getVec(i), false) || ElytraBehavior.this.clearView(ctx.playerHead(), this.path.getVec(i), false)) {
                    canSeeAny = true;
                }
                if (!ElytraBehavior.this.clearView(this.path.getVec(i), this.path.getVec(i + 1), false)) {
                    // obstacle. where do we return to pathing?
                    // if the end of render distance is closer to goal, then that's fine, otherwise we'd be "digging our hole deeper" and making an already bad backtrack worse
                    OptionalInt rejoinMainPathAt;
                    if (this.path.get(rangeEndExcl - 1).distanceSq(ElytraBehavior.this.destination) < ctx.playerFeet().distanceSq(ElytraBehavior.this.destination)) {
                        rejoinMainPathAt = OptionalInt.of(rangeEndExcl - 1); // rejoin after current render distance
                    } else {
                        rejoinMainPathAt = OptionalInt.empty(); // large backtrack detected. ignore render distance, rejoin later on
                    }

                    final BetterBlockPos blockage = this.path.get(i);
                    final double distance = ctx.playerFeet().distanceTo(this.path.get(rejoinMainPathAt.orElse(path.size() - 1)));

                    final long start = System.nanoTime();
                    this.pathRecalcSegment(rejoinMainPathAt)
                            .thenRun(() -> {
                                logVerbose(String.format("Recalculated segment around path blockage near %s %s %s (next %.1f blocks in %.4f seconds)",
                                        SettingsUtil.maybeCensor(blockage.x),
                                        SettingsUtil.maybeCensor(blockage.y),
                                        SettingsUtil.maybeCensor(blockage.z),
                                        distance,
                                        (System.nanoTime() - start) / 1e9d
                                ));
                            });
                    return;
                }
            }
            if (!canSeeAny && rangeStartIncl < rangeEndExcl - 2 && process.state != ElytraProcess.State.GET_TO_JUMP) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1)).thenRun(() -> logVerbose("Recalculated segment since no path points were visible"));
            }
        }

        private void attemptNextSegment() {
            if (this.recalculating) {
                return;
            }

            if (this.segmentDiagnosticsPending && this.failedSegmentStart != null) {
                // The only two facts §3.2 wants that the failure handler itself couldn't get at,
                // off the main thread: whether the resume point's chunk had actually arrived, and
                // whether the point itself sits in the solid or in a pocket of air. Logged once per
                // failure, not every tick it's being backed off or retried.
                this.segmentDiagnosticsPending = false;
                BetterBlockPos p = this.failedSegmentStart;
                logVerbose(String.format(
                        "Segment failure diagnostics: hasChunk=%b passable=%b",
                        ElytraBehavior.this.context.hasChunk(new ChunkPos(p)),
                        ElytraBehavior.this.passable(p.x, p.y, p.z, false)
                ));
            }

            if (this.restartFromPlayer && ctx.player().isFallFlying()) {
                // Airborne: findAir<X4> from the player's own position finds an open cube almost
                // immediately, since flying there at all means it isn't buried the way the stuck
                // resume node was. Grounded (mid-landing, taking off) there's nothing better to
                // search from yet, so fall through to the ordinary retry below instead until
                // flight resumes.
                this.restartFromPlayer = false;
                logVerbose("Next segment start unreachable, re-pathing from the player");
                this.pathRecalcSegment(OptionalInt.empty());
                return;
            }

            final int last = this.path.size() - 1;
            if (this.path.get(last).equals(this.failedSegmentStart)
                    && ctx.player().tickCount - this.failedSegmentTick < 40) {
                // The re-path from the player above (or a previous tick's own attempt) failed and
                // landed right back on this same stuck node moments ago -- retrying it again this
                // instant would just trade the old "Failed to compute next segment" spam for
                // "Failed to recompute segment" spam instead. Give the world a few ticks.
                return;
            }

            if (!this.completePath && ctx.world().isLoaded(this.path.get(last))) {
                this.pathNextSegment(last);
            }
        }

        public void updatePlayerNear() {
            if (this.path.isEmpty()) {
                return;
            }

            int index = this.playerNear;
            final BetterBlockPos pos = ctx.playerFeet();
            for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i >= Math.max(index - 50, 0); i--) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 50, path.size()); i++) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            this.playerNear = index;
        }

        public boolean isComplete() {
            return this.completePath;
        }
    }

    public void onRenderPass(RenderEvent event) {

        final Settings settings = Baritone.settings();
        if (this.visiblePath != null) {
            PathRenderer.drawPath(event.getModelViewStack(), this.visiblePath, 0, settings.elytraPathColor.value, false, 0, 0, 0.0D);
        }
        if (this.aimPos != null) {
            PathRenderer.drawGoal(event.getModelViewStack(), ctx, new GoalBlock(this.aimPos), event.getPartialTicks(), Color.GREEN);
        }
        if (!this.clearLines.isEmpty() && settings.elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.GREEN);
            for (Pair<Vec3, Vec3> line : this.clearLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (!this.blockedLines.isEmpty() && Baritone.settings().elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE);
            for (Pair<Vec3, Vec3> line : this.blockedLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (this.simulationLine != null && Baritone.settings().elytraRenderSimulation.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(new Color(0x36CCDC));
            final Vec3 offset = ctx.player().getPosition(event.getPartialTicks());
            for (int i = 0; i < this.simulationLine.size() - 1; i++) {
                final Vec3 src = this.simulationLine.get(i).add(offset);
                final Vec3 dst = this.simulationLine.get(i + 1).add(offset);
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), src, dst, settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
    }

    public void onChunkEvent(ChunkEvent event) {
        if (event.isPostPopulate() && this.context != null) {
            // A chunk packet the client discarded (outside its view range) still raises this event, and the
            // world hands back its shared empty chunk for it. Packing that would record a chunk of pure air as
            // real terrain, and the path would then be routed straight through whatever is actually there.
            final LevelChunk chunk = ctx.world().getChunkSource().getChunk(event.getX(), event.getZ(), false);
            if (chunk != null && !chunk.isEmpty()) {
                this.context.queueForPacking(chunk);
                this.feedCorridor(chunk);
            }
        }
    }

    // Wall-clock so that it also bounds the repacks queued from the recomputation callbacks between ticks
    private static final long REPACK_NEAR_COOLDOWN_MS = 2000;
    private long lastRepackNearMs;

    /**
     * Re-reads the chunks around the player into the pathfinder's cache. Everything the solver and the
     * obstacle checks do runs against that cache, so if it disagrees with the world (a chunk whose packing was
     * missed, a section the block update path never saw) the solver aims at terrain it thinks is air and
     * nothing in here notices - the raytraces look at the same cache. This is the fix that cancelling and
     * re-engaging used to apply by accident, through {@link #repackChunks()}.
     */
    private void repackNearPlayer(final String why) {
        if (!ctx.minecraft().isSameThread()) {
            ctx.minecraft().execute(() -> this.repackNearPlayer(why));
            return;
        }
        if (ctx.player() == null || ctx.world() == null || this.context == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - this.lastRepackNearMs < REPACK_NEAR_COOLDOWN_MS) {
            return;
        }
        this.lastRepackNearMs = now;
        final BetterBlockPos feet = ctx.playerFeet();
        final int centerX = feet.x >> 4;
        final int centerZ = feet.z >> 4;
        final ChunkSource chunkSource = ctx.world().getChunkSource();
        int packed = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final LevelChunk chunk = chunkSource.getChunk(centerX + dx, centerZ + dz, false);
                if (chunk != null && !chunk.isEmpty()) {
                    this.context.queueForPacking(chunk);
                    this.feedCorridor(chunk);
                    packed++;
                }
            }
        }
        logVerbose("Repacking " + packed + " chunks around the player: " + why);
    }

    public void onBlockChange(BlockChangeEvent event) {
        this.context.queueBlockUpdate(event);
        // Only for a chunk inside the corridor: a masked chunk is solid on purpose, and applying a block update
        // to it would carve real air into the wall.
        if (this.corridorContext != null && process.corridorContains(event.getChunkPos().toLong())) {
            this.corridorContext.queueBlockUpdate(event);
        }
    }

    /**
     * Mirrors a chunk that was just packed into {@link #context} into {@link #corridorContext}: as real terrain
     * if it is inside the corridor, as a solid block if it is not. Called from every place the main context is
     * given a chunk, so the two contexts never disagree about which chunks exist, only about what is in them.
     */
    private void feedCorridor(final LevelChunk chunk) {
        if (this.corridorContext == null) {
            return;
        }
        final ChunkPos pos = chunk.getPos();
        final long key = pos.toLong();
        if (process.corridorContains(key)) {
            this.maskedKeys.remove(key);
            this.corridorContext.queueForPacking(chunk, true);
        } else {
            this.maskedKeys.add(key);
            this.corridorContext.queueSolid(pos.x, pos.z);
        }
    }

    /**
     * Applies a corridor change to {@link #corridorContext}. {@code flipped} holds the chunks whose membership
     * changed: the ones now inside get their real terrain back, or air when the client does not have them (which
     * is what a chunk the pathfinder was never given counts as anyway); the ones now outside are masked solid.
     * Then the ring mask runs, see {@link #ringMask}. Game thread only: it reads loaded chunks and
     * {@link #maskedKeys}. Public only because {@link ElytraProcess} is in another package, like the other
     * members it reaches into here.
     */
    public void corridorRefresh(final LongCollection flipped) {
        if (this.corridorContext == null || ctx.world() == null || ctx.player() == null) {
            return;
        }
        if (!process.hasCorridor()) {
            // The corridor was cleared. Nothing searches this context until a new destination builds a new
            // behavior, so masking and unmasking hundreds of chunks for it would be work for nothing.
            return;
        }
        final ChunkSource chunkSource = ctx.world().getChunkSource();
        final LongIterator it = flipped.iterator();
        while (it.hasNext()) {
            final long key = it.nextLong();
            final int x = ChunkPos.getX(key);
            final int z = ChunkPos.getZ(key);
            if (process.corridorContains(key)) {
                this.maskedKeys.remove(key);
                final LevelChunk chunk = chunkSource.getChunk(x, z, false);
                if (chunk != null && !chunk.isEmpty()) {
                    this.corridorContext.queueForPacking(chunk, true);
                } else {
                    this.corridorContext.queueAir(x, z);
                }
            } else {
                this.maskedKeys.add(key);
                this.corridorContext.queueSolid(x, z);
            }
        }
        this.ringMask();
    }

    /**
     * Masks solid, once, every chunk within {@link Settings#elytraCorridorMaskRadius} of the player that is not
     * in the corridor, loaded or not. The pathfinder treats a chunk it was never given as air, so without this
     * the search would leave the corridor through the unloaded fringe the moment the corridor bends. Runs on
     * every push, and also whenever the player has moved to another chunk between pushes: the corridor is only
     * pushed when it changes, and in the seconds it does not the player would otherwise fly out of the ring.
     */
    private void ringMask() {
        final int radius = Baritone.settings().elytraCorridorMaskRadius.value;
        final ChunkPos center = ctx.player().chunkPosition();
        this.lastRingCenter = center.toLong();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final int x = center.x + dx;
                final int z = center.z + dz;
                final long key = ChunkPos.asLong(x, z);
                if (!process.corridorContains(key) && this.maskedKeys.add(key)) {
                    this.corridorContext.queueSolid(x, z);
                }
            }
        }
    }

    /**
     * Makes sure a corridor search can start from {@code src}: the loaded 3x3 chunks around it are packed as real
     * terrain into the corridor context whether or not they are in the corridor. The pathfinder's search for an
     * open cube to start in walks straight through solid, so from a start inside a masked chunk it would settle
     * on some far cube on the other side of the wall and the path would begin nowhere near us. Transient by
     * design: the next repack of these chunks masks them again if they are outside the corridor, which is why
     * they leave {@link #maskedKeys} here instead of being recorded as masked while they are not. Queued on the
     * corridor context's executor ahead of the search itself, so the search sees them.
     */
    private void corridorAdmit(final BlockPos src) {
        if (this.corridorContext == null || !ctx.minecraft().isSameThread() || ctx.world() == null) {
            return;
        }
        final ChunkSource chunkSource = ctx.world().getChunkSource();
        final int centerX = src.getX() >> 4;
        final int centerZ = src.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final LevelChunk chunk = chunkSource.getChunk(centerX + dx, centerZ + dz, false);
                if (chunk != null && !chunk.isEmpty()) {
                    this.maskedKeys.remove(chunk.getPos().toLong());
                    this.corridorContext.queueForPacking(chunk, true);
                }
            }
        }
    }

    public void onReceivePacket(PacketEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            ctx.minecraft().execute(() -> {
                this.remainingSetBackTicks = Baritone.settings().elytraFireworkSetbackUseDelay.value;
            });
        }
    }

    public void pathTo() {
        if (!Baritone.settings().elytraAutoJump.value || ctx.player().isFallFlying()) {
            this.pathManager.pathToDestination();
        }
    }

    public void destroy() {
        if (this.solver != null) {
            this.solver.cancel(true);
        }
        this.solverExecutor.shutdown();
        try {
            while (!this.solverExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (this.context.shutdown()) {
            // Freeing on the game thread orders the free behind any path result already queued on it,
            // and with both executors drained nothing else can be inside a native call.
            ctx.minecraft().execute(this.context::free);
        }
        // otherwise a search is still wedged in native code: freeing under it would be a use-after-free, so
        // the context is abandoned. The leak is bounded to one context per wedged search.
        if (this.corridorContext != null && this.corridorContext.shutdown()) {
            // Same rules as the main context: freed on the game thread once its executor has drained, and
            // abandoned if a search is wedged in it. Its bounded wait comes after the main context's, so a
            // teardown with both wedged takes twice the timeout, on the Baritone executor.
            ctx.minecraft().execute(this.corridorContext::free);
        }
    }

    public void repackChunks() {
        ChunkSource chunkProvider = ctx.world().getChunkSource();

        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    this.context.queueForPacking(chunk);
                    this.feedCorridor(chunk);
                }
            }
        }
        if (this.corridorContext != null && process.hasCorridor()) {
            // This runs when the behavior is built, before its first search, and the ring otherwise only appears
            // on the next corridor push. Until then the unloaded fringe would be air to the search.
            this.ringMask();
        }
    }

    public void onTick() {
        synchronized (this.context.cullingLock) {
            this.onTick0();
        }
        if (this.corridorContext != null && process.hasCorridor()
                && ctx.player().chunkPosition().toLong() != this.lastRingCenter) {
            this.ringMask();
        }
        final long now = System.currentTimeMillis();
        if ((now - this.timeLastCacheCull) / 1000 > Baritone.settings().elytraTimeBetweenCacheCullSecs.value) {
            final int chunkX = ctx.player().chunkPosition().x;
            final int chunkZ = ctx.player().chunkPosition().z;
            final int cullDistance = Baritone.settings().elytraCacheCullDistance.value;
            this.context.queueCacheCulling(chunkX, chunkZ, cullDistance, this.boi);
            if (this.corridorContext != null) {
                // No octree interface reads the corridor context, hence null. The cull erases masked chunks like
                // any other, so forget them here with the native side's own distance rule (chunk units, squared),
                // or the ring mask would never mask a chunk we come back to again.
                this.corridorContext.queueCacheCulling(chunkX, chunkZ, cullDistance, null);
                final long maxDistSq = (long) (cullDistance / 16) * (cullDistance / 16);
                final LongIterator it = this.maskedKeys.iterator();
                while (it.hasNext()) {
                    final long key = it.nextLong();
                    final long dx = ChunkPos.getX(key) - chunkX;
                    final long dz = ChunkPos.getZ(key) - chunkZ;
                    if (dx * dx + dz * dz > maxDistSq) {
                        it.remove();
                    }
                }
            }
            this.timeLastCacheCull = now;
        }
    }

    private void onTick0() {
        // Fetch the previous solution, regardless of if it's going to be used
        this.pendingSolution = null;
        if (this.solver != null) {
            try {
                this.pendingSolution = this.solver.get();
            } catch (Exception ignored) {
                // it doesn't matter if get() fails since the solution can just be recalculated synchronously
            } finally {
                this.solver = null;
            }
        }

        tickInventoryTransactions();

        // Certified mojang employee incident
        if (this.remainingFireworkTicks > 0) {
            this.remainingFireworkTicks--;
            if (this.remainingFireworkTicks == 0 && !this.fireworkSeenSinceLit) {
                logVerbose("the rocket lit " + FIREWORK_COOLDOWN_TICKS + " ticks ago never showed up, the server ignored it");
            }
        }
        if (this.remainingSetBackTicks > 0) {
            this.remainingSetBackTicks--;
        }
        if (this.getAttachedFirework().isPresent()) {
            this.fireworkSeenSinceLit = true;
        } else if (this.remainingFireworkTicks <= 0) {
            // nothing burning, and nothing lit recently enough for its entity to still be on its way from the server
            this.minimumBoostTicks = 0;
        }

        // Reset rendered elements
        this.clearLines.clear();
        this.blockedLines.clear();
        this.visiblePath = null;
        this.simulationLine = null;
        this.aimPos = null;

        // ctx AND context???? :DDD
        // Before the path check: everything the solver simulates goes through passable(), and the lava escape
        // solves without a path
        this.bsi = new BlockStateInterface(ctx);

        final List<BetterBlockPos> path = this.pathManager.getPath();
        if (path.isEmpty()) {
            return;
        } else if (this.destination == null) {
            this.pathManager.clear();
            return;
        }

        this.pathManager.tick();

        final int playerNear = this.pathManager.getNear();
        this.visiblePath = path.subList(
                Math.max(playerNear - 30, 0),
                Math.min(playerNear + 100, path.size())
        );
    }

    /**
     * Called by {@link baritone.process.ElytraProcess#onTick(boolean, boolean)} when the process is in control and the player is flying
     */
    public void tick() {
        final boolean inLava = ctx.player().isInLava();
        if (this.pathManager.getPath().isEmpty() && !inLava) {
            return;
        }

        trySwapElytra();

        if (ctx.player().horizontalCollision) {
            logVerbose("hbonk");
            // the simulation never flies into anything it knows about, so this is something it didn't
            repackNearPlayer("hit something the cache didn't know about");
        }
        if (ctx.player().verticalCollision) {
            logVerbose("vbonk");
        }
        if (this.bsi == null) {
            // onTick0 has not run yet (a path calculation has held the lock every tick so far), nothing to solve against
            return;
        }

        final SolverContext solverContext = this.new SolverContext(false);
        this.solveNextTick = true;

        // If there's no previously calculated solution to use, or the context used at the end of last tick doesn't match this tick
        final Solution solution;
        if (this.pendingSolution == null || !this.pendingSolution.context.equals(solverContext)) {
            solution = this.solveAngles(solverContext);
        } else {
            solution = this.pendingSolution;
        }

        if (inLava) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }

        if (solution == null) {
            logVerbose("no solution");
            return;
        }

        baritone.getLookBehavior().updateTarget(solution.rotation, false);

        if (!solution.solvedPitch) {
            logVerbose("no pitch solution, probably gonna crash in a few ticks LOL!!!");
            return;
        } else {
            this.aimPos = new BetterBlockPos(solution.goingTo.x, solution.goingTo.y, solution.goingTo.z);
        }

        this.tickUseFireworks(
                solution.context.start,
                solution.goingTo,
                solution.context.boost.isBoosted(),
                solution.forceUseFirework || inLava,
                inLava
        );
    }

    public void onPostTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN && this.solveNextTick) {
            // We're at the end of the tick, the player's position likely updated and the closest path node could've
            // changed. Updating it now will avoid unnecessary recalculation on the main thread.
            this.pathManager.updatePlayerNear();

            final SolverContext context = this.new SolverContext(true);
            this.solver = this.solverExecutor.submit(() -> this.solveAngles(context));
            this.solveNextTick = false;
        }
    }

    private Solution solveAngles(final SolverContext context) {
        if (context.ignoreLava) {
            return this.solveLavaEscape(context);
        }
        final NetherPath path = context.path;
        final int playerNear = landingMode ? path.size() - 1 : context.playerNear;
        final Vec3 start = context.start;
        final int scanAhead = Math.max(1, Baritone.settings().elytraPathLookahead.value);
        Solution solution = null;

        for (int relaxation = 0; relaxation < 3; relaxation++) { // try for a strict solution first, then relax more and more (if we're in a corner or near some blocks, it will have to relax its constraints a bit)
            int[] heights = context.boost.isBoosted() ? new int[]{20, 10, 5, 0} : new int[]{0}; // attempt to gain height, if we can, so as not to waste the boost
            int lookahead = relaxation == 0 ? 2 : 3; // ideally this would be expressed as a distance in blocks, rather than a number of voxel steps
            //int minStep = Math.max(0, playerNear - relaxation);
            int minStep = playerNear;

            for (int i = Math.min(playerNear + scanAhead, path.size() - 1); i >= minStep; i--) {
                final List<Pair<Vec3, Integer>> candidates = new ArrayList<>();
                for (int dy : heights) {
                    if (relaxation == 0 || i == minStep) {
                        // no interp
                        candidates.add(new Pair<>(path.getVec(i), dy));
                    } else if (relaxation == 1) {
                        final double[] interps = new double[]{1.0, 0.75, 0.5, 0.25};
                        for (double interp : interps) {
                            final Vec3 dest = interp == 1.0
                                    ? path.getVec(i)
                                    : path.getVec(i).scale(interp).add(path.getVec(i - 1).scale(1.0 - interp));
                            candidates.add(new Pair<>(dest, dy));
                        }
                    } else {
                        // Create a point along the segment every block
                        final Vec3 delta = path.getVec(i).subtract(path.getVec(i - 1));
                        final int steps = fastFloor(delta.length());
                        final Vec3 step = delta.normalize();
                        Vec3 stepped = path.getVec(i);
                        for (int interp = 0; interp < steps; interp++) {
                            candidates.add(new Pair<>(stepped, dy));
                            stepped = stepped.subtract(step);
                        }
                    }
                }

                for (final Pair<Vec3, Integer> candidate : candidates) {
                    final Integer augment = candidate.second();
                    Vec3 dest = candidate.first().add(0, augment, 0);
                    if (landingMode) {
                        dest = dest.add(0.5, 0.5, 0.5);
                    }

                    if (augment != 0) {
                        if (i + lookahead >= path.size()) {
                            continue;
                        }
                        if (start.distanceTo(dest) < 40) {
                            if (!this.clearView(dest, path.getVec(i + lookahead).add(0, augment, 0), false)
                                    || !this.clearView(dest, path.getVec(i + lookahead), false)) {
                                // aka: don't go upwards if doing so would prevent us from being able to see the next position **OR** the modified next position
                                continue;
                            }
                        } else {
                            // but if it's far away, allow gaining altitude if we could lose it again by the time we get there
                            if (!this.clearView(dest, path.getVec(i), false)) {
                                continue;
                            }
                        }
                    }

                    final double minAvoidance = Baritone.settings().elytraMinimumAvoidance.value;
                    final Double growth = relaxation == 2 ? null
                            : relaxation == 0 ? 2 * minAvoidance : minAvoidance;

                    if (this.isHitboxClear(context, dest, growth)) {
                        // Yaw is trivial, just calculate the rotation required to face the destination
                        final float yaw = RotationUtils.calcRotationFromVec3d(start, dest, ctx.playerRotations()).getYaw();

                        final Pair<Float, Boolean> pitch = this.solvePitch(context, dest, relaxation);
                        if (pitch == null) {
                            solution = new Solution(context, new Rotation(yaw, ctx.playerRotations().getPitch()), null, false, false);
                            continue;
                        }

                        // A solution was found with yaw AND pitch, so just immediately return it.
                        return new Solution(context, new Rotation(yaw, pitch.first()), dest, true, pitch.second());
                    }
                }
            }
        }
        return this.solveSurvival(context, solution);
    }

    /**
     * In lava the elytra is inert: vanilla moves us by the fluid rules whenever we are in one, glide or not, so
     * the only thrust is the rocket, at a fraction of its usual push, and the pool is walled in a few blocks
     * away on every side. Aiming at the path would spend the rocket pushing against basalt. The way out is up:
     * the steepest climb the simulation keeps clear, and a rocket, every time. Works without a path, since a
     * lava takeoff may still be waiting for its first one.
     */
    private Solution solveLavaEscape(final SolverContext context) {
        final NetherPath path = context.path;
        // yaw is irrelevant to a vertical rocket; face where we are going so that the climb comes out pointed
        // along the path once the normal solver takes over
        final Vec3 towards = path.isEmpty()
                ? Vec3.atCenterOf(this.destination)
                : path.getVec(Math.min(context.playerNear + 1, path.size() - 1));
        final float yaw = RotationUtils.calcRotationFromVec3d(context.start, towards, ctx.playerRotations()).getYaw();
        final int ticks = Math.max(5, Baritone.settings().elytraSimulationTicks.value);
        // simulate with a rocket burning whether or not one is yet: the escape needs one, and tick() lights it
        final int ticksBoosted = context.boost.isBoosted() ? Math.max(1, context.boost.getGuaranteedBoostTicks()) : 10;
        final int ticksBoostDelay = context.boost.isBoosted() ? 0 : 2;
        final PitchResult best = this.solveSurvivalPitch(context, yaw, ticks, ticksBoosted, ticksBoostDelay);
        if (best == null) {
            return null; // cancelled by the game thread
        }
        this.simulationLine = best.steps;
        final Vec3 last = best.steps.get(best.steps.size() - 1);
        return new Solution(context, new Rotation(yaw, best.pitch), context.start.add(last), true, true);
    }

    /**
     * Last resort when no pitch reaches the path (e.g. the terrain changed and the goal is now walled off):
     * fly the pitch that stays collision-free the longest, biased towards climbing, so that the path
     * recalculation has time to catch up. If an impact is imminent and a firework would provably extend
     * survival, one is forced.
     */
    private Solution solveSurvival(final SolverContext context, final Solution unsolved) {
        if (landingMode) {
            return unsolved;
        }
        final float yaw = unsolved != null ? unsolved.rotation.getYaw() : ctx.playerRotations().getYaw();
        final int ticks = Math.max(5, Baritone.settings().elytraSimulationTicks.value);
        final int ticksBoosted = context.boost.isBoosted() ? Math.max(1, context.boost.getGuaranteedBoostTicks()) : 0;

        final PitchResult best = this.solveSurvivalPitch(context, yaw, ticks, ticksBoosted, 0);
        if (best == null) { // cancelled by the game thread
            return unsolved;
        }

        final int survivedTicks = best.steps.size() - 1;
        // No pitch keeps us clear for the whole horizon, or we are in lava: if a rocket lit now would provably
        // do better, light one. This used to wait until the impact was a dozen ticks out, which from the top of
        // a climb out of a hole meant falling most of the way back in before doing anything about it.
        final boolean impactAhead = survivedTicks < ticks;
        if ((impactAhead || context.ignoreLava) && !context.boost.isBoosted() && context.hasFireworks) {
            final PitchResult boosted = this.solveSurvivalPitch(context, yaw, ticks, 10, 2);
            if (boosted != null && (boosted.steps.size() > best.steps.size() + 2
                    || (context.ignoreLava && boosted.steps.size() >= best.steps.size()))) {
                final Vec3 last = boosted.steps.get(boosted.steps.size() - 1);
                this.simulationLine = boosted.steps;
                return new Solution(context, new Rotation(yaw, boosted.pitch), context.start.add(last), true, true);
            }
        }

        this.simulationLine = best.steps;
        return new Solution(context, new Rotation(yaw, best.pitch), null, false, false);
    }

    private PitchResult solveSurvivalPitch(final SolverContext context, final float yaw, final int ticks,
                                           final int ticksBoosted, final int ticksBoostDelay) {
        final float yawRadians = yaw * RotationUtils.DEG_TO_RAD_F;
        // a goal far along the direction we are about to face, so that the simulated yaw stays fixed
        final Vec3 direction = new Vec3(-Mth.sin(yawRadians) * 1e4, 0, Mth.cos(yawRadians) * 1e4);
        final float currentPitch = ctx.playerRotations().getPitch();

        PitchResult best = null;
        // scan the climbing side (negative pitch) first; new walls are usually escapable from above. With a
        // rocket burning, don't settle for the first pitch that survives: take the one that ends highest. The
        // boost is the one chance to get above whatever is boxing us in, and straight up is where that is most
        // likely to be clear (the hole we just took off from, a crevice, the hollow beside a lava pond).
        final boolean preferHeight = ticksBoosted > 0;
        for (float pitch = currentPitch; pitch >= -90; pitch -= 3) {
            if (Thread.interrupted()) return null;
            best = this.betterSurvival(context, direction, pitch, ticks, ticksBoosted, ticksBoostDelay, best);
            if (!preferHeight && best != null && best.steps.size() - 1 >= ticks) return best;
        }
        if (best != null && best.steps.size() - 1 >= ticks) return best;
        for (float pitch = currentPitch + 3; pitch <= 90; pitch += 3) {
            if (Thread.interrupted()) return null;
            best = this.betterSurvival(context, direction, pitch, ticks, ticksBoosted, ticksBoostDelay, best);
            if (best != null && best.steps.size() - 1 >= ticks) return best;
        }
        return best;
    }

    private PitchResult betterSurvival(final SolverContext context, final Vec3 direction, final float pitch,
                                       final int ticks, final int ticksBoosted, final int ticksBoostDelay,
                                       final PitchResult best) {
        final List<Vec3> steps = this.simulate(context, direction, pitch, ticks, ticksBoosted, ticksBoostDelay, true);
        // longer survival wins; on equal survival prefer the trajectory that ends higher up
        final double score = (steps.size() - 1) * 1000.0 + steps.get(steps.size() - 1).y;
        return best == null || score > best.dot ? new PitchResult(pitch, score, steps) : best;
    }

    private void tickUseFireworks(final Vec3 start, final Vec3 goingTo, final boolean isBoosted, final boolean forceUseFirework, final boolean inLava) {
        // neither the setback delay nor a landing in progress is a reason to keep burning
        if (this.remainingSetBackTicks > 0 && !inLava) {
            logDebug("waiting for elytraFireworkSetbackUseDelay: " + this.remainingSetBackTicks);
            return;
        }
        if (this.landingMode && !inLava) {
            return;
        }
        final boolean useOnDescend = !Baritone.settings().elytraConserveFireworks.value || ctx.player().position().y < goingTo.y + 5;
        final double currentSpeed = new Vec3(
                ctx.player().getDeltaMovement().x,
                // ignore y component if we are BOTH below where we want to be AND descending
                ctx.player().position().y < goingTo.y ? Math.max(0, ctx.player().getDeltaMovement().y) : ctx.player().getDeltaMovement().y,
                ctx.player().getDeltaMovement().z
        ).lengthSqr();

        final double elytraFireworkSpeed = Baritone.settings().elytraFireworkSpeed.value;
        if (this.remainingFireworkTicks <= 0 && (forceUseFirework || (!isBoosted
                && useOnDescend
                && (ctx.player().position().y < goingTo.y - 5 || start.distanceTo(new Vec3(goingTo.x + 0.5, ctx.player().position().y, goingTo.z + 0.5)) > 5) // UGH!!!!!!!
                && currentSpeed < elytraFireworkSpeed * elytraFireworkSpeed))
        ) {
            logVerbose("attempting to use firework" + (forceUseFirework ? " (forced)" : ""));
            this.useFirework();
        }
    }

    /**
     * Lights a firework to get off the ground, for an elytra that was opened from a standing jump instead of a
     * fall. There is no speed and only a couple of blocks of air to work with, so this can't wait for the solver
     * to decide that a boost is warranted.
     *
     * @return {@code true} if a firework is now burning, {@code false} if we have none to use
     */
    public boolean useFireworkForTakeoff() {
        if (this.remainingFireworkTicks > 0 || this.getAttachedFirework().isPresent()) {
            return true;
        }
        return this.useFirework();
    }

    /**
     * @return {@code true} if a firework was used
     */
    private boolean useFirework() {
        // the main hand is what processRightClick uses, and selectFirework settles for putting something harmless
        // there when the only firework we have is in the off hand, which would right click nothing at all
        if (!this.selectFirework() || !isFireworks(ctx.player().getItemInHand(InteractionHand.MAIN_HAND))) {
            logDirect("no fireworks");
            return false;
        }
        // The use packet carries a look of its own, read off the player as it is built, and the server-side
        // movement check compares it with the look in this tick's movement packet: the rotation LookBehavior
        // is about to apply for the solver's target, not what the player is facing right now. Face that for
        // the duration of the click so the two agree; the look behavior sets or restores the real rotation
        // later in the tick regardless.
        final Rotation wire = this.baritone.getLookBehavior().getRotationForThisTick();
        final float yaw = ctx.player().getYRot();
        final float pitch = ctx.player().getXRot();
        ctx.player().setYRot(wire.getYaw());
        ctx.player().setXRot(wire.getPitch());
        try {
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
        } finally {
            ctx.player().setYRot(yaw);
            ctx.player().setXRot(pitch);
        }
        this.minimumBoostTicks = 10 * (1 + getFireworkBoost(ctx.player().getItemInHand(InteractionHand.MAIN_HAND)).orElse(0));
        this.remainingFireworkTicks = FIREWORK_COOLDOWN_TICKS;
        this.fireworkSeenSinceLit = false;
        return true;
    }

    /**
     * Puts a firework in the main hand. Picking one that is already in the hotbar is instant, but bringing one up
     * from the rest of the inventory is a window click, and those only happen while stationary, so a takeoff has
     * to call this before leaving the ground.
     *
     * @return {@code true} if the main hand now holds a firework
     */
    public boolean selectFirework() {
        // Prioritize boosting fireworks over regular ones
        // TODO: Take the minimum boost time into account?
        return baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isBoostingFireworks)
                || baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isFireworks);
    }

    private final class SolverContext {

        public final NetherPath path;
        public final int playerNear;
        public final Vec3 start;
        public final Vec3 motion;
        public final AABB boundingBox;
        public final boolean ignoreLava;
        public final double gravity;
        public final boolean slowFalling;
        public final boolean hasFireworks;
        public final FireworkBoost boost;
        public final IAimProcessor aimProcessor;

        /**
         * Creates a new SolverContext using the current state of the path, player, and firework boost at the time of
         * construction.
         *
         * @param async Whether the computation is being done asynchronously at the end of a game tick.
         */
        public SolverContext(boolean async) {
            this.path = ElytraBehavior.this.pathManager.getPath();
            this.playerNear = ElytraBehavior.this.pathManager.getNear();

            this.start = ctx.playerFeetAsVec();
            this.motion = ctx.playerMotion();
            this.boundingBox = ctx.player().getBoundingBox();
            this.ignoreLava = ctx.player().isInLava();
            this.gravity = ctx.player().getAttributeValue(Attributes.GRAVITY);
            this.slowFalling = ctx.player().hasEffect(MobEffects.SLOW_FALLING);
            boolean fireworks = false;
            for (ItemStack stack : ctx.player().getInventory().getNonEquipmentItems()) {
                if (isFireworks(stack)) {
                    fireworks = true;
                    break;
                }
            }
            this.hasFireworks = fireworks;

            Integer fireworkTicksExisted = ElytraBehavior.this.getAttachedFirework().map(e -> e.tickCount).orElse(null);
            if (fireworkTicksExisted == null && ElytraBehavior.this.remainingFireworkTicks > 0) {
                // A rocket we lit within the cooldown whose entity the server hasn't shown us yet: a round trip,
                // which on a busy server is several ticks. Solve as though it had been burning since we lit it.
                // The boost follows whatever we aim at once it arrives, and planning an unboosted trajectory in
                // the meantime - a shallow glide, since without thrust nothing else gets anywhere - is what
                // points a takeoff rocket at the nearest wall. If it never shows up the assumption expires with
                // the cooldown. The end-of-tick solve is for the next tick, whose cooldown is one lower.
                fireworkTicksExisted = FIREWORK_COOLDOWN_TICKS - ElytraBehavior.this.remainingFireworkTicks + (async ? 1 : 0);
            }
            this.boost = new FireworkBoost(fireworkTicksExisted, ElytraBehavior.this.minimumBoostTicks);

            ITickableAimProcessor aim = ElytraBehavior.this.baritone.getLookBehavior().getAimProcessor().fork();
            if (async) {
                // async computation is done at the end of a tick, advance by 1 to prepare for the next tick
                aim.advance(1);
            }
            this.aimProcessor = aim;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != SolverContext.class) {
                return false;
            }

            SolverContext other = (SolverContext) o;
            return this.path == other.path  // Contents aren't modified, just compare by reference
                    && this.playerNear == other.playerNear
                    && Objects.equals(this.start, other.start)
                    && Objects.equals(this.motion, other.motion)
                    && Objects.equals(this.boundingBox, other.boundingBox)
                    && this.ignoreLava == other.ignoreLava
                    && this.gravity == other.gravity
                    && this.slowFalling == other.slowFalling
                    && this.hasFireworks == other.hasFireworks
                    && Objects.equals(this.boost, other.boost);
        }
    }

    private static final class FireworkBoost {

        private final Integer fireworkTicksExisted;
        private final int minimumBoostTicks;
        private final int maximumBoostTicks;

        /**
         * @param fireworkTicksExisted The ticksExisted of the attached firework entity, or {@code null} if no entity.
         * @param minimumBoostTicks    The minimum number of boost ticks that the attached firework entity, if any, will
         *                             provide.
         */
        public FireworkBoost(final Integer fireworkTicksExisted, final int minimumBoostTicks) {
            this.fireworkTicksExisted = fireworkTicksExisted;

            // A client holding the rocket alive past its lifetime keeps pushing for this much longer, and
            // both ends of the window move with it: those extra ticks are as guaranteed as the rest, being
            // held deliberately rather than rolled for.
            final int extra = Math.max(0, Baritone.settings().elytraFireworkExtraBoostTicks.value);
            // this.lifetime = 10 * i + this.rand.nextInt(6) + this.rand.nextInt(7);
            this.minimumBoostTicks = minimumBoostTicks + extra;
            this.maximumBoostTicks = this.minimumBoostTicks + 11;
        }

        public boolean isBoosted() {
            return this.fireworkTicksExisted != null;
        }

        /**
         * @return The guaranteed number of remaining ticks with boost
         */
        public int getGuaranteedBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.minimumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        /**
         * @return The maximum number of remaining ticks with boost
         */
        public int getMaximumBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.maximumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != FireworkBoost.class) {
                return false;
            }

            FireworkBoost other = (FireworkBoost) o;
            if (!this.isBoosted() && !other.isBoosted()) {
                return true;
            }

            return Objects.equals(this.fireworkTicksExisted, other.fireworkTicksExisted)
                    && this.minimumBoostTicks == other.minimumBoostTicks
                    && this.maximumBoostTicks == other.maximumBoostTicks;
        }
    }

    private static final class PitchResult {

        public final float pitch;
        public final double dot;
        public final List<Vec3> steps;

        public PitchResult(float pitch, double dot, List<Vec3> steps) {
            this.pitch = pitch;
            this.dot = dot;
            this.steps = steps;
        }
    }

    private static final class Solution {

        public final SolverContext context;
        public final Rotation rotation;
        public final Vec3 goingTo;
        public final boolean solvedPitch;
        public final boolean forceUseFirework;

        public Solution(SolverContext context, Rotation rotation, Vec3 goingTo, boolean solvedPitch, boolean forceUseFirework) {
            this.context = context;
            this.rotation = rotation;
            this.goingTo = goingTo;
            this.solvedPitch = solvedPitch;
            this.forceUseFirework = forceUseFirework;
        }
    }

    public static boolean isFireworks(final ItemStack itemStack) {
        if (itemStack.getItem() != Items.FIREWORK_ROCKET) {
            return false;
        }
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        return fw != null && fw.explosions().isEmpty();
    }

    private static boolean isBoostingFireworks(final ItemStack itemStack) {
        return getFireworkBoost(itemStack).isPresent();
    }

    private static OptionalInt getFireworkBoost(final ItemStack itemStack) {
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        if (fw != null && fw.explosions().isEmpty()) {
            return OptionalInt.of(fw.flightDuration());
        }
        return OptionalInt.empty();
    }

    private Optional<FireworkRocketEntity> getAttachedFirework() {
        return ctx.entitiesStream()
                .filter(x -> x instanceof FireworkRocketEntity)
                .filter(x -> Objects.equals(((IFireworkRocketEntity) x).getBoostedEntity(), ctx.player()))
                .map(x -> (FireworkRocketEntity) x)
                .findFirst();
    }

    private boolean isHitboxClear(final SolverContext context, final Vec3 dest, final Double growAmount) {
        final Vec3 start = context.start;
        final boolean ignoreLava = context.ignoreLava;

        if (!this.clearView(start, dest, ignoreLava)) {
            return false;
        }
        if (growAmount == null) {
            return true;
        }

        final AABB bb = context.boundingBox.inflate(growAmount);

        final double ox = dest.x - start.x;
        final double oy = dest.y - start.y;
        final double oz = dest.z - start.z;

        final double[] src = new double[]{
                bb.minX, bb.minY, bb.minZ,
                bb.minX, bb.minY, bb.maxZ,
                bb.minX, bb.maxY, bb.minZ,
                bb.minX, bb.maxY, bb.maxZ,
                bb.maxX, bb.minY, bb.minZ,
                bb.maxX, bb.minY, bb.maxZ,
                bb.maxX, bb.maxY, bb.minZ,
                bb.maxX, bb.maxY, bb.maxZ,
        };
        final double[] dst = new double[]{
                bb.minX + ox, bb.minY + oy, bb.minZ + oz,
                bb.minX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.maxZ + oz,
        };

        // Use non-batching method without early failure
        if (Baritone.settings().elytraRenderHitboxRaytraces.value) {
            boolean clear = true;
            for (int i = 0; i < 8; i++) {
                final Vec3 s = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
                final Vec3 d = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
                // Don't forward ignoreLava since the batch call doesn't care about it
                if (!this.clearView(s, d, false)) {
                    clear = false;
                }
            }
            return clear;
        }

        return this.context.raytrace(8, src, dst, NetherPathfinderContext.Visibility.ALL);
    }

    public boolean clearView(Vec3 start, Vec3 dest, boolean ignoreLava) {
        final boolean clear;
        if (!ignoreLava) {
            // if start == dest then the cpp raytracer dies
            clear = start.equals(dest) || this.context.raytrace(start, dest);
        } else {
            clear = ctx.world().clip(new ClipContext(start, dest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS;
        }

        if (Baritone.settings().elytraRenderRaytraces.value) {
            (clear ? this.clearLines : this.blockedLines).add(new Pair<>(start, dest));
        }
        return clear;
    }

    private static FloatArrayList pitchesToSolveFor(final float goodPitch, final boolean desperate) {
        final float minPitch = desperate ? -90 : Math.max(goodPitch - Baritone.settings().elytraPitchRange.value, -89);
        final float maxPitch = desperate ? 90 : Math.min(goodPitch + Baritone.settings().elytraPitchRange.value, 89);

        final FloatArrayList pitchValues = new FloatArrayList(fastCeil(maxPitch - minPitch) + 1);
        for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
            pitchValues.add(pitch);
        }
        for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
            pitchValues.add(pitch);
        }

        return pitchValues;
    }

    @FunctionalInterface
    private interface IntTriFunction<T> {
        T apply(int first, int second, int third);
    }

    private static final class IntTriple {
        public final int first;
        public final int second;
        public final int third;

        public IntTriple(int first, int second, int third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    private Pair<Float, Boolean> solvePitch(final SolverContext context, final Vec3 goal, final int relaxation) {
        final boolean desperate = relaxation == 2;
        final float goodPitch = RotationUtils.calcRotationFromVec3d(context.start, goal, ctx.playerRotations()).getPitch();
        final FloatArrayList pitches = pitchesToSolveFor(goodPitch, desperate);

        final IntTriFunction<PitchResult> solve = (ticks, ticksBoosted, ticksBoostDelay) ->
                this.solvePitch(context, goal, relaxation, pitches.iterator(), ticks, ticksBoosted, ticksBoostDelay);

        final List<IntTriple> tests = new ArrayList<>();

        if (context.boost.isBoosted()) {
            final int guaranteed = context.boost.getGuaranteedBoostTicks();
            if (guaranteed == 0) {
                // uncertain when boost will run out
                final int lookahead = Math.max(4, 10 - context.boost.getMaximumBoostTicks());
                tests.add(new IntTriple(lookahead, 1, 0));
            } else if (guaranteed <= 5) {
                // boost will run out within 5 ticks
                tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
            } else {
                // there's plenty of guaranteed boost
                tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
            }
        }

        // Standard test, assume (not) boosted for entire duration
        final int ticks = desperate ? 3 : context.boost.isBoosted() ? Math.max(5, context.boost.getGuaranteedBoostTicks()) : Baritone.settings().elytraSimulationTicks.value;
        tests.add(new IntTriple(ticks, context.boost.isBoosted() ? ticks : 0, 0));

        final Optional<PitchResult> result = tests.stream()
                .map(i -> solve.apply(i.first, i.second, i.third))
                .filter(Objects::nonNull)
                .findFirst();
        if (result.isPresent()) {
            return new Pair<>(result.get().pitch, false);
        }

        // If we used a firework would we be able to get out of the current situation??? perhaps
        if (desperate) {
            final List<IntTriple> testsBoost = new ArrayList<>();
            testsBoost.add(new IntTriple(ticks, 10, 3));
            testsBoost.add(new IntTriple(ticks, 10, 2));
            testsBoost.add(new IntTriple(ticks, 10, 1));

            final Optional<PitchResult> resultBoost = testsBoost.stream()
                    .map(i -> solve.apply(i.first, i.second, i.third))
                    .filter(Objects::nonNull)
                    .findFirst();
            if (resultBoost.isPresent()) {
                return new Pair<>(resultBoost.get().pitch, true);
            }
        }

        return null;
    }

    private PitchResult solvePitch(final SolverContext context, final Vec3 goal, final int relaxation,
                                   final FloatIterator pitches, final int ticks, final int ticksBoosted,
                                   final int ticksBoostDelay) {
        // we are at a certain velocity, but we have a target velocity
        // what pitch would get us closest to our target velocity?
        // yaw is easy so we only care about pitch

        final Vec3 goalDelta = goal.subtract(context.start);
        final Vec3 goalDirection = goalDelta.normalize();

        final Deque<PitchResult> bestResults = new ArrayDeque<>();

        while (pitches.hasNext()) {
            final float pitch = pitches.nextFloat();
            final List<Vec3> displacement = this.simulate(
                    context,
                    goalDelta,
                    pitch,
                    ticks,
                    ticksBoosted,
                    ticksBoostDelay,
                    false
            );
            if (displacement == null) {
                continue;
            }
            final Vec3 last = displacement.get(displacement.size() - 1);
            double goodness = goalDirection.dot(last.normalize());
            if (landingMode) {
                goodness = -goalDelta.subtract(last).length();
            }
            final PitchResult bestSoFar = bestResults.peek();
            if (bestSoFar == null || goodness > bestSoFar.dot) {
                bestResults.push(new PitchResult(pitch, goodness, displacement));
            }
        }

        outer:
        for (final PitchResult result : bestResults) {
            if (relaxation < 2) {
                // Ensure that the goal is visible along the entire simulated path
                // Reverse order iteration since the last position is most likely to fail
                for (int i = result.steps.size() - 1; i >= 1; i--) {
                    if (!clearView(context.start.add(result.steps.get(i)), goal, context.ignoreLava)) {
                        continue outer;
                    }
                }
            } else {
                // Ensure that the goal is visible from the final position
                if (!clearView(context.start.add(result.steps.get(result.steps.size() - 1)), goal, context.ignoreLava)) {
                    continue;
                }
            }

            this.simulationLine = result.steps;
            return result;
        }
        return null;
    }

    private List<Vec3> simulate(final SolverContext context, final Vec3 goalDelta, final float pitch, final int ticks,
                                final int ticksBoosted, final int ticksBoostDelay, final boolean stopOnImpact) {
        final ITickableAimProcessor aimProcessor = context.aimProcessor.fork();
        Vec3 delta = goalDelta;
        Vec3 motion = context.motion;
        AABB hitbox = context.boundingBox;
        List<Vec3> displacement = new ArrayList<>(ticks + 1);
        displacement.add(Vec3.ZERO);
        int remainingTicksBoosted = ticksBoosted;

        for (int i = 0; i < ticks; i++) {
            final double cx = hitbox.minX + (hitbox.maxX - hitbox.minX) * 0.5D;
            final double cz = hitbox.minZ + (hitbox.maxZ - hitbox.minZ) * 0.5D;
            if (delta.lengthSqr() < 1) {
                break;
            }
            final Rotation rotation = aimProcessor.nextRotation(
                    RotationUtils.calcRotationFromVec3d(Vec3.ZERO, delta, ctx.playerRotations()).withPitch(pitch)
            );
            final Vec3 lookDirection = RotationUtils.calcLookDirectionFromRotation(rotation);

            motion = step(motion, lookDirection, rotation.getPitch(), context.gravity, context.slowFalling);
            delta = delta.subtract(motion);

            // Swept collision box: grow the hitbox ONLY in the direction of travel, plus a hair of
            // padding. The old inflate(motion) grew symmetrically, so it also reached backwards and
            // downwards into the block behind/under the player — which at takeoff is the ground, and
            // made the solver see a collision that was not on the flight path and report "no pitch
            // solution" (upstream #5047/#5052, issue #5094). expandTowards is the vanilla swept volume.
            final AABB inMotion = hitbox.expandTowards(motion.x, motion.y, motion.z).inflate(0.01);

            int xmin = fastFloor(inMotion.minX);
            int xmax = fastCeil(inMotion.maxX);
            int ymin = fastFloor(inMotion.minY);
            int ymax = fastCeil(inMotion.maxY);
            int zmin = fastFloor(inMotion.minZ);
            int zmax = fastCeil(inMotion.maxZ);
            for (int x = xmin; x < xmax; x++) {
                for (int y = ymin; y < ymax; y++) {
                    for (int z = zmin; z < zmax; z++) {
                        if (!this.passable(x, y, z, context.ignoreLava)) {
                            // displacement only covers the ticks before the impact
                            return stopOnImpact ? displacement : null;
                        }
                    }
                }
            }

            hitbox = hitbox.move(motion);
            displacement.add(displacement.get(displacement.size() - 1).add(motion));

            if (i >= ticksBoostDelay && remainingTicksBoosted-- > 0) {
                // See EntityFireworkRocket. The 1.5 is vanilla's, and a client that boosts harder than that
                // flies a faster trajectory than the one this loop raytraced, into blocks the raytrace never
                // looked at - so it is a setting, to be told the truth by whatever changes it client side.
                final double boostSpeed = Baritone.settings().elytraFireworkBoostMultiplier.value;
                motion = motion.add(
                        lookDirection.x * 0.1 + (lookDirection.x * boostSpeed - motion.x) * 0.5,
                        lookDirection.y * 0.1 + (lookDirection.y * boostSpeed - motion.y) * 0.5,
                        lookDirection.z * 0.1 + (lookDirection.z * boostSpeed - motion.z) * 0.5
                );
            }
        }

        return displacement;
    }

    /**
     * A faithful reimplementation of {@code LivingEntity.updateFallFlyingMovement}. Every constant, guard and
     * rounding step is kept identical to vanilla so that a simulated trajectory matches what the server will
     * actually compute; any divergence here compounds over the simulated horizon and puts the bot into walls.
     *
     * @param gravity     The player's {@code Attributes.GRAVITY} value, which is not always the 0.08 default
     * @param slowFalling Whether the player has Slow Falling, which caps gravity while not ascending
     */
    private static Vec3 step(final Vec3 motion, final Vec3 lookDirection, final float pitch,
                             final double gravity, final boolean slowFalling) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;

        float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
        double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        // vanilla uses the exact Math.cos here and keeps the square in double precision, unlike the Mth table
        double pitchBase3 = Mth.square(Math.cos((double) pitchRadians));
        // LivingEntity.getEffectiveGravity
        double effectiveGravity = slowFalling && motionY <= 0 ? Math.min(gravity, 0.01) : gravity;

        motionY += effectiveGravity * (-1.0 + pitchBase3 * 0.75);
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * pitchBase3;
            motionY += speedModifier;
            motionX += lookDirection.x * speedModifier / pitchBase2;
            motionZ += lookDirection.z * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0 && pitchBase2 > 0) { // if you are looking down (below level)
            double anotherSpeedModifier = flatMotion * (double) (-Mth.sin(pitchRadians)) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
            motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) { // this is always true unless you are looking literally straight up (let's just say the bot will never do that)
            motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.9900000095367432;
        motionY *= 0.9800000190734863;
        motionZ *= 0.9900000095367432;

        return new Vec3(motionX, motionY, motionZ);
    }

    private boolean passable(int x, int y, int z, boolean ignoreLava) {
        if (ignoreLava) {
            final BlockState state = this.bsi.get0(x, y, z);
            return state.getBlock() instanceof AirBlock || MovementHelper.isLava(state);
        } else {
            return !this.boi.get0(x, y, z);
        }
    }

    private void tickInventoryTransactions() {
        if (invTickCountdown <= 0) {
            Runnable r = invTransactionQueue.poll();
            if (r != null) {
                r.run();
                invTickCountdown = Baritone.settings().ticksBetweenInventoryMoves.value;
            }
        }
        if (invTickCountdown > 0) invTickCountdown--;
    }

    private void queueWindowClick(int windowId, int slotId, int button, ClickType type) {
        invTransactionQueue.add(() -> ctx.playerController().windowClick(windowId, slotId, button, type, ctx.player()));
    }

    private int findGoodElytra() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            ItemStack slot = invy.get(i);
            if (slot.getItem() == Items.ELYTRA && (slot.getMaxDamage() - slot.getDamageValue()) > Baritone.settings().elytraMinimumDurability.value) {
                return i;
            }
        }
        return -1;
    }

    private void trySwapElytra() {
        if (!Baritone.settings().elytraAutoSwap.value || !invTransactionQueue.isEmpty()) {
            return;
        }

        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA
                || chest.getMaxDamage() - chest.getDamageValue() > Baritone.settings().elytraMinimumDurability.value) {
            return;
        }

        int goodElytraSlot = findGoodElytra();
        if (goodElytraSlot != -1) {
            final int CHEST_SLOT = 6;
            final int slotId = goodElytraSlot < 9 ? goodElytraSlot + 36 : goodElytraSlot;
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ClickType.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, CHEST_SLOT, 0, ClickType.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ClickType.PICKUP);
        }
    }

    void logVerbose(String message) {
        if (Baritone.settings().elytraChatSpam.value) {
            logDebug(message);
        }
    }
}
