package com.dwinovo.numen.core.spatial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-companion bounded scene history with stable object identity reconciliation.
 */
public final class SpatialSceneStore {

    private static final int HISTORY_LIMIT = 4;
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private SpatialSceneStore() {}

    public static SpatialScene save(
            UUID companion,
            String anchorSource,
            SpatialSnapshot snapshot,
            List<SpatialObject> drafts) {
        if (companion == null) throw new IllegalArgumentException("companion is required");
        State state = STATES.computeIfAbsent(companion, ignored -> new State());
        synchronized (state) {
            SpatialScene previous = state.history.isEmpty()
                    ? null
                    : state.history.values().stream().reduce((a, b) -> b).orElse(null);
            List<SpatialObject> assigned = reconcile(state, previous, drafts);
            long revision = ++state.revision;
            String sceneId = "scene-" + companion.toString().substring(0, 8) + "-" + revision;
            SpatialScene scene = new SpatialScene(
                    sceneId,
                    revision,
                    anchorSource,
                    snapshot.anchor(),
                    snapshot.bounds(),
                    snapshot.cells().size(),
                    snapshot.unloadedColumns(),
                    assigned);
            state.history.put(sceneId, scene);
            while (state.history.size() > HISTORY_LIMIT) {
                String oldest = state.history.keySet().iterator().next();
                state.history.remove(oldest);
            }
            return scene;
        }
    }

    public static SpatialScene latest(UUID companion) {
        State state = STATES.get(companion);
        if (state == null) return null;
        synchronized (state) {
            return state.history.values().stream().reduce((a, b) -> b).orElse(null);
        }
    }

    public static SpatialScene get(UUID companion, String sceneId) {
        State state = STATES.get(companion);
        if (state == null) return null;
        synchronized (state) {
            if (sceneId == null || sceneId.isBlank()) {
                return state.history.values().stream().reduce((a, b) -> b).orElse(null);
            }
            return state.history.get(sceneId);
        }
    }

    static void clearForTests() {
        STATES.clear();
    }

    private static List<SpatialObject> reconcile(
            State state,
            SpatialScene previous,
            List<SpatialObject> drafts) {
        List<SpatialObject> oldObjects =
                previous == null ? List.of() : previous.objects();
        Set<String> usedOldIds = new HashSet<>();
        Map<String, String> draftToAssigned = new HashMap<>();
        List<SpatialObject> assigned = new ArrayList<>();

        for (SpatialObject draft : drafts) {
            SpatialObject best = null;
            double bestScore = 0.0;
            for (SpatialObject old : oldObjects) {
                if (usedOldIds.contains(old.id()) || !old.kind().equals(draft.kind())) continue;
                double score = matchScore(old, draft);
                if (score > bestScore) {
                    best = old;
                    bestScore = score;
                }
            }
            String id;
            if (best != null && bestScore >= 0.36) {
                id = best.id();
                usedOldIds.add(id);
            } else {
                int ordinal = state.nextByKind.merge(draft.kind(), 1, Integer::sum);
                id = safeKind(draft.kind()) + "-" + ordinal;
            }
            draftToAssigned.put(draft.id(), id);
            assigned.add(draft.withId(id));
        }

        List<SpatialObject> rewritten = new ArrayList<>(assigned.size());
        for (SpatialObject object : assigned) {
            List<String> relations = object.relations().stream()
                    .map(relation -> rewriteRelation(relation, draftToAssigned))
                    .toList();
            rewritten.add(object.withRelations(relations));
        }
        return List.copyOf(rewritten);
    }

    private static double matchScore(SpatialObject old, SpatialObject fresh) {
        int intersection = old.bounds().intersectionVolume(fresh.bounds());
        int smaller = Math.max(1, Math.min(old.bounds().volume(), fresh.bounds().volume()));
        double overlap = intersection / (double) smaller;
        double distance = old.bounds().centerDistance(fresh.bounds());
        double proximity = Math.max(0.0, 1.0 - distance / 8.0);
        Set<VoxelPos> oldCells = new HashSet<>(old.cells());
        long sharedCells = fresh.cells().stream().filter(oldCells::contains).count();
        double cellOverlap = sharedCells / (double)
                Math.max(1, Math.min(old.cells().size(), fresh.cells().size()));
        return 0.45 * overlap + 0.35 * cellOverlap + 0.20 * proximity;
    }

    private static String rewriteRelation(
            String relation,
            Map<String, String> draftToAssigned) {
        int separator = relation.indexOf(':');
        if (separator < 0) return relation;
        String target = relation.substring(separator + 1);
        String stableTarget = draftToAssigned.get(target);
        return stableTarget == null
                ? relation
                : relation.substring(0, separator + 1) + stableTarget;
    }

    private static String safeKind(String kind) {
        return kind.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    private static final class State {
        private long revision;
        private final Map<String, Integer> nextByKind = new HashMap<>();
        private final LinkedHashMap<String, SpatialScene> history = new LinkedHashMap<>();
    }
}
