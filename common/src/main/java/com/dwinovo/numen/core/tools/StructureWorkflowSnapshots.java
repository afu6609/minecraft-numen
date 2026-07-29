package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Read-only public boundary around the package-private structure workflow store.
 *
 * <p>Consumers outside {@code core.tools} must never retain the mutable store
 * objects themselves. This facade copies metadata, cells and state properties
 * while the store monitor is held, so a survival subsystem can safely analyse a
 * companion-owned blueprint without gaining mutation or filesystem access.</p>
 */
public final class StructureWorkflowSnapshots {

    private static final Map<StructureWorkflowStore.Workflow, CachedSnapshot>
            SNAPSHOT_CACHE = new WeakHashMap<>();

    private StructureWorkflowSnapshots() {}

    /** Resolve the companion's latest saved workflow and return a defensive snapshot. */
    public static WorkflowSnapshot latest(NumenPlayer companion) {
        return resolve(companion, "latest");
    }

    /**
     * Return every workflow owned by the companion, newest first. This is the
     * correct discovery entry point for shelter recognition: a later decoration
     * or repair workflow must not hide an older house blueprint.
     */
    public static List<WorkflowSnapshot> owned(NumenPlayer companion) {
        Objects.requireNonNull(companion, "companion");
        synchronized (StructureWorkflowStore.class) {
            return StructureWorkflowStore.owned(companion).stream()
                    .map(StructureWorkflowSnapshots::snapshotOf)
                    .toList();
        }
    }

    /**
     * Bounded current-dimension discovery view. Filtering happens inside the
     * store before snapshots are decoded/copied, so newer workflows from another
     * dimension neither consume the cap nor create per-tick analysis work.
     */
    public static List<WorkflowSnapshot> owned(
            NumenPlayer companion, int limit, int legacyLimit) {
        Objects.requireNonNull(companion, "companion");
        synchronized (StructureWorkflowStore.class) {
            return StructureWorkflowStore
                    .owned(companion, limit, legacyLimit)
                    .stream()
                    .map(StructureWorkflowSnapshots::snapshotOf)
                    .toList();
        }
    }

    /**
     * Atomically attach the current dimension to a legacy workflow only when
     * every saved cell (including automatic door/bed partner cells) is loaded
     * and exactly equals the desired live block state.
     */
    public static DimensionClaimResult claimLegacyDimensionIfExact(
            NumenPlayer companion, String workflowId) {
        Objects.requireNonNull(companion, "companion");
        synchronized (StructureWorkflowStore.class) {
            StructureWorkflowStore.DimensionClaim claim =
                    StructureWorkflowStore.claimDimensionIfExact(
                            companion, workflowId);
            Optional<WorkflowSnapshot> snapshot =
                    claim.workflow() == null
                            ? Optional.empty()
                            : Optional.of(snapshotOf(claim.workflow()));
            return new DimensionClaimResult(
                    DimensionClaimStatus.valueOf(claim.status().name()),
                    snapshot,
                    claim.verifiedCells(),
                    Optional.ofNullable(claim.problem())
                            .map(BlockPos::immutable),
                    claim.detail());
        }
    }

    /**
     * Resolve one exact companion-owned workflow (or {@code latest}) and return a
     * defensive snapshot.
     *
     * <p>The underlying store enforces ownership from the supplied companion UUID.</p>
     */
    public static WorkflowSnapshot resolve(
            NumenPlayer companion, String workflowId) {
        Objects.requireNonNull(companion, "companion");
        synchronized (StructureWorkflowStore.class) {
            return snapshotOf(StructureWorkflowStore.resolve(companion, workflowId));
        }
    }

    /** Package-visible seam for headless defensive-copy tests. */
    static WorkflowSnapshot snapshotOf(StructureWorkflowStore.Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        synchronized (SNAPSHOT_CACHE) {
            CachedSnapshot cached = SNAPSHOT_CACHE.get(workflow);
            if (cached != null
                    && cached.revision == workflow.revision
                    && cached.updatedAt == workflow.updatedAt
                    && cached.dimensionId.equals(
                    workflow.dimensionId == null ? "" : workflow.dimensionId)
                    && cached.cellCount == workflow.cells.size()) {
                return cached.snapshot;
            }
        }
        List<CellSnapshot> cells = workflow.cells.stream()
                .map(cell -> new CellSnapshot(
                        cell.pos(),
                        cell.itemId,
                        cell.desired.toState(),
                        cell.before == null ? null : cell.before.toState()))
                .toList();
        WorkflowSnapshot snapshot = new WorkflowSnapshot(
                workflow.id,
                workflow.ownerUuid,
                workflow.ownerName,
                Optional.ofNullable(workflow.dimensionId)
                        .filter(value -> !value.isBlank()),
                workflow.name,
                workflow.goal,
                workflow.allowReplace,
                workflow.createdAt,
                workflow.updatedAt,
                workflow.revision,
                workflow.lastOperation,
                cells);
        synchronized (SNAPSHOT_CACHE) {
            SNAPSHOT_CACHE.put(workflow, new CachedSnapshot(
                    workflow.revision,
                    workflow.updatedAt,
                    workflow.dimensionId == null ? "" : workflow.dimensionId,
                    workflow.cells.size(),
                    snapshot));
        }
        return snapshot;
    }

    private record CachedSnapshot(
            long revision,
            long updatedAt,
            String dimensionId,
            int cellCount,
            WorkflowSnapshot snapshot) {}

    /** Immutable complete desired-cell view of one saved structure workflow. */
    public record WorkflowSnapshot(
            String id,
            String ownerUuid,
            String ownerName,
            Optional<String> dimensionId,
            String name,
            String goal,
            boolean allowReplace,
            long createdAt,
            long updatedAt,
            long revision,
            String lastOperation,
            List<CellSnapshot> cells) {

        public WorkflowSnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(cells, "cells");
            cells = List.copyOf(cells);
        }
    }

    public enum DimensionClaimStatus {
        CLAIMED,
        ALREADY_CURRENT,
        WRONG_DIMENSION,
        UNLOADED,
        MISMATCH,
        LIMIT_EXCEEDED,
        INVALID
    }

    public record DimensionClaimResult(
            DimensionClaimStatus status,
            Optional<WorkflowSnapshot> snapshot,
            int verifiedCells,
            Optional<BlockPos> problem,
            String detail) {

        public DimensionClaimResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(problem, "problem");
            Objects.requireNonNull(detail, "detail");
            problem = problem.map(BlockPos::immutable);
        }

        public boolean usable() {
            return status == DimensionClaimStatus.CLAIMED
                    || status == DimensionClaimStatus.ALREADY_CURRENT;
        }
    }

    /**
     * One absolute desired cell. Minecraft block states are immutable; the
     * position is normalized to an immutable {@link BlockPos}.
     */
    public record CellSnapshot(
            BlockPos position,
            String itemId,
            BlockState desiredState,
            BlockState originalState) {

        public CellSnapshot {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(desiredState, "desiredState");
            position = position.immutable();
        }

        public String blockId() {
            return BuiltInRegistries.BLOCK.getKey(desiredState.getBlock()).toString();
        }

        /** Stable string form of every desired block-state property. */
        public Map<String, String> properties() {
            Map<String, String> result = new LinkedHashMap<>();
            for (Property<?> property : desiredState.getProperties()) {
                result.put(property.getName(), propertyValue(desiredState, property));
            }
            return Map.copyOf(result);
        }

        private static <T extends Comparable<T>> String propertyValue(
                BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }
    }
}
