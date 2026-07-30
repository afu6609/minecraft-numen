package com.dwinovo.numen.core.pathing.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * An immutable snapshot of the loaded chunks near a path request — a thread-safe copy of the
 * chunk provider's "what is loaded right now". Built or briefly reused by
 * {@link PathCaches#ensureSnapshot} and read by the planner off-thread. It holds live
 * {@link LevelChunk} references, so a lookup reads the LIVE section
 * palette — exact for loaded terrain. We tolerate the rare race of reading
 * a palette the main thread is concurrently resizing (the reader catches it and yields AIR; the
 * executor re-costs live and replans) — a deliberate exactness-for-cheapness trade.
 *
 * <p>Never mutated after construction, so a worker reading the map structure can't race a writer — only
 * the shared chunk CONTENTS are live. A newly built snapshot is published through
 * {@link PathCaches}; an in-flight search keeps the immutable map it started with.
 */
public final class LoadedChunks {

    private final Long2ObjectMap<LevelChunk> chunks;
    /** Packed positions ({@link BlockPos#asLong}) that held a block entity when this snapshot was taken
     *  — captured on the main thread so the don't-grief check is answerable off-thread without a live
     *  read (presence is all {@code shouldAvoidBreaking} needs). */
    private final LongSet blockEntities;
    /**
     * Chunks whose block-entity position list exceeded the bounded snapshot
     * budget. Treating every block there as protected is conservative: a
     * pathological chunk cannot turn one path request into an unbounded
     * main-thread scan, and the planner never griefs an unknown container.
     */
    private final LongSet opaqueBlockEntityChunks;

    LoadedChunks(
            Long2ObjectMap<LevelChunk> chunks,
            LongSet blockEntities,
            LongSet opaqueBlockEntityChunks) {
        this.chunks = chunks;
        this.blockEntities = blockEntities;
        this.opaqueBlockEntityChunks = opaqueBlockEntityChunks;
    }

    /** The loaded chunk at the given chunk coordinates, or {@code null} if it wasn't loaded when this
     *  snapshot was taken (→ the reader treats it as unknown / AIR). */
    public LevelChunk at(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Whether a block entity occupied {@code pos} when this snapshot was taken. Only meaningful for a
     *  position inside a captured chunk — {@link #blockEntities} is populated in lockstep with
     *  {@link #at}, and a cell outside the snapshot reads AIR (so the don't-grief check, which only
     *  runs on a breakable block, is never consulted there). */
    public boolean hasBlockEntity(BlockPos pos) {
        return blockEntities.contains(pos.asLong())
                || opaqueBlockEntityChunks.contains(ChunkPos.asLong(
                        SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ())));
    }

    /** Number of chunks captured — for debug / memory accounting. */
    public int size() {
        return chunks.size();
    }
}
