package com.dwinovo.numen.core.task.survival.shelter;

import com.dwinovo.numen.core.tools.StructureWorkflowSnapshots;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterAnalyzerTest {

    @BeforeAll
    static void bootMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void derivesRectangularHouseDoorInteriorAndAmenities() {
        ShelterAnalysis analysis = ShelterAnalyzer.analyze(smallHouse());

        assertEquals(
                ShelterAnalysis.Outcome.SUPPORTED_RECTANGULAR,
                analysis.outcome());
        ShelterBlueprint shelter = analysis.blueprint().orElseThrow();
        assertEquals(
                new ShelterBlueprint.Bounds(0, 64, 0, 4, 68, 4),
                shelter.bounds());
        assertEquals(64, shelter.floorY());
        assertEquals(68, shelter.roofY());
        assertEquals(1.0, shelter.shellPlanCoverage());

        ShelterBlueprint.Doorway door = shelter.doors().get(0);
        assertEquals(new BlockPos(2, 65, 0), door.lower());
        assertEquals(Direction.SOUTH, door.inward());
        assertEquals(new BlockPos(2, 65, 1), door.inside());
        assertEquals(new BlockPos(2, 65, -1), door.outside());

        assertEquals(List.of(new BlockPos(1, 65, 2)),
                shelter.amenities().beds());
        assertEquals(List.of(new BlockPos(3, 65, 3)),
                shelter.amenities().torches());
        assertTrue(shelter.amenities().lightSources()
                .contains(new BlockPos(3, 65, 3)));
        assertFalse(shelter.interiorStances()
                .contains(new BlockPos(1, 65, 2)));
        assertFalse(shelter.interiorStances()
                .contains(new BlockPos(2, 65, 2)));
        assertTrue(shelter.interiorStances()
                .contains(new BlockPos(2, 65, 1)));
    }

    @Test
    void rejectsSparseOpenPlatformInsteadOfCallingItShelter() {
        List<StructureWorkflowSnapshots.CellSnapshot> cells =
                new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                cells.add(cell(new BlockPos(x, 64, z),
                        Blocks.OAK_PLANKS.defaultBlockState(),
                        "minecraft:oak_planks"));
            }
        }
        // One door makes the bounds tall enough, but there are no walls or roof.
        cells.add(cell(
                new BlockPos(2, 65, 0),
                lowerOakDoor(),
                "minecraft:oak_door"));
        cells.add(cell(
                new BlockPos(0, 68, 0),
                Blocks.OAK_PLANKS.defaultBlockState(),
                "minecraft:oak_planks"));

        ShelterAnalysis analysis = ShelterAnalyzer.analyze(snapshot(cells));

        assertEquals(ShelterAnalysis.Outcome.NOT_A_SHELTER, analysis.outcome());
        assertTrue(analysis.blueprint().isEmpty());
        assertTrue(analysis.reasons().get(0).contains("coverage"));
    }

    @Test
    void refusesLegacyDimensionlessWorkflowUntilAuthoritativelyMigrated() {
        StructureWorkflowSnapshots.WorkflowSnapshot house = smallHouse();
        StructureWorkflowSnapshots.WorkflowSnapshot legacy =
                new StructureWorkflowSnapshots.WorkflowSnapshot(
                        house.id(),
                        house.ownerUuid(),
                        house.ownerName(),
                        Optional.empty(),
                        house.name(),
                        house.goal(),
                        house.allowReplace(),
                        house.createdAt(),
                        house.updatedAt(),
                        house.revision(),
                        house.lastOperation(),
                        house.cells());

        ShelterAnalysis analysis = ShelterAnalyzer.analyze(legacy);

        assertEquals(ShelterAnalysis.Outcome.NOT_A_SHELTER, analysis.outcome());
        assertTrue(analysis.reasons().get(0).contains("dimension"));
    }

    static StructureWorkflowSnapshots.WorkflowSnapshot smallHouse() {
        List<StructureWorkflowSnapshots.CellSnapshot> cells =
                new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                cells.add(cell(
                        new BlockPos(x, 64, z),
                        Blocks.STONE.defaultBlockState(),
                        "minecraft:stone"));
                cells.add(cell(
                        new BlockPos(x, 68, z),
                        Blocks.OAK_PLANKS.defaultBlockState(),
                        "minecraft:oak_planks"));
            }
        }
        for (int y = 65; y <= 67; y++) {
            for (int x = 0; x <= 4; x++) {
                addWallUnlessDoor(cells, new BlockPos(x, y, 0));
                addWallUnlessDoor(cells, new BlockPos(x, y, 4));
            }
            for (int z = 1; z < 4; z++) {
                addWallUnlessDoor(cells, new BlockPos(0, y, z));
                addWallUnlessDoor(cells, new BlockPos(4, y, z));
            }
        }
        // Only the lower cell is saved, matching structure_plan's atomic-footprint contract.
        cells.add(cell(
                new BlockPos(2, 65, 0),
                lowerOakDoor(),
                "minecraft:oak_door"));
        BlockState bed = Blocks.RED_BED.defaultBlockState()
                .setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        cells.add(cell(
                new BlockPos(1, 65, 2),
                bed,
                "minecraft:red_bed"));
        cells.add(cell(
                new BlockPos(3, 65, 3),
                Blocks.TORCH.defaultBlockState(),
                "minecraft:torch"));
        return snapshot(cells);
    }

    private static void addWallUnlessDoor(
            List<StructureWorkflowSnapshots.CellSnapshot> cells,
            BlockPos pos) {
        if (pos.equals(new BlockPos(2, 65, 0))
                || pos.equals(new BlockPos(2, 66, 0))) {
            return;
        }
        cells.add(cell(
                pos,
                Blocks.OAK_PLANKS.defaultBlockState(),
                "minecraft:oak_planks"));
    }

    private static BlockState lowerOakDoor() {
        return Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                        DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, false);
    }

    private static StructureWorkflowSnapshots.WorkflowSnapshot snapshot(
            List<StructureWorkflowSnapshots.CellSnapshot> cells) {
        return new StructureWorkflowSnapshots.WorkflowSnapshot(
                "house-1",
                "owner-uuid",
                "momo",
                Optional.of("minecraft:overworld"),
                "small house",
                "safe furnished house",
                false,
                1,
                2,
                3,
                "build",
                cells);
    }

    private static StructureWorkflowSnapshots.CellSnapshot cell(
            BlockPos pos, BlockState state, String itemId) {
        return new StructureWorkflowSnapshots.CellSnapshot(
                pos, itemId, state, Blocks.AIR.defaultBlockState());
    }
}
