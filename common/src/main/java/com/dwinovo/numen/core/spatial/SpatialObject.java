package com.dwinovo.numen.core.spatial;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A stable semantic object card backed by exact world voxels. */
public record SpatialObject(
        String id,
        String kind,
        String label,
        double confidence,
        SpatialBounds bounds,
        List<VoxelPos> cells,
        Map<String, Object> features,
        String provenance,
        double provenanceConfidence,
        String protection,
        List<String> relations) {

    public SpatialObject {
        cells = List.copyOf(cells);
        features = Map.copyOf(new LinkedHashMap<>(features));
        relations = List.copyOf(relations);
    }

    public SpatialObject withId(String assignedId) {
        return new SpatialObject(
                assignedId, kind, label, confidence, bounds, cells, features,
                provenance, provenanceConfidence, protection, relations);
    }

    public SpatialObject withRelations(List<String> assignedRelations) {
        return new SpatialObject(
                id, kind, label, confidence, bounds, cells, features,
                provenance, provenanceConfidence, protection, assignedRelations);
    }
}
