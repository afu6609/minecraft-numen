package com.dwinovo.numen.core.task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;

/** Vanilla blocks whose one item placement atomically occupies two cells. */
public final class MultiBlockPlacement {

    private MultiBlockPlacement() {}

    public record Cell(BlockPos pos, BlockState state) {}

    public static List<Cell> footprint(BlockPos pos, BlockState state) {
        Cell primary = new Cell(pos.immutable(), state);
        if (state.getBlock() instanceof BedBlock
                && state.hasProperty(BlockStateProperties.BED_PART)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            BedPart part = state.getValue(BlockStateProperties.BED_PART);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            BlockPos partnerPos = pos.relative(
                    part == BedPart.FOOT ? facing : facing.getOpposite());
            BlockState partnerState = state.setValue(
                    BlockStateProperties.BED_PART,
                    part == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT);
            return List.of(primary, new Cell(partnerPos.immutable(), partnerState));
        }
        if (state.getBlock() instanceof DoorBlock
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            BlockPos partnerPos =
                    half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState partnerState = state.setValue(
                    BlockStateProperties.DOUBLE_BLOCK_HALF,
                    half == DoubleBlockHalf.LOWER
                            ? DoubleBlockHalf.UPPER
                            : DoubleBlockHalf.LOWER);
            return List.of(primary, new Cell(partnerPos.immutable(), partnerState));
        }
        return List.of(primary);
    }

    public static boolean matches(BlockGetter view, BuildTaskRecord.Target target) {
        List<Cell> footprint = footprint(target.pos(), target.desiredState());
        for (int index = 0; index < footprint.size(); index++) {
            Cell cell = footprint.get(index);
            BlockState current = view.getBlockState(cell.pos());
            boolean matches = index == 0
                    ? target.matches(current)
                    : BuildValidity.valid(current, cell.state(), false);
            if (!matches) {
                return false;
            }
        }
        return true;
    }
}
