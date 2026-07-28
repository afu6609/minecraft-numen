package com.dwinovo.numen.core.tools;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementFeasibilityToolTest {

    @Test
    void schemaSupportsWorkflowOrExplicitBlocks() {
        Map<String, Object> schema =
                new PlacementFeasibilityTool().parameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) schema.get("properties");

        assertTrue(properties.containsKey("workflow_id"));
        assertTrue(properties.containsKey("positions"));
        assertTrue(properties.containsKey("blocks"));
        assertTrue(properties.containsKey("relocation_radius"));
        assertTrue(schema.containsKey("oneOf"));
    }

    @Test
    void candidateFeetNeverUsesReservedTargetFootprints() {
        BlockPos target = new BlockPos(222, 127, -418);
        BlockPos bedHead = target.east();
        BlockPos westWall = target.west();
        Set<BlockPos> excluded = Set.of(target, bedHead, westWall);
        Set<BlockPos> standable = new LinkedHashSet<>(List.of(
                westWall,
                bedHead,
                target.south(),
                target.south(2)));

        List<BlockPos> candidates =
                PlacementFeasibilityTool.candidateFeet(
                        target, excluded, 3, standable::contains);

        assertEquals(List.of(target.south(), target.south(2)), candidates);
        assertFalse(candidates.contains(bedHead));
        assertFalse(candidates.contains(westWall));
    }

    @Test
    void sealedNorthWallIsNotInventedAsAChestStance() {
        BlockPos chest = new BlockPos(224, 127, -418);
        BlockPos northWall = chest.north();
        Set<BlockPos> standable = Set.of(
                northWall, chest.south(), chest.south(2));

        List<BlockPos> candidates =
                PlacementFeasibilityTool.candidateFeet(
                        chest, Set.of(chest, northWall), 3,
                        standable::contains);

        assertEquals(List.of(chest.south(), chest.south(2)), candidates);
    }

    @Test
    void relocationOffsetsAreBoundedAndExcludeOrigin() {
        List<BlockPos> offsets =
                PlacementFeasibilityTool.relocationOffsets(3);

        assertEquals(48, offsets.size());
        assertFalse(offsets.contains(BlockPos.ZERO));
        assertTrue(offsets.stream().allMatch(pos ->
                Math.abs(pos.getX()) <= 3
                        && pos.getY() == 0
                        && Math.abs(pos.getZ()) <= 3));
        assertEquals(1, offsets.get(0).distManhattan(BlockPos.ZERO));
    }

    @Test
    void runtimeAssessmentSeparatesDeterministicAndTransientFailures() {
        PlacementFeasibilityTool.RuntimeAssessment mismatch =
                new PlacementFeasibilityTool.RuntimeAssessment(
                        false, false, "STATE_MISMATCH",
                        "wrong facing", List.of());
        PlacementFeasibilityTool.RuntimeAssessment entity =
                new PlacementFeasibilityTool.RuntimeAssessment(
                        false, false, "BLOCKED_BY_ENTITY",
                        "entity in footprint", List.of());

        assertTrue(mismatch.deterministicFailure());
        assertFalse(mismatch.transientObstacle());
        assertFalse(entity.deterministicFailure());
        assertTrue(entity.transientObstacle());
    }
}
