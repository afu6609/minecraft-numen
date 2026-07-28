package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared block-state validity rules for construction scans, placement, and path costs. */
public final class BuildValidity {

    private static final Set<String> HORIZONTAL_CONNECTION_PROPERTIES =
            Set.of("north", "east", "south", "west");
    private static final Set<String> SIX_WAY_CONNECTION_PROPERTIES =
            Set.of("north", "east", "south", "west", "up", "down");

    private static final Set<Property<?>> ORIENTATION_PROPERTIES = Set.copyOf(List.of(
            RotatedPillarBlock.AXIS,
            HorizontalDirectionalBlock.FACING,
            StairBlock.FACING,
            StairBlock.HALF,
            StairBlock.SHAPE,
            PipeBlock.NORTH,
            PipeBlock.EAST,
            PipeBlock.SOUTH,
            PipeBlock.WEST,
            PipeBlock.UP,
            TrapDoorBlock.OPEN,
            TrapDoorBlock.HALF));

    private BuildValidity() {}

    public static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        NavSettings settings = NavSettings.get();
        if (current.getBlock() instanceof LiquidBlock && settings.okIfWater) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && settings.okIfAir().contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && settings.buildIgnoreBlocks().contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && settings.buildIgnoreExisting && !itemVerify) {
            return true;
        }
        if (!itemVerify && settings.buildValidSubstitutes()
                .getOrDefault(desired.getBlock(), List.of()).contains(current.getBlock())) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockState(current, desired);
    }

    public static boolean sameBlockState(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        NavSettings settings = NavSettings.get();
        List<String> ignoredProps = settings.buildIgnoreProperties();
        Map<Property<?>, Comparable<?>> firstValues = first.getValues();
        Map<Property<?>, Comparable<?>> secondValues = second.getValues();
        for (Map.Entry<Property<?>, Comparable<?>> entry : firstValues.entrySet()) {
            Property<?> property = entry.getKey();
            if (entry.getValue() != secondValues.get(property)
                    && !isNeighbourDerived(first, property)
                    && !(settings.buildIgnoreDirection && ORIENTATION_PROPERTIES.contains(property))
                    && !ignoredProps.contains(property.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Properties recalculated by vanilla from neighbouring blocks are outcomes,
     * not placement choices. A pane placed into a window frame, for example,
     * immediately flips its north/east/south/west connections away from the
     * item's default state. Treating those flips as a blueprint mismatch makes
     * the builder remove and replace the same correct block forever.
     *
     * <p>Keep player-controlled state (facing, half, axis, waterlogged, open)
     * strict; only ignore properties whose value vanilla derives from the
     * surrounding world.</p>
     */
    private static boolean isNeighbourDerived(BlockState state, Property<?> property) {
        String name = property.getName();
        if (state.getBlock() instanceof CrossCollisionBlock) {
            return HORIZONTAL_CONNECTION_PROPERTIES.contains(name);
        }
        if (state.getBlock() instanceof WallBlock) {
            return HORIZONTAL_CONNECTION_PROPERTIES.contains(name) || "up".equals(name);
        }
        if (state.getBlock() instanceof PipeBlock) {
            return SIX_WAY_CONNECTION_PROPERTIES.contains(name);
        }
        if (state.getBlock() instanceof StairBlock) {
            return "shape".equals(name);
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            return "in_wall".equals(name);
        }
        return false;
    }
}
