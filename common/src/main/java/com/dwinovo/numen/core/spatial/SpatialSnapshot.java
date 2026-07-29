package com.dwinovo.numen.core.spatial;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, immutable-enough voxel snapshot captured on the Minecraft server thread. */
public final class SpatialSnapshot {

    private final VoxelPos anchor;
    private final SpatialBounds bounds;
    private final Map<VoxelPos, VoxelCell> cells;
    private final int unloadedColumns;

    public SpatialSnapshot(
            VoxelPos anchor,
            SpatialBounds bounds,
            Collection<VoxelCell> cells,
            int unloadedColumns) {
        this.anchor = anchor;
        this.bounds = bounds;
        Map<VoxelPos, VoxelCell> indexed = new LinkedHashMap<>();
        for (VoxelCell cell : cells) indexed.put(cell.pos(), cell);
        this.cells = Map.copyOf(indexed);
        this.unloadedColumns = unloadedColumns;
    }

    public VoxelPos anchor() {
        return anchor;
    }

    public SpatialBounds bounds() {
        return bounds;
    }

    public Collection<VoxelCell> cells() {
        return cells.values();
    }

    public VoxelCell cell(VoxelPos pos) {
        return cells.get(pos);
    }

    public List<VoxelCell> cellsWith(int flag) {
        return cells.values().stream().filter(cell -> cell.has(flag)).toList();
    }

    public int unloadedColumns() {
        return unloadedColumns;
    }
}
