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
import baritone.utils.accessor.IPalettedContainer;
import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;
import dev.babbaj.pathfinder.PathSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.SoftReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author Brady
 */
public final class NetherPathfinderContext {

    private static final BlockState AIR_BLOCK_STATE = Blocks.AIR.defaultBlockState();
    // This lock must be held while there are active pointers to chunks in java,
    // but we just hold it for the entire tick so we don't have to think much about it.
    public final Object cullingLock = new Object();

    // Visible for access in BlockStateOctreeInterface
    final long context;
    // Published before freeContext so that a path result still sitting in the game thread's task
    // queue turns into a no-op instead of dereferencing memory that is already gone.
    private volatile boolean destroyed;
    private final long seed;
    private final ExecutorService executor;

    public NetherPathfinderContext(long seed) {
        this.context = NetherPathfinder.newContext(seed);
        this.seed = seed;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public boolean hasChunk(ChunkPos pos) {
        if (this.destroyed) {
            return false;
        }
        return NetherPathfinder.hasChunkFromJava(this.context, pos.x, pos.z);
    }

    /**
     * Submits to the executor, dropping the task if the context is already being torn down.
     */
    private void executeTask(Runnable task) {
        try {
            this.executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // destroy() has shut the executor down; the cache no longer matters
        }
    }

    public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks, BlockStateOctreeInterface boi) {
        this.executeTask(() -> {
            synchronized (this.cullingLock) {
                boi.chunkPtr = 0L;
                NetherPathfinder.cullFarChunks(this.context, chunkX, chunkZ, maxDistanceBlocks);
            }
        });
    }

    public void queueForPacking(final LevelChunk chunkIn) {
        final SoftReference<LevelChunk> ref = new SoftReference<>(chunkIn);
        this.executeTask(() -> {
            // TODO: Prioritize packing recent chunks and/or ones that the path goes through,
            //       and prune the oldest chunks per chunkPackerQueueMaxSize
            final LevelChunk chunk = ref.get();
            if (chunk != null) {
                long ptr = NetherPathfinder.getOrCreateChunk(this.context, chunk.getPos().x, chunk.getPos().z);
                writeChunkData(chunk, ptr);
            }
        });
    }

    public void queueBlockUpdate(BlockChangeEvent event) {
        this.executeTask(() -> {
            ChunkPos chunkPos = event.getChunkPos();
            long ptr = NetherPathfinder.getChunkPointer(this.context, chunkPos.x, chunkPos.z);
            if (ptr == 0) return; // this shouldn't ever happen
            event.getBlocks().forEach(pair -> {
                BlockPos pos = pair.first();
                if (pos.getY() >= 128) return;
                boolean isSolid = pair.second() != AIR_BLOCK_STATE;
                Octree.setBlock(ptr, pos.getX() & 15, pos.getY(), pos.getZ() & 15, isSolid);
            });
        });
    }

    public CompletableFuture<PathSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (this.destroyed) {
                    throw new PathCalculationException("Pathfinder context destroyed");
                }
                final PathSegment segment = NetherPathfinder.pathFind(
                        this.context,
                        src.getX(), src.getY(), src.getZ(),
                        dst.getX(), dst.getY(), dst.getZ(),
                        true,
                        false,
                        10000,
                        !Baritone.settings().elytraPredictTerrain.value
                );
                if (segment == null) {
                    throw new PathCalculationException("Path calculation failed");
                }
                return segment;
            }, this.executor);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new PathCalculationException("Pathfinder context destroyed"));
        }
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
        if (this.destroyed) {
            return false;
        }
        return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID, startX, startY, startZ, endX, endY, endZ);
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
        if (this.destroyed) {
            return false;
        }
        return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID, start.x, start.y, start.z, end.x, end.y, end.z);
    }

    public boolean raytrace(final int count, final double[] src, final double[] dst, final int visibility) {
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
    }

    public void raytrace(final int count, final double[] src, final double[] dst, final boolean[] hitsOut, final double[] hitPosOut) {
        if (this.destroyed) {
            java.util.Arrays.fill(hitsOut, true);
            return;
        }
        NetherPathfinder.raytrace(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, hitsOut, hitPosOut);
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
        this.executor.shutdownNow();

        boolean terminated;
        try {
            // The native cancel is a no-op, so shutdownNow only drops queued tasks; an in-flight pathFind
            // runs to its own timeout. Bound the wait: a wedged native call must not pin this thread forever.
            terminated = this.executor.awaitTermination(TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
     * Frees the native context. Must be called on the game thread, and only after {@link #shutdown()}
     * and the solver have been drained: that leaves the game thread as the only possible caller, so
     * nothing can be inside a native call while the memory goes away. A path result that completed
     * before the shutdown is queued on the game thread ahead of this and still sees a live context.
     */
    public void free() {
        this.destroyed = true;
        NetherPathfinder.freeContext(this.context);
    }

    public long getSeed() {
        return this.seed;
    }

    private static void writeChunkData(LevelChunk chunk, long ptr) {
        try {
            LevelChunkSection[] chunkInternalStorageArray = chunk.getSections();
            for (int y0 = 0; y0 < 8; y0++) {
                final LevelChunkSection extendedblockstorage = chunkInternalStorageArray[y0];
                if (extendedblockstorage == null) {
                    continue;
                }
                final PalettedContainer<BlockState> bsc = extendedblockstorage.getStates();
                IPalettedContainer<BlockState> iPalettedContainer = (IPalettedContainer<BlockState>) bsc;
                int airId = -1;
                if (iPalettedContainer.getPalette().maybeHas(state -> state.equals(AIR_BLOCK_STATE))) {
                    airId = iPalettedContainer.getPalette().idFor(AIR_BLOCK_STATE, PaletteResize.noResizeExpected());
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
                        Octree.setBlock(ptr, x, y, z, value != airId);
                    }
                }
            }
            Octree.setIsFromJava(ptr);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static final class Visibility {

        public static final int ALL = 0;
        public static final int NONE = 1;
        public static final int ANY = 2;

        private Visibility() {}
    }

    public static boolean isSupported() {
        return NetherPathfinder.isThisSystemSupported();
    }
}
