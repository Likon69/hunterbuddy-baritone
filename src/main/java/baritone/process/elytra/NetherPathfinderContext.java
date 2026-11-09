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
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.accessor.IPalettedContainer;
import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;
import dev.babbaj.pathfinder.PathSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static net.minecraft.world.level.chunk.LevelChunkSection.SECTION_SIZE;

/**
 * @author Brady
 */
public final class NetherPathfinderContext implements IElytraPathFinder {

    private static final Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    private static final BlockState AIR_BLOCK_STATE = Blocks.AIR.defaultBlockState();
    /**
     * The other two kinds of air. This packer was written when there was only one, and kept testing for that one
     * alone: every other block went into the cache as solid. Since 1.13 the nether carver fills what it carves
     * with cave air above the lava level ({@code NetherWorldCarver#carveBlock}), so in terrain generated since
     * then every carved tunnel and pocket was rock to the pathfinder - and to the solver, which reads the same
     * cache - while the takeoff, which reads the client world, saw open air there and launched from it.
     * <p>
     * Measured on a flight: nine launches in a row from one pocket, each asking for a path from a 4x4x4 cube the
     * takeoff had checked was air, each getting one whose first node sat in the same column nine blocks further
     * down. The same thing happened to searches started from the player's own position while it was scraping
     * along the pocket's ceiling - in open air, and after its chunks had been packed again.
     */
    private static final BlockState CAVE_AIR_BLOCK_STATE = Blocks.CAVE_AIR.defaultBlockState();
    private static final BlockState VOID_AIR_BLOCK_STATE = Blocks.VOID_AIR.defaultBlockState();
    /**
     * A whole chunk of solid, in the layout {@code NetherPathfinder.insertChunkData} expects (index
     * {@code y << 8 | z << 4 | x}): 16x384x16 for the overworld, 16x256x16 for the nether and the end, the native
     * side rejecting any other length. Inserted for every chunk a corridor search must not enter. The
     * native side copies the array and never writes back, so one shared instance per size is safe.
     */
    private static final boolean[] ALL_SOLID_OVERWORLD = new boolean[16 * 16 * 384];
    private static final boolean[] ALL_SOLID_OTHER = new boolean[16 * 16 * 256];
    /**
     * A whole chunk of air, same layouts as {@link #ALL_SOLID_OVERWORLD}. Inserted to wipe a chunk before it is
     * packed again, and for a corridor chunk the client has not received yet.
     */
    private static final boolean[] ALL_AIR_OVERWORLD = new boolean[16 * 16 * 384];
    private static final boolean[] ALL_AIR_OTHER = new boolean[16 * 16 * 256];

    static {
        java.util.Arrays.fill(ALL_SOLID_OVERWORLD, true);
        java.util.Arrays.fill(ALL_SOLID_OTHER, true);
    }

    // This lock must be held while there are active pointers to chunks in java,
    // but we just hold it for the entire tick so we don't have to think much about it.
    public final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    public final ReentrantReadWriteLock.ReadLock readLock = rwl.readLock();
    public final ReentrantReadWriteLock.WriteLock writeLock = rwl.writeLock();
    private final int maxHeight;

    // Visible for access in BlockStateOctreeInterface
    final long context;
    // Published before freeContext so that a path result still sitting in the game thread's task
    // queue turns into a no-op instead of dereferencing memory that is already gone.
    private volatile boolean destroyed;
    private final long seed;
    // write locked operations
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    // operations that don't make changes to the chunk cache. could use multiple threads but i'm not sure if it would cause problems.
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private final ResourceKey<Level> dimension;
    private final int nativeDim;
    final int minY;

