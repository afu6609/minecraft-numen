package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("mc")
class StructureWorkflowStoreTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable error) {
            booted = false;
        }
    }

    @BeforeEach
    void requireMinecraftBootstrap() {
        assumeTrue(booted, "Minecraft bootstrap unavailable");
    }

    @Test
    void patchPreservesOriginalBeforeAndCapturesBeforeOnlyForNewCells() {
        BlockPos existingPos = new BlockPos(1, 64, 1);
        BlockPos untouchedPos = new BlockPos(2, 64, 1);
        BlockPos newPos = new BlockPos(3, 64, 1);
        StructureWorkflowStore.Workflow original = workflow(7, List.of(
                cell(target(Blocks.STONE, Items.STONE, existingPos), Blocks.DIRT.defaultBlockState()),
                cell(target(Blocks.OAK_PLANKS, Items.OAK_PLANKS, untouchedPos),
                        Blocks.GRASS_BLOCK.defaultBlockState())));
        AtomicInteger worldReads = new AtomicInteger();

        StructureWorkflowStore.Workflow patched = StructureWorkflowStore.patchedCopy(
                original,
                7,
                List.of(
                        target(Blocks.GLASS, Items.GLASS, existingPos),
                        target(Blocks.CHEST, Items.CHEST, newPos)),
                List.of(),
                pos -> {
                    worldReads.incrementAndGet();
                    return Blocks.SAND.defaultBlockState();
                });

        assertEquals(8, patched.revision);
        assertEquals("patch", patched.lastOperation);
        assertEquals(3, patched.cells.size());
        assertEquals(1, worldReads.get(), "only a newly-added coordinate reads the live world");

        StructureWorkflowStore.Cell changed = cellAt(patched, existingPos);
        assertEquals(Blocks.GLASS, changed.desired.toState().getBlock());
        assertEquals(Blocks.DIRT, changed.before.toState().getBlock(),
                "changing the desired state must retain the first saved before state");

        StructureWorkflowStore.Cell untouched = cellAt(patched, untouchedPos);
        assertEquals(Blocks.OAK_PLANKS, untouched.desired.toState().getBlock());
        assertEquals(Blocks.GRASS_BLOCK, untouched.before.toState().getBlock());

        StructureWorkflowStore.Cell added = cellAt(patched, newPos);
        assertEquals(Blocks.CHEST, added.desired.toState().getBlock());
        assertEquals(Blocks.SAND, added.before.toState().getBlock(),
                "new coordinates snapshot the world at patch time");

        assertEquals(7, original.revision);
        assertEquals(Blocks.STONE, cellAt(original, existingPos).desired.toState().getBlock(),
                "constructing a patch must not mutate the prior workflow");
    }

    @Test
    void patchRemovesOnlyTheRequestedSavedCoordinate() {
        BlockPos keep = new BlockPos(1, 64, 1);
        BlockPos remove = new BlockPos(2, 64, 1);
        StructureWorkflowStore.Workflow original = workflow(2, List.of(
                cell(target(Blocks.STONE, Items.STONE, keep), Blocks.AIR.defaultBlockState()),
                cell(target(Blocks.GLASS, Items.GLASS, remove), Blocks.DIRT.defaultBlockState())));

        StructureWorkflowStore.Workflow patched = StructureWorkflowStore.patchedCopy(
                original, 2, List.of(), List.of(remove), pos -> {
                    throw new AssertionError("remove-only patch must not inspect a new before state");
                });

        assertEquals(3, patched.revision);
        assertEquals(1, patched.cells.size());
        assertEquals(keep, patched.cells.get(0).pos());
        assertEquals(2, original.cells.size(), "the previous manifest remains intact");
    }

    @Test
    void rejectsStaleEmptyOverlappingAndAmbiguousPatchesWithoutMutation() {
        BlockPos saved = new BlockPos(4, 70, 4);
        BlockPos absent = new BlockPos(5, 70, 4);
        StructureWorkflowStore.Workflow original = workflow(3, List.of(
                cell(target(Blocks.STONE, Items.STONE, saved), Blocks.AIR.defaultBlockState())));

        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original, 2, List.of(), List.of(saved), pos -> Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original, 3, List.of(), List.of(), pos -> Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original, 3, List.of(target(Blocks.GLASS, Items.GLASS, saved)),
                        List.of(saved), pos -> Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original, 3, List.of(), List.of(saved, saved),
                        pos -> Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original, 3, List.of(), List.of(absent),
                        pos -> Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original, 3,
                        List.of(
                                target(Blocks.GLASS, Items.GLASS, absent),
                                target(Blocks.DIRT, Items.DIRT, absent)),
                        List.of(), pos -> Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original, 3,
                        List.of(target(Blocks.STONE, Items.STONE, saved)),
                        List.of(), pos -> Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original, 3, List.of(), List.of(saved),
                        pos -> Blocks.AIR.defaultBlockState()));

        assertEquals(3, original.revision);
        assertEquals(1, original.cells.size());
        assertEquals(Blocks.STONE, original.cells.get(0).desired.toState().getBlock());
    }

    @Test
    void rejectsOperationAndResultSizeLimits() {
        BlockPos saved = new BlockPos(0, 64, 0);
        StructureWorkflowStore.Workflow oneCell = workflow(1, List.of(
                cell(target(Blocks.STONE, Items.STONE, saved), Blocks.AIR.defaultBlockState())));
        List<BuildTaskRecord.Target> tooManyOperations = new ArrayList<>();
        for (int index = 0; index <= StructurePlanTool.MAX_CELLS; index++) {
            tooManyOperations.add(target(
                    Blocks.STONE, Items.STONE, new BlockPos(index, 64, 1)));
        }
        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        oneCell, 1, tooManyOperations, List.of(),
                        pos -> Blocks.AIR.defaultBlockState()));

        List<StructureWorkflowStore.Cell> maxCells = new ArrayList<>();
        for (int index = 0; index < StructurePlanTool.MAX_CELLS; index++) {
            BlockPos pos = new BlockPos(index, 64, 0);
            maxCells.add(cell(target(Blocks.STONE, Items.STONE, pos),
                    Blocks.AIR.defaultBlockState()));
        }
        StructureWorkflowStore.Workflow full = workflow(9, maxCells);
        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        full,
                        9,
                        List.of(target(
                                Blocks.GLASS,
                                Items.GLASS,
                                new BlockPos(StructurePlanTool.MAX_CELLS, 64, 0))),
                        List.of(),
                        pos -> Blocks.AIR.defaultBlockState()));
        assertEquals(StructurePlanTool.MAX_CELLS, full.cells.size());
    }

    @Test
    void outOfRangePackedCoordinateCannotAliasAndRemoveSavedCell() {
        BlockPos saved = new BlockPos(1, 64, 1);
        BlockPos packedAlias = new BlockPos(
                saved.getX() + (1 << 26), saved.getY(), saved.getZ());
        assertEquals(saved.asLong(), packedAlias.asLong(),
                "the regression fixture must collide in BlockPos packed form");
        StructureWorkflowStore.Workflow original = workflow(4, List.of(
                cell(target(Blocks.STONE, Items.STONE, saved),
                        Blocks.AIR.defaultBlockState())));

        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original,
                        4,
                        List.of(),
                        List.of(packedAlias),
                        pos -> {
                            throw new AssertionError(
                                    "an invalid removal must not read world state");
                        }));
        assertEquals(saved, original.cells.get(0).pos());
    }

    @Test
    void validatesCompleteManifestBeforeReadingNewBeforeState() {
        BlockPos saved = new BlockPos(1, 64, 1);
        BlockPos newPos = new BlockPos(2, 64, 1);
        StructureWorkflowStore.Workflow original = workflow(5, List.of(
                cell(target(Blocks.STONE, Items.STONE, saved),
                        Blocks.AIR.defaultBlockState())));
        AtomicInteger reads = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () ->
                StructureWorkflowStore.patchedCopy(
                        original,
                        5,
                        List.of(target(Blocks.GLASS, Items.GLASS, newPos)),
                        List.of(),
                        pos -> {
                            reads.incrementAndGet();
                            return Blocks.AIR.defaultBlockState();
                        },
                        targets -> {
                            throw new IllegalArgumentException(
                                    "simulated live bounds rejection");
                        }));
        assertEquals(0, reads.get(),
                "rejected coordinates must never be queried from the world");
        assertEquals(1, original.cells.size());
    }

    @Test
    void remainingSamplesExposeExactRequestedStateAndAreBounded() {
        BlockPos bedPos = new BlockPos(10, 65, 10);
        BuildTaskRecord.Target bed = new BuildTaskRecord.Target(
                Blocks.WHITE_BED,
                Items.WHITE_BED,
                bedPos,
                "white_bed",
                Direction.EAST,
                null,
                null);
        BuildTaskRecord.Target stone = target(
                Blocks.STONE, Items.STONE, new BlockPos(11, 65, 10));

        JsonArray samples = StructureWorkflowAssessment.targetSamples(
                List.of(bed), List.of(stone), 1);

        assertEquals(1, samples.size());
        JsonObject sample = samples.get(0).getAsJsonObject();
        assertEquals(10, sample.get("x").getAsInt());
        assertEquals("minecraft:white_bed", sample.get("block_id").getAsString());
        assertEquals("minecraft:white_bed", sample.get("item_id").getAsString());
        assertEquals("east", sample.get("facing").getAsString());
        assertEquals("east",
                sample.getAsJsonObject("state").get("facing").getAsString());
        assertTrue(sample.getAsJsonObject("state").has("part"),
                "all requested block-state properties are returned");
        assertFalse(sample.get("unloaded").getAsBoolean());
    }

    @Test
    void predictedBedHeadCannotBeSuggestedAsThePlayersStance() {
        BlockPos foot = new BlockPos(10, 65, 10);
        BlockState eastFacingBed = Blocks.WHITE_BED.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);

        assertTrue(PlacementFeasibilityTool.stanceIntersectsFootprint(
                foot.east(), foot, eastFacingBed));
        assertTrue(PlacementFeasibilityTool.stanceIntersectsFootprint(
                foot.below(), foot, eastFacingBed),
                "the player's head cell must stay outside the future footprint");
        assertFalse(PlacementFeasibilityTool.stanceIntersectsFootprint(
                foot.south(), foot, eastFacingBed));
    }

    @Test
    void patchSchemaRequiresRevisionAndUsesExplicitRemovePositions() {
        Map<String, Object> schema = new StructurePatchTool().parameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");

        assertTrue(required.contains("workflow_id"));
        assertTrue(required.contains("expected_revision"));
        assertTrue(properties.containsKey("upsert"));
        assertTrue(properties.containsKey("remove_positions"));
        assertFalse(properties.containsKey("remove"));
    }

    @Test
    void patchRuntimeValidationRejectsMissingCoordinatesAndMetadata() {
        JsonObject missingCoordinate = JsonParser.parseString("""
                {
                  "workflow_id": "structure-test",
                  "expected_revision": 1,
                  "remove_positions": [{}]
                }
                """).getAsJsonObject();
        JsonObject extraMetadata = JsonParser.parseString("""
                {
                  "workflow_id": "structure-test",
                  "expected_revision": 1,
                  "upsert": [{
                    "block_id": "minecraft:stone",
                    "x": 1,
                    "y": 64,
                    "z": 1
                  }],
                  "repair_kind": "RELOCATE"
                }
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () ->
                StructurePatchTool.validateRawArgs(missingCoordinate));
        assertThrows(IllegalArgumentException.class, () ->
                StructurePatchTool.validateRawArgs(extraMetadata));
    }

    private static StructureWorkflowStore.Workflow workflow(
            long revision, List<StructureWorkflowStore.Cell> cells) {
        StructureWorkflowStore.Workflow workflow = new StructureWorkflowStore.Workflow();
        workflow.id = "structure-test";
        workflow.ownerUuid = "owner";
        workflow.ownerName = "Momo";
        workflow.name = "test";
        workflow.goal = "test patch semantics";
        workflow.allowReplace = false;
        workflow.createdAt = 10;
        workflow.updatedAt = 20;
        workflow.revision = revision;
        workflow.lastOperation = "plan";
        workflow.cells = new ArrayList<>(cells);
        return workflow;
    }

    private static StructureWorkflowStore.Cell cell(
            BuildTaskRecord.Target target, BlockState before) {
        return StructureWorkflowStore.Cell.from(
                target, StructureWorkflowStore.StateSpec.from(before));
    }

    private static StructureWorkflowStore.Cell cellAt(
            StructureWorkflowStore.Workflow workflow, BlockPos pos) {
        return workflow.cells.stream()
                .filter(cell -> cell.pos().equals(pos))
                .findFirst()
                .orElseThrow();
    }

    private static BuildTaskRecord.Target target(
            Block block, Item item, BlockPos pos) {
        String label = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return new BuildTaskRecord.Target(
                block, item, pos, label, null, null, null);
    }
}
