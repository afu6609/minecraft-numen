package com.dwinovo.numen.core.task.survival.shelter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Objects;

/**
 * Server-world implementation of {@link ShelterWorldSampler}.
 *
 * <p>Call it on the server thread. It checks {@link Level#hasChunkAt(BlockPos)}
 * before any state read and therefore never pulls an unloaded shelter chunk into
 * memory merely to score it.</p>
 */
public final class MinecraftShelterWorldSampler implements ShelterWorldSampler {

    private final Level level;

    public MinecraftShelterWorldSampler(Level level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public String dimensionId() {
        return level.dimension().location().toString();
    }

    @Override
    public BlockSample sample(BlockPos position) {
        BlockPos pos = position.immutable();
        if (!level.hasChunkAt(pos)) {
            return BlockSample.unloaded();
        }
        BlockState state = level.getBlockState(pos);
        boolean door = state.getBlock() instanceof DoorBlock;
        boolean open = door
                && state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN);
        boolean hazardous = isHazardous(state);
        boolean occupiable = state.getCollisionShape(level, pos).isEmpty()
                && state.getFluidState().isEmpty()
                && !hazardous;
        return new BlockSample(
                true,
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                occupiable,
                state.isFaceSturdy(level, pos, Direction.UP),
                hazardous,
                door,
                open,
                level.getBrightness(LightLayer.BLOCK, pos),
                level.getBrightness(LightLayer.SKY, pos));
    }

    private static boolean isHazardous(BlockState state) {
        return state.is(BlockTags.FIRE)
                || state.is(Blocks.LAVA)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)
                || (state.getBlock() instanceof CampfireBlock
                && state.hasProperty(BlockStateProperties.LIT)
                && state.getValue(BlockStateProperties.LIT));
    }
}