    public NetherPathfinderContext(long seed, Path cache, Level world) {
        this.dimension = world.dimension();
        this.minY = world.dimensionType().minY();
        final int dim;
        if (this.dimension == Level.NETHER) dim = NetherPathfinder.DIMENSION_NETHER;
        else if (this.dimension == Level.END) dim = NetherPathfinder.DIMENSION_END;
        else dim = NetherPathfinder.DIMENSION_OVERWORLD;
        int height = Math.min(world.dimensionType().height(), 384);
        if (!Baritone.settings().elytraAllowAboveRoof.value && dim == NetherPathfinder.DIMENSION_NETHER) height = Math.min(height, 128);
        this.maxHeight = height;
        this.nativeDim = dim;
        this.context = NetherPathfinder.newContext(seed, cache != null ? cache.toString() : null, dim, height, Baritone.settings().elytraCustomAllocator.value);
        this.seed = seed;
    }

    public boolean hasChunk(ChunkPos pos) {
        if (this.destroyed) {
            return false;
        }
        return NetherPathfinder.hasChunkFromJava(this.context, pos.x, pos.z);
    }

    /**
     * Submits to the write executor, dropping the task if the context is already being torn down.
     */
    private void executeWrite(Runnable task) {
        try {
            this.writeExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shutdown() has shut the executor down; the cache no longer matters
        }
    }

