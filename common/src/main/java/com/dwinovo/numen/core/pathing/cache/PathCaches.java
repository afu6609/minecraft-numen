package com.dwinovo.numen.core.pathing.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-level snapshots of loaded chunks for the off-thread planner.
 *
 * <p>A snapshot is refreshed only when a path search is actually requested. The server-tick hook
 * merely expires snapshots that have not served a search for a while; it never walks companions or
 * gathers chunks. Each in-flight search retains the immutable map structure it started with, while
 * later searches atomically publish a fresh view of the currently loaded chunks around their own
 * start position.
 */
public final class PathCaches {

    private PathCaches() {}

    /** How far around each requested search origin to capture loaded chunks (radius in chunks). */
    private static final int RADIUS_CHUNKS = 8;
    /** Replans in the same chunk reuse one immutable map for at most half a second. */
    private static final int REUSE_TICKS = 10;
    /** Hard ceiling for per-block protection metadata copied by one request. */
    private static final int MAX_BLOCK_ENTITY_POSITIONS = 4_096;
    /** Keep an unused level snapshot this long so closely spaced replans do not churn the map entry. */
    private static final int IDLE_GRACE_TICKS = 600;

    private static final ConcurrentHashMap<ResourceKey<Level>, SnapshotEntry> SNAPSHOTS =
            new ConcurrentHashMap<>();
    private static final AtomicLong BUILDS = new AtomicLong();
    private static final AtomicLong REUSES = new AtomicLong();
    private static final AtomicLong LAST_BUILD_MICROS = new AtomicLong();
    private static final AtomicLong MAX_BUILD_MICROS = new AtomicLong();

    /** The most recently requested snapshot for {@code level}, or null if none is cached. */
    public static LoadedChunks peek(Level level) {
        SnapshotEntry entry = SNAPSHOTS.get(level.dimension());
        return entry == null ? null : entry.chunks();
    }

    /**
     * Refresh and return the snapshot around {@code around}.
     *
     * <p>Called on the main thread immediately before a search. Closely spaced replans from the same
     * chunk reuse a short-lived snapshot; otherwise one bounded 17×17-chunk capture is built. This
     * keeps work proportional to real route starts rather than companion count × server ticks.
     */
    public static LoadedChunks ensureSnapshot(ServerLevel level, BlockPos around) {
        int requestedAt = level.getServer().getTickCount();
        int centerChunkX = SectionPos.blockToSectionCoord(around.getX());
        int centerChunkZ = SectionPos.blockToSectionCoord(around.getZ());
        SnapshotEntry cached = SNAPSHOTS.get(level.dimension());
        if (cached != null
                && cached.centerChunkX() == centerChunkX
                && cached.centerChunkZ() == centerChunkZ
                && requestedAt - cached.builtAtTick() <= REUSE_TICKS) {
            REUSES.incrementAndGet();
            SNAPSHOTS.replace(
                    level.dimension(),
                    cached,
                    cached.usedAt(requestedAt));
            return cached.chunks();
        }

        long startedNanos = System.nanoTime();
        LoadedChunks built = snapshot(level, List.of(around));
        long elapsedMicros = Math.max(
                0L,
                (System.nanoTime() - startedNanos) / 1_000L);
        BUILDS.incrementAndGet();
        LAST_BUILD_MICROS.set(elapsedMicros);
        MAX_BUILD_MICROS.accumulateAndGet(elapsedMicros, Math::max);
        SNAPSHOTS.put(
                level.dimension(),
                new SnapshotEntry(
                        built,
                        centerChunkX,
                        centerChunkZ,
                        requestedAt,
                        requestedAt));
        return built;
    }

    /** Lifetime counters for diagnostics; reading them never touches world state. */
    public static Performance performance() {
        return new Performance(
                BUILDS.get(),
                REUSES.get(),
                LAST_BUILD_MICROS.get(),
                MAX_BUILD_MICROS.get());
    }

    public static void dropAll() {
        SNAPSHOTS.clear();
    }

    /**
     * Expire snapshots that have not served a path request recently. This is deliberately bookkeeping
     * only: chunk gathering belongs to {@link #ensureSnapshot}, never the every-tick hot path.
     */
    public static void serverTick(MinecraftServer server) {
        int now = server.getTickCount();
        for (Map.Entry<ResourceKey<Level>, SnapshotEntry> cached : SNAPSHOTS.entrySet()) {
            SnapshotEntry entry = cached.getValue();
            if (now - entry.lastUsedAtTick() > IDLE_GRACE_TICKS) {
                SNAPSHOTS.remove(cached.getKey(), entry);
            }
        }
    }

    /** Gather references to the chunks loaded within {@link #RADIUS_CHUNKS} of the requested origins
     *  (non-blocking — unloaded chunks are simply absent → the reader sees AIR). */
    private static LoadedChunks snapshot(ServerLevel level, List<BlockPos> feet) {
        Long2ObjectOpenHashMap<LevelChunk> map = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet blockEntities = new LongOpenHashSet();
        LongOpenHashSet opaqueBlockEntityChunks = new LongOpenHashSet();
        for (BlockPos f : feet) {
            int ccx = SectionPos.blockToSectionCoord(f.getX());
            int ccz = SectionPos.blockToSectionCoord(f.getZ());
            for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                    int cx = ccx + dx;
                    int cz = ccz + dz;
                    long key = ChunkPos.asLong(cx, cz);
                    if (!map.containsKey(key)) {
                        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                        if (chunk != null) {
                            map.put(key, chunk);
                            if (blockEntities.size() >= MAX_BLOCK_ENTITY_POSITIONS) {
                                if (!chunk.getBlockEntities().isEmpty()) {
                                    opaqueBlockEntityChunks.add(key);
                                }
                                continue;
                            }
                            for (BlockPos bePos : chunk.getBlockEntities().keySet()) {
                                if (blockEntities.size() >= MAX_BLOCK_ENTITY_POSITIONS) {
                                    opaqueBlockEntityChunks.add(key);
                                    break;
                                }
                                blockEntities.add(bePos.asLong());
                            }
                        }
                    }
                }
            }
        }
        return new LoadedChunks(map, blockEntities, opaqueBlockEntityChunks);
    }

    public record Performance(
            long builds,
            long reuses,
            long lastBuildMicros,
            long maxBuildMicros) {}

    private record SnapshotEntry(
            LoadedChunks chunks,
            int centerChunkX,
            int centerChunkZ,
            int builtAtTick,
            int lastUsedAtTick) {

        private SnapshotEntry usedAt(int tick) {
            return new SnapshotEntry(
                    chunks,
                    centerChunkX,
                    centerChunkZ,
                    builtAtTick,
                    tick);
        }
    }
}
