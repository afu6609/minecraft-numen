package com.dwinovo.numen.core.spatial;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialSceneAnalyzerTest {

    private final SpatialSceneAnalyzer analyzer = new SpatialSceneAnalyzer();

    @AfterEach
    void clearStore() {
        SpatialSceneStore.clearForTests();
    }

    @Test
    void recognizesTreeAndKeepsItsIdAcrossSmallCanopyChange() {
        List<VoxelCell> cells = new ArrayList<>();
        cells.add(cell(0, 0, 0, VoxelCell.GROUND));
        for (int y = 1; y <= 4; y++) {
            cells.add(cell(0, y, 0, VoxelCell.LOG));
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) + Math.abs(z) <= 3) {
                    cells.add(cell(x, 4, z, VoxelCell.LEAF));
                }
            }
        }
        SpatialSnapshot firstSnapshot = snapshot(cells);
        List<SpatialObject> firstObjects = analyzer.analyze(firstSnapshot);
        SpatialObject tree = firstObjects.stream()
                .filter(object -> object.kind().equals("tree"))
                .findFirst()
                .orElseThrow();
        assertTrue(tree.confidence() >= 0.8);
        assertEquals(true, tree.features().get("ground_contact"));

        UUID companion = UUID.randomUUID();
        SpatialScene first = SpatialSceneStore.save(
                companion, "test", firstSnapshot, firstObjects);
        cells.add(cell(2, 5, 0, VoxelCell.LEAF));
        SpatialSnapshot secondSnapshot = snapshot(cells);
        SpatialScene second = SpatialSceneStore.save(
                companion, "test", secondSnapshot, analyzer.analyze(secondSnapshot));

        String firstId = first.objects().stream()
                .filter(object -> object.kind().equals("tree"))
                .findFirst().orElseThrow().id();
        String secondId = second.objects().stream()
                .filter(object -> object.kind().equals("tree"))
                .findFirst().orElseThrow().id();
        assertEquals(firstId, secondId);
    }

    @Test
    void recognizesEnclosedBuildingAndLinksDoorway() {
        List<VoxelCell> cells = new ArrayList<>();
        int constructed = VoxelCell.CONSTRUCTED;
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                cells.add(cell(x, 0, z, constructed));
                cells.add(cell(x, 3, z, constructed));
                if (x == 0 || x == 4 || z == 0 || z == 4) {
                    cells.add(cell(x, 1, z, constructed));
                    cells.add(cell(x, 2, z, constructed));
                }
            }
        }
        replace(cells, cell(2, 1, 0, constructed | VoxelCell.DOOR));
        replace(cells, cell(2, 2, 0, constructed | VoxelCell.DOOR));
        cells.add(cell(
                1, 1, 1,
                constructed | VoxelCell.FURNITURE));

        SpatialSnapshot snapshot = snapshot(cells);
        List<SpatialObject> objects = analyzer.analyze(snapshot);
        SpatialObject building = objects.stream()
                .filter(object -> object.kind().equals("building"))
                .findFirst()
                .orElseThrow();
        SpatialObject entrance = objects.stream()
                .filter(object -> object.kind().equals("entrance"))
                .findFirst()
                .orElseThrow();
        assertTrue((Boolean) building.features().get("room_like"));
        assertTrue((Integer) building.features().get("enclosed_air_cells") > 0);
        assertEquals("preserve_by_default", building.protection());

        SpatialScene scene = SpatialSceneStore.save(
                UUID.randomUUID(), "test", snapshot, objects);
        SpatialObject stableBuilding = scene.objects().stream()
                .filter(object -> object.kind().equals("building"))
                .findFirst().orElseThrow();
        SpatialObject stableEntrance = scene.objects().stream()
                .filter(object -> object.kind().equals("entrance"))
                .findFirst().orElseThrow();
        assertEquals(
                List.of("entrance_of:" + stableBuilding.id()),
                stableEntrance.relations());
        assertFalse(stableEntrance.id().startsWith("draft-"));
        assertNotNull(entrance);
    }

    @Test
    void findsPitAndMoundAsGeometryWithoutInventingProvenance() {
        List<VoxelCell> cells = new ArrayList<>();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                int y = 10;
                if (x >= -2 && x <= 1 && z >= -2 && z <= 1) y = 6;
                if (x >= 5 && x <= 6 && z >= 5 && z <= 6) y = 13;
                cells.add(cell(x, y, z, VoxelCell.GROUND));
            }
        }
        List<SpatialObject> objects = analyzer.analyze(snapshot(cells));
        SpatialObject pit = objects.stream()
                .filter(object -> object.kind().equals("terrain_depression"))
                .findFirst().orElseThrow();
        SpatialObject mound = objects.stream()
                .filter(object -> object.kind().equals("ground_protrusion"))
                .findFirst().orElseThrow();
        assertTrue((Integer) pit.features().get("max_depth") >= 4);
        assertTrue((Integer) mound.features().get("max_height") >= 3);
        assertEquals("unknown", pit.provenance());
        assertEquals("inspect_before_edit", mound.protection());
    }

    private static SpatialSnapshot snapshot(List<VoxelCell> cells) {
        return new SpatialSnapshot(
                new VoxelPos(0, 10, 0),
                new SpatialBounds(-10, -2, -10, 10, 22, 10),
                cells,
                0);
    }

    private static VoxelCell cell(int x, int y, int z, int flags) {
        return new VoxelCell(
                new VoxelPos(x, y, z),
                (flags & VoxelCell.LOG) != 0 ? "minecraft:oak_log"
                        : (flags & VoxelCell.LEAF) != 0 ? "minecraft:oak_leaves"
                        : (flags & VoxelCell.DOOR) != 0 ? "minecraft:oak_door"
                        : "minecraft:stone",
                flags,
                false);
    }

    private static void replace(List<VoxelCell> cells, VoxelCell replacement) {
        cells.removeIf(cell -> cell.pos().equals(replacement.pos()));
        cells.add(replacement);
    }
}
