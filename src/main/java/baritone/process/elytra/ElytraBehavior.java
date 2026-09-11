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
import baritone.api.event.events.type.EventState;
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
     * A second native context mirroring {@link #context}, except every chunk outside the corridor pushed via
     * {@link ElytraProcess#setPathCorridor} is inserted as solid, so a search in it cannot leave the corridor.
     * Only the path search uses it; raytraces and {@link #passable} stay on {@link #context}.
     */
    final NetherPathfinderContext corridorContext;
    /** Chunks currently masked solid in {@link #corridorContext} for being outside the corridor. Game thread only. */
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
     * How far from where it was asked to start a path may begin before {@link PathManager#noteStart} writes it
     * down: past one neighbouring 4-block node, which is as far as an ordinary search ever moves a start.
     */
    private static final int START_MOVED_NOTE_BLOCKS = 6;
    /**
     * How long a stretch without a pitch solution has to last before the flight record mentions it. Shorter ones
     * come and go every few ticks in a tight passage and say nothing.
     */
    private static final int NO_PITCH_NOTE_TICKS = 5;
    /** How often, at most, the flight record notes a recalculation from our own feet that started elsewhere. */
    private static final int RECALC_NOTE_INTERVAL_TICKS = 40;
    /** Yaw offsets {@link #solveSurvival} tries, smallest first, once the current heading can't clear the whole simulated horizon. */
    private static final float[] YAW_RESCUE_OFFSETS = {30f, 60f, 90f};
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
    /** Consecutive flight ticks the solver has had no pitch solution for; see {@link #tick()}. */
    private int noPitchTicks;
    /**
     * The yaw offset last logged as adopted by the survival rescue, or 0 while flying straight. Latched so the
     * log gets one line per turning episode, not one per tick; cleared alongside {@link #noPitchTicks}.
     */
    private float lastLoggedYawOffset;
    /** Whether the flight record already says the hotbar is out of rockets; cleared by the next one lit. */
    private boolean noFireworksNoted;

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

        // Tracks a segment recompute that failed at the same resume node, so the next tick restarts
        // the search from the player instead of retrying the same unreachable node.
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
            return this.pathOnwards(from, UnaryOperator.identity())
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
                                FlightLog.log("path: no path to the destination from " + from);
                            } else {
                                logUnhandledException(cause);
                            }
                        } else {
                            this.noteStart("path", new BetterBlockPos(from));
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
            final BetterBlockPos from = ctx.playerFeet();

            final UnaryOperator<UnpackedSegment> rejoin = segment -> {
                if (!upToIncl.isPresent() || !segment.isFinished()) {
                    // Either aimed onwards and speaks for itself, or the search ran out of time short of the
                    // rejoin node - tacking the old tail onto an unfinished segment would leave a blind jump
                    // through whatever lies between. Leave it unfinished and continue from where it got to.
                    return segment;
                }
                return segment.append(after.stream(), complete);
            };
            return (upToIncl.isPresent()
                    ? this.path0(from, this.path.get(upToIncl.getAsInt()), rejoin)
                    : this.pathOnwards(from, rejoin))
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
                        } else {
                            this.noteStart("recalc", from);
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

            this.pathOnwards(pathStart, segment -> segment.prepend(before.stream()))
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
                                // This callback runs off the main thread (NetherPathfinderContext's own
                                // executor): nothing here may touch ctx.playerFeet(), ctx.world(), or
                                // pathRecalcSegment. repackNearPlayer hops to the main thread itself; the
                                // rest just sets fields, read back next tick in attemptNextSegment.
                                repackNearPlayer("next segment computation failed");
                                if (pathStart.distanceSq(ElytraBehavior.this.destination) <= 48 * 48) {
                                    // Same radius the landing-spot search uses for "close enough": a resumed
                                    // search failing this close to the destination means there's nothing left
                                    // to compute, not a stuck resume point.
                                    logDirect("Failed to compute next segment");
                                    logVerbose("Player is near the segment start, therefore repeating this calculation is pointless. Marking as complete");
                                    completePath = true;
                                } else {
                                    // One line per distinct failing node, not per tick.
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
                                    // hasChunk/passable need the main thread; this just flags a fresh
                                    // failure for attemptNextSegment to log next tick.
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

        /** The last start {@link #noteStart} wrote down, so a search repeated every tick is written once. */
        private String lastStartNote = "";
        private int lastRecalcNoteTick = -RECALC_NOTE_INTERVAL_TICKS;

        /**
         * Writes down a search whose path came back starting somewhere other than asked: the native search
         * moved it to the nearest known air, so the pathfinder's picture of the terrain disagrees with the
         * client's there. Reads the client world only, since it can overlap the solver's own thread, and must
         * never throw - it sits inside a future a takeoff waits on.
         */
        private void noteStart(final String why, final BetterBlockPos from) {
            if (!FlightLog.enabled() || this.path.isEmpty()) {
                return;
            }
            try {
                final BetterBlockPos first = this.path.get(0);
                final double off = Math.sqrt(first.distanceSq(from));
                if (off <= START_MOVED_NOTE_BLOCKS) {
                    return;
                }
                final String key = why + from + first;
                final boolean recalc = "recalc".equals(why);
                final int now = ctx.player().tickCount;
                if (key.equals(this.lastStartNote) || recalc && now >= this.lastRecalcNoteTick
                        && now - this.lastRecalcNoteTick < RECALC_NOTE_INTERVAL_TICKS) {
                    return;
                }
                this.lastStartNote = key;
                if (recalc) {
                    this.lastRecalcNoteTick = now;
                }
                FlightLog.log(String.format(Locale.ROOT,
                        "%s: asked to start at %d %d %d (%s there), the path starts at %d %d %d, %.1f blocks away",
                        why, from.x, from.y, from.z, ctx.world().getBlockState(from).getBlock(),
                        first.x, first.y, first.z, off));
            } catch (RuntimeException ignored) {
                // a line of log is not worth a path
            }
        }

        // mickey resigned
        private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            return this.search(src, dst)
                    .thenApply(UnpackedSegment::from)
                    .thenApply(operator)
                    .thenAcceptAsync(this::setPath, ctx.minecraft()::execute);
        }

        /**
         * {@link #path0} aimed onwards: at the destination, or at the end of the next leg when the destination is
         * farther off than a leg (see {@link #legTarget}). Reaching a leg's end does not finish the path, so that
         * segment is handed on unfinished, same as a search that ran out before the destination.
         */
        private CompletableFuture<Void> pathOnwards(BlockPos src, UnaryOperator<UnpackedSegment> operator) {
            final BetterBlockPos leg = this.legTarget(src);
            if (leg == null) {
                return this.path0(src, ElytraBehavior.this.destination, operator);
            }
            return this.path0(src, leg, segment -> operator.apply(segment.unfinished()));
        }

        /**
         * The end of the leg a search from {@code from} aims at: a point on the line to the destination, at least
         * {@link Settings#elytraPathLegLength} blocks along it, pushed past the loaded chunks so the native search
         * doesn't snap it to the nearest air short of there (a wall face, inside loaded terrain). {@code null} when
         * the search should aim at the destination directly (no more than a leg away, legs off, landing/corridor).
         * <p>
         * The native search only steps along six axes and is drawn by straight-line distance, so aimed far away it
         * rides one axis and only turns once the destination sits at 45 degrees; a leg ahead bounds that drift to
         * about a fifth of the leg instead.
         */
        private BetterBlockPos legTarget(final BlockPos from) {
            final int leg = Baritone.settings().elytraPathLegLength.value;
            final boolean corridor = ElytraBehavior.this.corridorContext != null && process.hasCorridor()
                    && Baritone.settings().elytraCorridor.value;
            if (leg <= 0 || ElytraBehavior.this.appendDestination || corridor) {
                return null;
            }
            final BetterBlockPos dest = ElytraBehavior.this.destination;
            final double dx = dest.x - from.getX();
            final double dz = dest.z - from.getZ();
            final double distance = Math.sqrt(dx * dx + dz * dz);
            // in chunk-sized steps, out to well past the farthest a client loads; all of that loaded, and it is as if
            // the destination were no further
            for (double along = leg; along + 16 < distance && along <= leg + 1024; along += 16) {
                // both the sample and the point handed back out of the loaded chunks: the loaded area is made of
                // whole chunks and fills in unevenly at its edge, so one sample out of it does not put the next out
                final BetterBlockPos end = pointAlong(from, dest, (along + 16) / distance);
                if (!ctx.world().isLoaded(pointAlong(from, dest, along / distance)) && !ctx.world().isLoaded(end)) {
                    return end;
                }
            }
            return null;
        }

        /** The point {@code fraction} of the way from {@code from} to {@code dest}, at a height the native search accepts. */
        private static BetterBlockPos pointAlong(final BlockPos from, final BetterBlockPos dest, final double fraction) {
            return new BetterBlockPos(
                    from.getX() + (int) Math.round((dest.x - from.getX()) * fraction),
                    Mth.clamp(from.getY() + (int) Math.round((dest.y - from.getY()) * fraction), 0, 127),
                    from.getZ() + (int) Math.round((dest.z - from.getZ()) * fraction)
            );
        }

        /**
         * The search behind every path computation: inside the corridor first when one is set, the full map
         * otherwise or when the corridor has no way through. Called on the game thread, which {@link #corridorAdmit}
         * relies on. A failure reaches callers' {@code whenComplete} the same way a plain {@code pathFindAsync}
         * failure does: as a {@link CompletionException} wrapping a {@link PathCalculationException}.
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
         * One search in one context: wide nodes first if asked, retried with fine nodes when that comes back a
         * stub - or fails outright, e.g. a start node with any rock in it, the ordinary case off a crevice or a
         * fungus - and adaptive is on. A stub from the fine search is returned as-is; the caller decides whether
         * to try another context or fly it.
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
                                process.countDetourRetry(false);
                                return fineSearch(where, label, src, dst).thenApply(fine -> {
                                    // Only take the fine path if it is genuinely straighter over the same
                                    // journey: a fine search that gave up early scores near-zero for having
                                    // gone nowhere, which must not beat a wide search that actually finished.
                                    final double narrow = detourRatio(src, fine);
                                    final boolean comparable = narrow > 0
                                            && !isStub(src, fine)
                                            && (fine.finished || !segment.finished)
                                            // two unfinished searches measure two different journeys; the
                                            // fine one only counts if it got at least as close to the target
                                            && (fine.finished || lastNodeDistSq(fine, dst) <= lastNodeDistSq(segment, dst));
                                    if (comparable && narrow < wide) {
                                        logVerbose(String.format("path: %s x2 is straighter (%.2fx), taking it", label, narrow));
                                        process.countDetourRetry(true);
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
         * How far the segment travels per block of progress, as a multiple of the straight line to where it
         * ended up ({@code 1.0} straight, {@code 2.0} twice as far as it got). Tells a detour from a route: a
         * 4-block search routes around anything narrower than that, a whole massif sometimes, as a successful
         * finished search the stub test never catches. {@code 0} for a segment too short to judge.
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
         * The native search never returns null for "no way through" - it hands back its best node, unfinished -
         * so near the start that means boxed in, worth retrying finer or on the full map; far from the start it
         * is an ordinary cutoff at the edge of loaded terrain, worth flying.
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
                // On the ground the path isn't being flown - it was computed on purpose by a takeoff state
                // (the exit cube above a hole, so the takeoff rocket leaves along a clear line). Recomputing
                // it from our feet here would replace it with one starting inside whatever we're standing in.
                // Resume once flying.
                return;
            }

            if (process.inTakeoffGrace()) {
                // The path was computed by the takeoff from a cube chosen so the first boost leaves along
                // something clear. For the first tick or two after the elytra opens the feet are still in the
                // hole, so recomputing here would replace it with one aimed out of the hole - exactly what the
                // takeoff picked the cube to avoid.
                return;
            }

            if (ctx.player().isInLava()) {
                // solveAngles hands lava entirely to solveLavaEscape, which only takes a yaw from the path.
                // Every node's view is blocked by definition from inside lava, so the checks below would spend
                // a full native search every tick for nothing. Resume once out.
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
                // Off the main thread the failure handler couldn't check whether the resume point's chunk
                // had arrived or whether the point itself is passable; logged once per failure here instead.
                this.segmentDiagnosticsPending = false;
                BetterBlockPos p = this.failedSegmentStart;
                logVerbose(String.format(
                        "Segment failure diagnostics: hasChunk=%b passable=%b",
                        ElytraBehavior.this.context.hasChunk(new ChunkPos(p)),
                        ElytraBehavior.this.passable(p.x, p.y, p.z, false)
                ));
            }

            if (this.restartFromPlayer && ctx.player().isFallFlying()) {
                // Airborne, an open cube near the player is found almost at once since flying there at all
                // means it isn't buried like the stuck resume node. Grounded there's nothing better to search
                // from yet, so fall through to the ordinary retry below until flight resumes.
                this.restartFromPlayer = false;
                logVerbose("Next segment start unreachable, re-pathing from the player");
                this.pathRecalcSegment(OptionalInt.empty());
                return;
            }

            final int last = this.path.size() - 1;
            if (this.path.get(last).equals(this.failedSegmentStart)
                    && ctx.player().tickCount - this.failedSegmentTick < 40) {
                // Landed right back on the same stuck node moments ago; give the world a few ticks
                // instead of retrying instantly.
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
     * Re-reads the chunks around the player into the pathfinder's cache. The solver and obstacle checks run
     * only against that cache, so if it disagrees with the world the solver aims at terrain it thinks is air
     * and nothing here notices - the raytraces read the same cache.
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
     * Mirrors a chunk just packed into {@link #context} into {@link #corridorContext}: real terrain if inside
     * the corridor, solid if not. Called everywhere the main context is given a chunk.
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
     * Applies a corridor change to {@link #corridorContext}: chunks in {@code flipped} now inside get real
     * terrain (or air if the client doesn't have them) back, the ones now outside are masked solid, then
     * {@link #ringMask} runs. Game thread only.
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
     * in the corridor, loaded or not - the pathfinder treats a chunk it was never given as air, so without this
     * the search would leave the corridor through the unloaded fringe. Runs on every push and whenever the
     * player moves to another chunk between pushes.
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
     * Makes sure a corridor search can start from {@code src}: packs the loaded 3x3 chunks around it as real
     * terrain regardless of corridor membership, since the native search for an open start cube walks straight
     * through solid and would otherwise settle on some far cube past the mask. Transient: the next repack masks
     * them again if they are outside the corridor.
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
            // the event fires twice for every packet, before it is handled and after: count it once
            final boolean count = event.getState() == EventState.PRE;
            ctx.minecraft().execute(() -> {
                this.remainingSetBackTicks = Baritone.settings().elytraFireworkSetbackUseDelay.value;
                // counted here, on the game thread, with everything else the flight record keeps
                if (count) {
                    this.process.countSetback();
                }
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
            // Freeing on the game thread orders it behind any path result already queued there, and with
            // both executors drained nothing else can be inside a native call.
            ctx.minecraft().execute(this.context::free);
        }
        // Otherwise a search is still wedged in native code: freeing under it would be a use-after-free, so
        // the context is abandoned instead. The leak is bounded to one context per wedged search.
        if (this.corridorContext != null && this.corridorContext.shutdown()) {
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
                FlightLog.log("rocket: the one lit " + FIREWORK_COOLDOWN_TICKS + " ticks ago never showed up, the server ignored it");
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
            if (++this.noPitchTicks == NO_PITCH_NOTE_TICKS) {
                // Once per stretch, not per tick: a pinned glide sits in here for seconds, and the solver
                // lights no rocket while it does.
                final Vec3 pos = ctx.player().position();
                final NetherPath path = this.pathManager.getPath();
                final int near = Math.min(this.pathManager.getNear(), Math.max(0, path.size() - 1));
                FlightLog.log(String.format(Locale.ROOT,
                        "solver: for %d ticks no pitch has reached the path, now at %.1f %.1f %.1f (speed %.2f, nearest node %d of %d%s), flying yaw %.0f pitch %.0f to stay clear, and the solver lights no rocket until a pitch is found",
                        NO_PITCH_NOTE_TICKS, pos.x, pos.y, pos.z, ctx.player().getDeltaMovement().length(), near, path.size(),
                        path.isEmpty() ? "" : String.format(Locale.ROOT, ", %.1f blocks away", Math.sqrt(ctx.player().distanceToSqr(path.getVec(near)))),
                        solution.rotation.getYaw(), solution.rotation.getPitch()));
            }
            return;
        } else {
            if (this.noPitchTicks >= NO_PITCH_NOTE_TICKS) {
                FlightLog.log("solver: a pitch reaches the path again, after " + this.noPitchTicks + " ticks without one");
            }
            this.noPitchTicks = 0;
            this.lastLoggedYawOffset = 0;
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
     * In lava the elytra is inert - vanilla applies only fluid movement, glide or not - so the only thrust is
     * the rocket, at a fraction of its usual push, in a pool walled in a few blocks away on every side. The way
     * out is up: the steepest climb the simulation keeps clear, with a rocket every time.
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

        PitchResult best = this.solveSurvivalPitch(context, yaw, ticks, ticksBoosted, 0);
        if (best == null) { // cancelled by the game thread
            return unsolved;
        }

        float solvedYaw = yaw;
        final int straightSurvived = best.steps.size() - 1;
        int survivedTicks = straightSurvived;

        // A hazard that spans the flight path front-on has no pitch that clears it, only a heading that goes
        // around it. Try turning before spending a rocket or riding the impact in: stop at the first offset
        // that survives the whole horizon, and only switch off the current best for a >=2-tick gain (or for
        // reaching the full horizon outright, when the current best doesn't), so the yaw doesn't flap between
        // two offsets from one tick to the next.
        if (survivedTicks < ticks) {
            search:
            for (final float magnitude : YAW_RESCUE_OFFSETS) {
                for (final float sign : new float[]{1f, -1f}) {
                    final float candidateYaw = yaw + magnitude * sign;
                    final PitchResult candidate = this.solveSurvivalPitch(context, candidateYaw, ticks, ticksBoosted, 0);
                    if (candidate == null) {
                        // cancelled by the game thread mid-search; keep whichever yaw already survived best
                        // rather than throw away a real (if incomplete) answer for no answer at all
                        break search;
                    }
                    final int candidateSurvived = candidate.steps.size() - 1;
                    if (candidateSurvived >= survivedTicks + 2 || (candidateSurvived >= ticks && survivedTicks < ticks)) {
                        best = candidate;
                        solvedYaw = candidateYaw;
                        survivedTicks = candidateSurvived;
                        if (this.lastLoggedYawOffset != magnitude * sign) {
                            this.lastLoggedYawOffset = magnitude * sign;
                            FlightLog.log(String.format(Locale.ROOT,
                                    "solver: turning %.0f degrees off the heading to stay clear (straight survives %d ticks, turned survives %d)",
                                    magnitude * sign, straightSurvived, candidateSurvived));
                        }
                    }
                    if (survivedTicks >= ticks) {
                        break search;
                    }
                }
            }
        }
        if (solvedYaw == yaw) {
            // back to flying straight: clear the latch so a later turn, even to the same offset, logs again
            // as the new episode it is instead of being mistaken for the one that just ended
            this.lastLoggedYawOffset = 0;
        }

        // No pitch at the chosen yaw keeps us clear for the whole horizon, or we are in lava: light a rocket
        // now if it would provably do better, rather than waiting until impact is imminent.
        final boolean impactAhead = survivedTicks < ticks;
        if ((impactAhead || context.ignoreLava) && !context.boost.isBoosted() && context.hasFireworks) {
            final PitchResult boosted = this.solveSurvivalPitch(context, solvedYaw, ticks, 10, 2);
            if (boosted != null && (boosted.steps.size() > best.steps.size() + 2
                    || (context.ignoreLava && boosted.steps.size() >= best.steps.size()))) {
                final Vec3 last = boosted.steps.get(boosted.steps.size() - 1);
                this.simulationLine = boosted.steps;
                return new Solution(context, new Rotation(solvedYaw, boosted.pitch), context.start.add(last), true, true);
            }
        }

        this.simulationLine = best.steps;
        return new Solution(context, new Rotation(solvedYaw, best.pitch), null, false, false);
    }

    private PitchResult solveSurvivalPitch(final SolverContext context, final float yaw, final int ticks,
                                           final int ticksBoosted, final int ticksBoostDelay) {
        final float yawRadians = yaw * RotationUtils.DEG_TO_RAD_F;
        // a goal far along the direction we are about to face, so that the simulated yaw stays fixed
        final Vec3 direction = new Vec3(-Mth.sin(yawRadians) * 1e4, 0, Mth.cos(yawRadians) * 1e4);
        final float currentPitch = ctx.playerRotations().getPitch();

        PitchResult best = null;
        // Climbing side (negative pitch) first: new walls are usually escapable from above. With a rocket
        // burning, take the pitch that ends highest rather than the first survivor - the boost is the one
        // chance to get above whatever is boxing us in.
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
            this.useFirework(inLava ? "lava" : forceUseFirework ? "solver, forced" : "solver");
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
            FlightLog.log("rocket: no takeoff rocket needed, one is already burning");
            return true;
        }
        return this.useFirework("takeoff");
    }

    /**
     * The stretch of flight ticks without a pitch solution that is still open, for the flight record to close
     * when the flight ends in the middle of one. Resets it.
     */
    public int takeNoPitchTicks() {
        final int ticks = this.noPitchTicks;
        this.noPitchTicks = 0;
        return ticks;
    }

    /**
     * Whether the pathfinder's cache holds this block as solid - not always the same as the client's world.
     * Game thread only, and only while the solver is not running: the cache reader is not thread-safe.
     */
    public boolean nativeSolid(int x, int y, int z) {
        // the cache cull frees chunks under this lock, and the reader caches a pointer to the last one it read
        synchronized (this.context.cullingLock) {
            return !this.passable(x, y, z, false);
        }
    }

    /**
     * @return {@code true} if a firework was used
     */
    private boolean useFirework(final String why) {
        // the main hand is what processRightClick uses, and selectFirework settles for putting something harmless
        // there when the only firework we have is in the off hand, which would right click nothing at all
        if (!this.selectFirework() || !isFireworks(ctx.player().getItemInHand(InteractionHand.MAIN_HAND))) {
            logDirect("no fireworks");
            if (!this.noFireworksNoted) {
                // once until a rocket is lit again: the solver asks for one every tick while it is slow
                this.noFireworksNoted = true;
                FlightLog.log("rocket: wanted one (" + why + ") and there is none in the hotbar");
            }
            return false;
        }
        // The use packet carries a look read off the player as it's built, and the server's movement check
        // compares it with this tick's movement packet - which will carry the solver's target look, not the
        // player's current facing. Face that for the click so the two agree.
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
        this.noFireworksNoted = false;
        this.process.countRocket();
        final Vec3 pos = ctx.player().position();
        FlightLog.log(String.format(Locale.ROOT, "rocket: %s, at %.1f %.1f %.1f, speed %.2f, facing yaw %.0f pitch %.0f",
                why, pos.x, pos.y, pos.z, ctx.player().getDeltaMovement().length(), wire.getYaw(), wire.getPitch()));
        return true;
    }

    /**
     * Puts a firework in the main hand. Bringing one up from the inventory is a window click, which only
     * happens while stationary, so a takeoff has to call this before leaving the ground.
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
                // A rocket lit within the cooldown whose entity the server hasn't shown yet - a round trip,
                // several ticks on a busy server. Solve as though it had been burning since it was lit: an
                // unboosted trajectory in the meantime is a shallow glide that points a takeoff rocket at the
                // nearest wall. Expires with the cooldown if the entity never shows up.
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

            // A client holding the rocket alive past its lifetime keeps pushing this much longer; as
            // guaranteed as the rest, since it's held deliberately rather than rolled for.
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

            // Swept collision box: grows the hitbox only in the direction of travel, plus a hair of padding.
            // The old inflate(motion) grew symmetrically, reaching into the block behind/under the player -
            // at takeoff, the ground - and reporting a collision that wasn't on the flight path (upstream
            // #5047/#5052, #5094).
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
