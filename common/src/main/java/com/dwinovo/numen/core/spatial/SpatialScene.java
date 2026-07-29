package com.dwinovo.numen.core.spatial;

import java.util.List;

/** One bounded scene-graph revision. */
public record SpatialScene(
        String id,
        long revision,
        String anchorSource,
        VoxelPos anchor,
        SpatialBounds bounds,
        int sampledCells,
        int unloadedColumns,
        List<SpatialObject> objects) {

    public SpatialScene {
        objects = List.copyOf(objects);
    }
}