    public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks) {
        this.executeWrite(() -> {
            writeLock.lock();
            try {
                NetherPathfinder.cullFarChunks(this.context, chunkX, chunkZ, maxDistanceBlocks);
            } finally {
                writeLock.unlock();
            }
        });
    }

    public void queueForPacking(final LevelChunk chunkIn) {
        this.queueForPacking(chunkIn, false);
    }

    /**
     * Packs the chunk's blocks into this context's cache on the context's executor.
     *
     * @param resetFirst Kept for callers that ask for a wipe. Packing always allocates a fresh all-air chunk and
     *                   swaps it into the cache, which frees the old one, so a masked chunk always turns back into real
     *                   terrain and the flag changes nothing.
     */
    public void queueForPacking(final LevelChunk chunkIn, final boolean resetFirst) {
        final SoftReference<LevelChunk> ref = new SoftReference<>(chunkIn);
        this.executeWrite(() -> {
            // TODO: Prioritize packing recent chunks and/or ones that the path goes through,
            //       and prune the oldest chunks per chunkPackerQueueMaxSize
            final LevelChunk chunk = ref.get();
            if (chunk != null) {
                writeLock.lock();
                try {
                    long ptr = NetherPathfinder.allocateAndInsertChunk(this.context, chunk.getPos().x, chunk.getPos().z);
                    writeChunkData(chunk, ptr);
                } finally {
                    writeLock.unlock();
                }
            }
        });
    }

    /**
     * Replaces the cached chunk with one that is solid from bedrock to the roof, so that no search in this
     * context can pass through it. Runs on this context's write executor under the write lock like every other
     * cache write: the native insert swaps the cache entry, and a search running on the same context reads the
     * cache, so the two must never overlap.
     */
    public void queueSolid(final int chunkX, final int chunkZ) {
        this.executeWrite(() -> {
            writeLock.lock();
            try {
                NetherPathfinder.insertChunkData(this.context, chunkX, chunkZ, this.nativeDim == NetherPathfinder.DIMENSION_OVERWORLD ? ALL_SOLID_OVERWORLD : ALL_SOLID_OTHER);
            } finally {
                writeLock.unlock();
            }
        });
    }

    /**
     * Replaces the cached chunk with one that is all air, which is what a chunk the pathfinder has never been
     * given amounts to during a search. Used to undo {@link #queueSolid} for a chunk the client has not loaded,
     * where there is nothing real to pack in its place.
     */
    public void queueAir(final int chunkX, final int chunkZ) {
        this.executeWrite(() -> {
            writeLock.lock();
            try {
                NetherPathfinder.insertChunkData(this.context, chunkX, chunkZ, this.nativeDim == NetherPathfinder.DIMENSION_OVERWORLD ? ALL_AIR_OVERWORLD : ALL_AIR_OTHER);
            } finally {
                writeLock.unlock();
            }
        });
    }

    public void queueBlockUpdate(BlockChangeEvent event) {
        this.executeWrite(() -> {
            ChunkPos chunkPos = event.getChunkPos();
            // not inserting or deleting from the cache hashmap but it would still be bad for this function to race with itself
            writeLock.lock();
            try {
                long ptr = NetherPathfinder.getChunk(this.context, chunkPos.x, chunkPos.z);
                if (ptr == 0) return; // this shouldn't ever happen
                event.getBlocks().forEach(pair -> {
                    BlockPos pos = pair.first().below(minY);
                    if (pos.getY() < 0 || pos.getY() >= 384) return;
                    // any of the three airs, the same test writeChunkData makes
                    boolean isSolid = !pair.second().isAir();
                    Octree.setBlock(ptr, pos.getX() & 15, pos.getY(), pos.getZ() & 15, isSolid);
                });
            } finally {
                writeLock.unlock();
            }
        });
    }

    public CompletableFuture<UnpackedSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        final boolean atLeastX4 = Baritone.settings().elytraPathNodeSize.value >= 4 && !Baritone.settings().elytraAllowTightSpaces.value;
        return this.pathFindAsync(src, dst, atLeastX4).thenApply(UnpackedSegment::from);
    }

    /**
     * @param atLeastX4 Search with 4-block nodes ({@code true}) or 2-block nodes ({@code false}). The fine search
     *                  gets through gaps a gliding player fits through; the wide one is several times faster.
     * @return The raw segment, its nodes in world coordinates
     */
    public CompletableFuture<PathSegment> pathFindAsync(final BlockPos src, final BlockPos dst, final boolean atLeastX4) {
        final BlockPos adjustedSrc = src.below(minY);
        final BlockPos adjustedDst = dst.below(minY);
        boolean generate = Baritone.settings().elytraPredictTerrain.value && this.dimension == Level.NETHER;
        Lock l = generate ? writeLock : readLock;
        ExecutorService exec = generate ? writeExecutor : readExecutor;
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (this.destroyed) {
                    throw new PathCalculationException("Pathfinder context destroyed");
                }
                l.lock();
                try {
                    final PathSegment segment = NetherPathfinder.pathFind(
                            this.context,
                            adjustedSrc.getX(), adjustedSrc.getY(), adjustedSrc.getZ(),
                            adjustedDst.getX(), adjustedDst.getY(), adjustedDst.getZ(),
                            atLeastX4,
                            false, // refine
                            10000, // timeoutMs
                            !generate, // useAirIfChunkNotLoaded
                            // TODO: Determine appropriate cost value
                            8.0 // fakeChunkCost
                    );
                    if (segment == null) {
                        throw new PathCalculationException("Path calculation failed");
                    }
                    return this.toWorldY(segment);
                } finally {
                    l.unlock();
                }
            }, exec);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new PathCalculationException("Pathfinder context destroyed"));
        }
    }

    private PathSegment toWorldY(final PathSegment segment) {
        if (this.minY == 0) {
            return segment;
        }
        final long[] packed = new long[segment.packed.length];
        for (int i = 0; i < packed.length; i++) {
            final BetterBlockPos node = BetterBlockPos.deserializeFromLong(segment.packed[i]);
            packed[i] = BetterBlockPos.serializeToLong(node.x, node.y + this.minY, node.z);
        }
        return new PathSegment(segment.finished, packed);
    }

    /**
     * Whether a height, already shifted to the pathfinder's own origin, lies outside the octree. A ray ending
     * outside it indexes past the end of the chunk's array and reads whatever follows the chunk, which crashed
     * twice on 2026-09-18 through {@code isVisible}. Only the two single-ray calls are bounded: the batched one
     * is the hitbox check, whose corners reach past the roof on any climb.
     */
    private boolean outsideOctree(final double y) {
        return y < 0.0 || y >= this.maxHeight;
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param startX The start X coordinate
     * @param startY The start Y coordinate
     * @param startZ The start Z coordinate
     * @param endX   The end X coordinate
     * @param endY   The end Y coordinate
     * @param endZ   The end Z coordinate
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final double startX, final double startY, final double startZ,
                            final double endX, final double endY, final double endZ) {
        final double adjustedStartY = startY - this.minY;
        final double adjustedEndY = endY - this.minY;
        if (outsideOctree(adjustedStartY) || outsideOctree(adjustedEndY)) {
            return false;
        }

        this.readLock.lock();
        try {
            if (this.destroyed) {
                return false;
            }
            return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID, startX, adjustedStartY, startZ, endX, adjustedEndY, endZ);
        } finally {
            this.readLock.unlock();
        }
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param start The starting point
     * @param end   The ending point
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final Vec3 start, final Vec3 end) {
        final Vec3 adjustedStart = start.subtract(0, this.minY, 0);
        final Vec3 adjustedEnd = end.subtract(0, this.minY, 0);
        if (outsideOctree(adjustedStart.y) || outsideOctree(adjustedEnd.y)) {
            return false;
        }

        this.readLock.lock();
        try {
            if (this.destroyed) {
                return false;
            }
            return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID, adjustedStart.x, adjustedStart.y, adjustedStart.z, adjustedEnd.x, adjustedEnd.y, adjustedEnd.z);
        } finally {
            this.readLock.unlock();
        }
    }

    public boolean raytrace(final int count, final double[] src, final double[] dst, final int visibility) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        this.readLock.lock();
        try {
            if (this.destroyed) {
                return false;
            }
            switch (visibility) {
                case Visibility.ALL:
                    return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, false) == -1;
                case Visibility.NONE:
                    return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) == -1;
                case Visibility.ANY:
                    return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) != -1;
                default:
                    throw new IllegalArgumentException("lol");
            }
        } finally {
            this.readLock.unlock();
        }
    }

    public void raytrace(final int count, final double[] src, final double[] dst, final boolean[] hitsOut, final double[] hitPosOut) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        this.readLock.lock();
        try {
            if (this.destroyed) {
                java.util.Arrays.fill(hitsOut, true);
                return;
            }
            NetherPathfinder.raytrace(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, hitsOut, hitPosOut);
        } finally {
            this.readLock.unlock();
        }
    }

    public BlockStateOctreeInterface getBoi() {
        return new BlockStateOctreeInterface(this);
    }

    public void cancel() {
        NetherPathfinder.cancel(this.context);
    }

    // Generous relative to the 10000ms native pathFind timeout, which does not bound findAir's BFS. A normal
    // teardown finishes well inside this; overshooting it means a search is genuinely wedged in native code
    // and this context is abandoned rather than freed.
    private static final long TEARDOWN_TIMEOUT_MS = 15000;

    /**
     * Stops all background work and waits for it to finish. The native context is still alive
     * afterwards - see {@link #free()}.
     *
     * @return {@code false} if a search was still running in native code when the wait ran out. The context
     *         has been marked destroyed so that nothing new calls into it, but it must not be freed: that
     *         search still holds it, and freeing under it would be a use-after-free. Abandon (leak) it instead.
     */
    public boolean shutdown() {
        this.cancel();
        // Ignore anything that was queued up, just shutdown the executor
        this.readExecutor.shutdownNow();
        this.writeExecutor.shutdownNow();

        boolean terminated;
        try {
            // The native cancel is a no-op, so shutdownNow only drops queued tasks; an in-flight pathFind
            // runs to its own timeout. Bound the wait: a wedged native call must not pin this thread forever.
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TEARDOWN_TIMEOUT_MS);
            terminated = this.readExecutor.awaitTermination(TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    && this.writeExecutor.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminated = false;
        }
        if (!terminated) {
            this.destroyed = true;
        }
        return terminated;
    }

    /**
     * Frees the native context. Must be called by a thread that holds no read lock of this context, and only
     * after {@link #shutdown()} and the solver have been drained: that leaves the caller as the only
     * possible user, so nothing can be inside a native call while the memory goes away. A path result that
     * completed before the shutdown is queued on the game thread ahead of this and still sees a live context.
     */
    public void free() {
        this.writeLock.lock();
        try {
            this.destroyed = true;
            NetherPathfinder.freeContext(this.context);
        } finally {
            this.writeLock.unlock();
        }
    }

    /**
     * Stops all background work and frees the native context, or abandons it if a search is wedged in native
     * code (see {@link #shutdown()}).
     */
    public void destroy() {
        if (this.shutdown()) {
            this.free();
        }
    }

    public long getSeed() {
        return this.seed;
    }

    public void acquireReadLock() {
        this.readLock.lock();
    }

    public boolean tryAcquireReadLock() {
        return this.readLock.tryLock();
    }

    public void releaseReadLock() {
        this.readLock.unlock();
    }

    public int getMaxHeight() {
        return this.maxHeight;
    }

    private static void writeChunkData(LevelChunk chunk, long chunkPtr) {
        try {
            LevelChunkSection[] chunkInternalStorageArray = chunk.getSections();
            final int maxSections = Math.min(chunkInternalStorageArray.length, 24); // pathfinder support stops at 384/16 sections
            for (int y0 = 0; y0 < maxSections; y0++) {
                final LevelChunkSection extendedblockstorage = chunkInternalStorageArray[y0];
                if (extendedblockstorage == null || extendedblockstorage.hasOnlyAir()) {
                    continue;
                }
                final PalettedContainer<BlockState> bsc = extendedblockstorage.getStates();
                IPalettedContainer<BlockState> iPalettedContainer = (IPalettedContainer<BlockState>) bsc;
                var palette = iPalettedContainer.getPalette();
                // all three airs, not only the plain one: see CAVE_AIR_BLOCK_STATE
                // Mushrooms spawn on the roof and writing them as solid will cause pages to be unnecessarily allocated.
                // idFor can't be used because it may update the palette
                int airId = -1;
                int caveAirId = -1;
                int voidAirId = -1;
                int redMushroomId = -1;
                int brownMushroomId = -1;
                for (int i = 0; i < palette.getSize(); i++) {
                    BlockState bs = palette.valueFor(i);
                    if (bs == AIR_BLOCK_STATE) airId = i;
                    else if (bs == CAVE_AIR_BLOCK_STATE) caveAirId = i;
                    else if (bs == VOID_AIR_BLOCK_STATE) voidAirId = i;
                    else if (bs == Blocks.RED_MUSHROOM.defaultBlockState()) redMushroomId = i;
                    else if (bs == Blocks.BROWN_MUSHROOM.defaultBlockState()) brownMushroomId = i;
                }
                if (airId == -1 & caveAirId == -1 & voidAirId == -1) {
                    final long bytesInSection = SECTION_SIZE / 8;
                    UNSAFE.setMemory(chunkPtr + (y0 * bytesInSection), bytesInSection, (byte) 0xFF);
                    continue;
                }
                // pasted from FasterWorldScanner
                final BitStorage array = iPalettedContainer.getStorage();
                if (array == null) continue;
                final long[] longArray = array.getRaw();
                final int arraySize = array.getSize();
                int bitsPerEntry = array.getBits();
                long maxEntryValue = (1L << bitsPerEntry) - 1L;

                final int yReal = y0 << 4;
                for (int i = 0, idx = 0; i < longArray.length && idx < arraySize; ++i) {
                    long l = longArray[i];
                    for (int offset = 0; offset <= (64 - bitsPerEntry) && idx < arraySize; offset += bitsPerEntry, ++idx) {
                        int value = (int) ((l >> offset) & maxEntryValue);
                        int x = (idx & 15);
                        int y = yReal + (idx >> 8);
                        int z = ((idx >> 4) & 15);

                        // Avoid unnecessary writes that may trigger a page allocation
                        if (!(value == airId | value == caveAirId | value == voidAirId) & value != redMushroomId & value != brownMushroomId) {
                            Octree.setBlock(chunkPtr, x, y, z, true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static boolean isSupported() {
        return NetherPathfinder.isThisSystemSupported();
    }

    public static final class Visibility {
        public static final int ALL = 0;
        public static final int NONE = 1;
        public static final int ANY = 2;
        private Visibility() {}
    }
}
