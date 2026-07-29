package com.dwinovo.numen.core.spatial;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic first-pass scene understanding.
 *
 * <p>The analyzer never asks a model to guess geometry. It groups exact voxels,
 * measures them, then attaches deliberately conservative semantic labels and
 * confidence values.
 */
public final class SpatialSceneAnalyzer {

    private static final int[][] NEIGHBORS_26 = neighbors26();
    private static final int[][] NEIGHBORS_8 = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1},             {0, 1},
            {1, -1},  {1, 0},    {1, 1}
    };

    public List<SpatialObject> analyze(SpatialSnapshot snapshot) {
        List<SpatialObject> objects = new ArrayList<>();
        objects.addAll(findTrees(snapshot));
        objects.addAll(findBuildings(snapshot));
        objects.addAll(findTerrainShapes(snapshot));
        objects.sort(Comparator
                .comparing(SpatialObject::kind)
                .thenComparingInt(object -> object.bounds().minX())
                .thenComparingInt(object -> object.bounds().minY())
                .thenComparingInt(object -> object.bounds().minZ()));
        return List.copyOf(objects);
    }

    private List<SpatialObject> findTrees(SpatialSnapshot snapshot) {
        Map<VoxelPos, VoxelCell> logs = indexed(snapshot.cellsWith(VoxelCell.LOG));
        List<VoxelCell> leaves = snapshot.cellsWith(VoxelCell.LEAF);
        Set<VoxelPos> unseen = new LinkedHashSet<>(logs.keySet());
        List<SpatialObject> trees = new ArrayList<>();
        int sequence = 0;

        while (!unseen.isEmpty()) {
            VoxelPos seed = unseen.iterator().next();
            List<VoxelPos> trunk = takeComponent(seed, unseen, logs.keySet(), true);
            if (trunk.size() < 2) continue;

            SpatialBounds trunkBounds = SpatialBounds.of(trunk);
            List<VoxelPos> canopy = new ArrayList<>();
            int persistent = 0;
            for (VoxelCell leaf : leaves) {
                if (!nearBounds(leaf.pos(), trunkBounds, 4)) continue;
                boolean attached = false;
                for (VoxelPos log : trunk) {
                    if (leaf.pos().chebyshevDistance(log) <= 3) {
                        attached = true;
                        break;
                    }
                }
                if (!attached) continue;
                canopy.add(leaf.pos());
                if (leaf.persistentLeaves()) persistent++;
            }

            int verticalSpan = trunkBounds.sizeY();
            boolean grounded = trunk.stream().anyMatch(log -> {
                VoxelCell below = snapshot.cell(new VoxelPos(log.x(), log.y() - 1, log.z()));
                return below != null && below.has(VoxelCell.GROUND);
            });
            if (canopy.size() < 3 || verticalSpan < 2) continue;

            List<VoxelPos> cells = new ArrayList<>(trunk);
            cells.addAll(canopy);
            SpatialBounds bounds = SpatialBounds.of(cells);
            double confidence = 0.50;
            if (grounded) confidence += 0.15;
            if (verticalSpan >= 3) confidence += 0.12;
            if (canopy.size() >= trunk.size()) confidence += 0.12;
            if (persistent == 0) confidence += 0.06;
            confidence = Math.min(0.95, confidence);

            Map<String, Object> features = new LinkedHashMap<>();
            features.put("log_blocks", trunk.size());
            features.put("leaf_blocks", canopy.size());
            features.put("persistent_leaf_blocks", persistent);
            features.put("vertical_trunk_span", verticalSpan);
            features.put("ground_contact", grounded);
            features.put("natural_leaf_decay_signal", persistent == 0);
            trees.add(object(
                    "draft-tree-" + sequence++, "tree", "tree",
                    confidence, cells, features,
                    persistent == 0 ? "natural_or_sapling_grown" : "unknown_or_decorative",
                    persistent == 0 ? 0.65 : 0.30,
                    "normal_resource", List.of()));
        }
        return trees;
    }

    private List<SpatialObject> findBuildings(SpatialSnapshot snapshot) {
        Map<VoxelPos, VoxelCell> constructed =
                indexed(snapshot.cellsWith(VoxelCell.CONSTRUCTED));
        Set<VoxelPos> unseen = new LinkedHashSet<>(constructed.keySet());
        List<SpatialObject> structures = new ArrayList<>();
        int buildingSequence = 0;
        int entranceSequence = 0;

        while (!unseen.isEmpty()) {
            VoxelPos seed = unseen.iterator().next();
            List<VoxelPos> component =
                    takeComponent(seed, unseen, constructed.keySet(), true);
            if (component.size() < 8) continue;

            List<VoxelPos> doors = component.stream()
                    .filter(pos -> constructed.get(pos).has(VoxelCell.DOOR))
                    .toList();
            SpatialBounds bounds = SpatialBounds.of(component);
            if (doors.isEmpty() && component.size() < 24) continue;
            if (bounds.sizeY() < 2 || Math.max(bounds.sizeX(), bounds.sizeZ()) < 3) {
                continue;
            }

            List<VoxelPos> furniture = snapshot.cellsWith(VoxelCell.FURNITURE).stream()
                    .map(VoxelCell::pos)
                    .filter(pos -> insideExpanded(pos, bounds, 1))
                    .toList();
            int glass = (int) component.stream()
                    .filter(pos -> constructed.get(pos).has(VoxelCell.GLASS))
                    .count();
            int enclosedAir = countEnclosedAir(snapshot, bounds);
            double roofCoverage = roofCoverage(component, bounds);
            boolean roomLike = enclosedAir >= 4 || (roofCoverage >= 0.35 && !doors.isEmpty());

            double confidence = 0.38;
            if (!doors.isEmpty()) confidence += 0.22;
            if (roomLike) confidence += 0.18;
            if (roofCoverage >= 0.35) confidence += 0.10;
            if (!furniture.isEmpty()) confidence += 0.08;
            confidence = Math.min(0.96, confidence);

            String buildingDraftId = "draft-building-" + buildingSequence++;
            Map<String, Object> features = new LinkedHashMap<>();
            features.put("constructed_blocks", component.size());
            features.put("entrance_blocks", doors.size());
            features.put("glass_blocks", glass);
            features.put("furniture_blocks", furniture.size());
            features.put("enclosed_air_cells", enclosedAir);
            features.put("roof_coverage", round2(roofCoverage));
            features.put("room_like", roomLike);
            features.put("entrances", doors.stream().map(SpatialSceneAnalyzer::coordinate).toList());
            features.put("attribution_note",
                    "No placement ledger exists for old blocks; creator identity is not known.");

            boolean probablyBuilt = !doors.isEmpty() && roomLike;
            structures.add(object(
                    buildingDraftId,
                    probablyBuilt ? "building" : "constructed_structure",
                    probablyBuilt ? "enclosed building candidate" : "constructed structure",
                    confidence, component, features,
                    probablyBuilt ? "probably_player_built" : "unknown",
                    probablyBuilt ? 0.72 : 0.35,
                    "preserve_by_default", List.of()));

            Set<VoxelPos> lowerDoors = new LinkedHashSet<>();
            for (VoxelPos door : doors) {
                VoxelCell below = constructed.get(new VoxelPos(
                        door.x(), door.y() - 1, door.z()));
                if (below == null || !below.has(VoxelCell.DOOR)) lowerDoors.add(door);
            }
            for (VoxelPos door : lowerDoors) {
                Map<String, Object> entranceFeatures = new LinkedHashMap<>();
                entranceFeatures.put("door_block", constructed.get(door).blockId());
                entranceFeatures.put("position", coordinate(door));
                structures.add(object(
                        "draft-entrance-" + entranceSequence++,
                        "entrance", "doorway entrance", 0.98,
                        List.of(door), entranceFeatures,
                        "part_of_constructed_structure", 0.95,
                        "preserve_by_default",
                        List.of("entrance_of:" + buildingDraftId)));
            }
        }
        return structures;
    }

    private List<SpatialObject> findTerrainShapes(SpatialSnapshot snapshot) {
        Map<Long, Integer> heights = new HashMap<>();
        for (VoxelCell cell : snapshot.cellsWith(VoxelCell.GROUND)) {
            long column = columnKey(cell.pos().x(), cell.pos().z());
            heights.merge(column, cell.pos().y(), Math::max);
        }
        if (heights.size() < 16) return List.of();

        Set<VoxelPos> depressions = new LinkedHashSet<>();
        Set<VoxelPos> protrusions = new LinkedHashSet<>();
        Map<VoxelPos, Integer> deltas = new HashMap<>();
        SpatialBounds scan = snapshot.bounds();

        for (int x = scan.minX(); x <= scan.maxX(); x++) {
            for (int z = scan.minZ(); z <= scan.maxZ(); z++) {
                Integer height = heights.get(columnKey(x, z));
                if (height == null) continue;
                List<Integer> ring = new ArrayList<>();
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        int distance = Math.max(Math.abs(dx), Math.abs(dz));
                        if (distance < 2 || distance > 3) continue;
                        Integer around = heights.get(columnKey(x + dx, z + dz));
                        if (around != null) ring.add(around);
                    }
                }
                if (ring.size() < 6) continue;
                ring.sort(Integer::compareTo);
                int baseline = ring.get(ring.size() / 2);
                int delta = baseline - height;
                VoxelPos surface = new VoxelPos(x, height, z);
                if (delta >= 2) {
                    depressions.add(surface);
                    deltas.put(surface, delta);
                } else if (delta <= -2) {
                    protrusions.add(surface);
                    deltas.put(surface, -delta);
                }
            }
        }

        List<SpatialObject> objects = new ArrayList<>();
        addTerrainComponents(
                objects, depressions, deltas,
                "terrain_depression", "pit or terrain depression", "depth");
        addTerrainComponents(
                objects, protrusions, deltas,
                "ground_protrusion", "ground mound or sharp terrain rise", "height");
        return objects;
    }

    private void addTerrainComponents(
            List<SpatialObject> output,
            Set<VoxelPos> candidates,
            Map<VoxelPos, Integer> deltas,
            String kind,
            String label,
            String measurement) {
        Set<VoxelPos> unseen = new LinkedHashSet<>(candidates);
        int sequence = 0;
        while (!unseen.isEmpty()) {
            VoxelPos seed = unseen.iterator().next();
            List<VoxelPos> component = takeSurfaceComponent(seed, unseen, candidates);
            if (component.size() < 2) continue;
            int maximum = component.stream().mapToInt(pos -> deltas.getOrDefault(pos, 0)).max()
                    .orElse(0);
            if (maximum < 2) continue;
            Map<String, Object> features = new LinkedHashMap<>();
            features.put("surface_cells", component.size());
            features.put("max_" + measurement, maximum);
            features.put("classification_note",
                    "Geometry is exact; whether this shape is natural or unwanted is unknown.");
            double confidence = Math.min(0.88, 0.52 + component.size() * 0.02 + maximum * 0.04);
            output.add(object(
                    "draft-" + kind + "-" + sequence++,
                    kind, label, confidence, component, features,
                    "unknown", 0.10,
                    "inspect_before_edit", List.of()));
        }
    }

    private static int countEnclosedAir(SpatialSnapshot snapshot, SpatialBounds bounds) {
        if (bounds.volume() > 4_096 || bounds.sizeX() < 3
                || bounds.sizeY() < 3 || bounds.sizeZ() < 3) {
            return 0;
        }
        Set<VoxelPos> empty = new HashSet<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    VoxelPos pos = new VoxelPos(x, y, z);
                    VoxelCell cell = snapshot.cell(pos);
                    if (cell == null || cell.has(VoxelCell.FLUID)) empty.add(pos);
                }
            }
        }
        Set<VoxelPos> outside = new HashSet<>();
        ArrayDeque<VoxelPos> queue = new ArrayDeque<>();
        for (VoxelPos pos : empty) {
            if (onBoundary(pos, bounds)) {
                outside.add(pos);
                queue.add(pos);
            }
        }
        int[][] directions = {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
                {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
        };
        while (!queue.isEmpty()) {
            VoxelPos current = queue.removeFirst();
            for (int[] d : directions) {
                VoxelPos next = new VoxelPos(
                        current.x() + d[0], current.y() + d[1], current.z() + d[2]);
                if (empty.contains(next) && outside.add(next)) queue.add(next);
            }
        }
        return empty.size() - outside.size();
    }

    private static double roofCoverage(List<VoxelPos> component, SpatialBounds bounds) {
        int footprint = bounds.sizeX() * bounds.sizeZ();
        if (footprint <= 0) return 0.0;
        int roofFloor = bounds.maxY() - Math.max(1, bounds.sizeY() / 3);
        Set<Long> covered = new HashSet<>();
        for (VoxelPos pos : component) {
            if (pos.y() >= roofFloor) covered.add(columnKey(pos.x(), pos.z()));
        }
        return Math.min(1.0, covered.size() / (double) footprint);
    }

    private static SpatialObject object(
            String id,
            String kind,
            String label,
            double confidence,
            List<VoxelPos> cells,
            Map<String, Object> features,
            String provenance,
            double provenanceConfidence,
            String protection,
            List<String> relations) {
        return new SpatialObject(
                id, kind, label, round2(confidence), SpatialBounds.of(cells), cells,
                features, provenance, round2(provenanceConfidence), protection, relations);
    }

    private static Map<VoxelPos, VoxelCell> indexed(List<VoxelCell> cells) {
        Map<VoxelPos, VoxelCell> index = new LinkedHashMap<>();
        for (VoxelCell cell : cells) index.put(cell.pos(), cell);
        return index;
    }

    private static List<VoxelPos> takeComponent(
            VoxelPos seed,
            Set<VoxelPos> unseen,
            Set<VoxelPos> allowed,
            boolean diagonal) {
        List<VoxelPos> component = new ArrayList<>();
        ArrayDeque<VoxelPos> queue = new ArrayDeque<>();
        unseen.remove(seed);
        queue.add(seed);
        int[][] neighbors = diagonal ? NEIGHBORS_26 : new int[][] {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
                {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
        };
        while (!queue.isEmpty()) {
            VoxelPos current = queue.removeFirst();
            component.add(current);
            for (int[] d : neighbors) {
                VoxelPos next = new VoxelPos(
                        current.x() + d[0], current.y() + d[1], current.z() + d[2]);
                if (allowed.contains(next) && unseen.remove(next)) queue.add(next);
            }
        }
        return component;
    }

    private static List<VoxelPos> takeSurfaceComponent(
            VoxelPos seed,
            Set<VoxelPos> unseen,
            Set<VoxelPos> allowed) {
        List<VoxelPos> component = new ArrayList<>();
        ArrayDeque<VoxelPos> queue = new ArrayDeque<>();
        unseen.remove(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            VoxelPos current = queue.removeFirst();
            component.add(current);
            for (int[] d : NEIGHBORS_8) {
                for (int dy = -2; dy <= 2; dy++) {
                    VoxelPos next = new VoxelPos(
                            current.x() + d[0], current.y() + dy, current.z() + d[1]);
                    if (allowed.contains(next) && unseen.remove(next)) queue.add(next);
                }
            }
        }
        return component;
    }

    private static boolean nearBounds(VoxelPos pos, SpatialBounds bounds, int distance) {
        return pos.x() >= bounds.minX() - distance && pos.x() <= bounds.maxX() + distance
                && pos.y() >= bounds.minY() - distance && pos.y() <= bounds.maxY() + distance
                && pos.z() >= bounds.minZ() - distance && pos.z() <= bounds.maxZ() + distance;
    }

    private static boolean insideExpanded(VoxelPos pos, SpatialBounds bounds, int distance) {
        return nearBounds(pos, bounds, distance);
    }

    private static boolean onBoundary(VoxelPos pos, SpatialBounds bounds) {
        return pos.x() == bounds.minX() || pos.x() == bounds.maxX()
                || pos.y() == bounds.minY() || pos.y() == bounds.maxY()
                || pos.z() == bounds.minZ() || pos.z() == bounds.maxZ();
    }

    private static String coordinate(VoxelPos pos) {
        return pos.x() + "," + pos.y() + "," + pos.z();
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int[][] neighbors26() {
        List<int[]> result = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) result.add(new int[] {dx, dy, dz});
                }
            }
        }
        return result.toArray(int[][]::new);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
