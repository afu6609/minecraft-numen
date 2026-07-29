package com.dwinovo.numen.core.tools;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureWorkflowDimensionClaimTest {

    @BeforeAll
    static void bootMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void exactCheckExpandsAndVerifiesBothDoorCells() {
        StructureWorkflowStore.Workflow workflow = lowerDoorWorkflow();
        BlockPos lower = new BlockPos(4, 65, 8);
        BlockState lowerState = workflow.cells.get(0).desired.toState();
        BlockState upperState = lowerState.setValue(
                BlockStateProperties.DOUBLE_BLOCK_HALF,
                DoubleBlockHalf.UPPER);
        Map<BlockPos, BlockState> live = new LinkedHashMap<>();
        live.put(lower, lowerState);
        live.put(lower.above(), upperState);

        StructureWorkflowStore.ExactMatchCheck result =
                StructureWorkflowStore.checkExact(
                        workflow, ignored -> true, live::get);

        assertEquals(StructureWorkflowStore.ExactMatchStatus.EXACT,
                result.status());
        assertEquals(2, result.verifiedCells());
    }

    @Test
    void unloadedPartnerStopsBeforeReadingIt() {
        StructureWorkflowStore.Workflow workflow = lowerDoorWorkflow();
        BlockPos lower = new BlockPos(4, 65, 8);
        BlockState lowerState = workflow.cells.get(0).desired.toState();
        AtomicInteger reads = new AtomicInteger();

        StructureWorkflowStore.ExactMatchCheck result =
                StructureWorkflowStore.checkExact(
                        workflow,
                        pos -> !pos.equals(lower.above()),
                        pos -> {
                            reads.incrementAndGet();
                            return lowerState;
                        });

        assertEquals(StructureWorkflowStore.ExactMatchStatus.UNLOADED,
                result.status());
        assertEquals(lower.above(), result.problem());
        assertEquals(1, result.verifiedCells());
        assertEquals(1, reads.get());
    }

    @Test
    void onePropertyDifferenceRejectsClaim() {
        StructureWorkflowStore.Workflow workflow = lowerDoorWorkflow();
        BlockPos lower = new BlockPos(4, 65, 8);
        BlockState expected = workflow.cells.get(0).desired.toState();
        Map<BlockPos, BlockState> live = new LinkedHashMap<>();
        live.put(lower, expected.setValue(BlockStateProperties.OPEN, true));
        live.put(lower.above(), expected.setValue(
                BlockStateProperties.DOUBLE_BLOCK_HALF,
                DoubleBlockHalf.UPPER));

        StructureWorkflowStore.ExactMatchCheck result =
                StructureWorkflowStore.checkExact(
                        workflow, ignored -> true, live::get);

        assertEquals(StructureWorkflowStore.ExactMatchStatus.MISMATCH,
                result.status());
        assertEquals(lower, result.problem());
        assertEquals(0, result.verifiedCells());
    }

    private static StructureWorkflowStore.Workflow lowerDoorWorkflow() {
        BlockPos lower = new BlockPos(4, 65, 8);
        BlockState state = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                        DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.OPEN, false);
        StructureWorkflowStore.Cell cell =
                new StructureWorkflowStore.Cell();
        cell.x = lower.getX();
        cell.y = lower.getY();
        cell.z = lower.getZ();
        cell.itemId = "minecraft:oak_door";
        cell.desired = StructureWorkflowStore.StateSpec.from(state);
        cell.before = StructureWorkflowStore.StateSpec.from(
                Blocks.AIR.defaultBlockState());

        StructureWorkflowStore.Workflow workflow =
                new StructureWorkflowStore.Workflow();
        workflow.id = "legacy-house";
        workflow.ownerUuid = "owner";
        workflow.ownerName = "momo";
        workflow.name = "legacy house";
        workflow.goal = "shelter";
        workflow.revision = 1;
        workflow.cells = List.of(cell);
        return workflow;
    }
}
