package com.dwinovo.numen.core.task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiBlockPlacementTest {

    @Test
    void bedFootReservesHeadInFacingDirection() {
        BlockState foot = Blocks.RED_BED.defaultBlockState()
                .setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);

        var cells = MultiBlockPlacement.footprint(new BlockPos(10, 64, 20), foot);

        assertEquals(2, cells.size());
        assertEquals(new BlockPos(10, 64, 20), cells.get(0).pos());
        assertEquals(new BlockPos(11, 64, 20), cells.get(1).pos());
        assertEquals(BedPart.HEAD,
                cells.get(1).state().getValue(BlockStateProperties.BED_PART));
    }

    @Test
    void lowerDoorReservesUpperCell() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);

        var cells = MultiBlockPlacement.footprint(new BlockPos(3, 70, -4), lower);

        assertEquals(2, cells.size());
        assertEquals(new BlockPos(3, 71, -4), cells.get(1).pos());
        assertEquals(DoubleBlockHalf.UPPER,
                cells.get(1).state().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF));
    }
}
