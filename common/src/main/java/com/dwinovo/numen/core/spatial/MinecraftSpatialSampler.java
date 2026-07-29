package com.dwinovo.numen.core.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Captures and classifies a bounded world snapshot on the server thread. */
public final class MinecraftSpatialSampler {

    private static final Set<String> GROUND_EXACT = Set.of(
            "grass_block", "dirt", "coarse_dirt", "podzol", "mycelium",
            "rooted_dirt", "mud", "clay", "sand", "red_sand", "gravel",
            "stone", "deepslate", "granite", "diorite", "andesite",
            "tuff", "calcite", "dripstone_block", "netherrack", "end_stone",
            "soul_sand", "soul_soil", "snow_block", "ice", "packed_ice",
            "blue_ice", "moss_block");

    private static final Set<String> FURNITURE_EXACT = Set.of(
            "crafting_table", "furnace", "blast_furnace", "smoker",
            "chest", "trapped_chest", "ender_chest", "barrel",
            "bookshelf", "chiseled_bookshelf", "lectern", "anvil",
            "chipped_anvil", "damaged_anvil", "stonecutter", "loom",
            "cartography_table", "fletching_table", "smithing_table",
            "grindstone", "brewing_stand", "cauldron", "composter");

    public SpatialSnapshot sample(
            ServerLevel level,
            VoxelPos anchor,
            int horizontalRadius,
            int verticalRadius) {
        if (level == null || anchor == null) {
            throw new IllegalArgumentException("level and anchor are required");
        }
        int minY = Math.max(level.getMinBuildHeight(), anchor.y() - verticalRadius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, anchor.y() + verticalRadius);
        SpatialBounds bounds = new SpatialBounds(
                anchor.x() - horizontalRadius, minY, anchor.z() - horizontalRadius,
                anchor.x() + horizontalRadius, maxY, anchor.z() + horizontalRadius);
        List<VoxelCell> cells = new ArrayList<>();
        int unloadedColumns = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                cursor.set(x, anchor.y(), z);
                if (!level.hasChunkAt(cursor)) {
                    unloadedColumns++;
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    cells.add(classify(state, new VoxelPos(x, y, z)));
                }
            }
        }
        return new SpatialSnapshot(anchor, bounds, cells, unloadedColumns);
    }

    static VoxelCell classify(BlockState state, VoxelPos pos) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
                .toLowerCase(Locale.ROOT);
        int flags = 0;

        boolean log = state.is(BlockTags.LOGS);
        boolean leaf = state.is(BlockTags.LEAVES);
        boolean door = path.endsWith("_door") && !path.endsWith("_trapdoor");
        boolean glass = path.contains("glass");
        boolean furniture = isFurniture(path);
        boolean constructed = isConstructed(path)
                || door || glass || furniture || path.startsWith("stripped_");
        boolean ground = isGround(path);

        if (ground) flags |= VoxelCell.GROUND;
        if (log) flags |= VoxelCell.LOG;
        if (leaf) flags |= VoxelCell.LEAF;
        if (constructed) flags |= VoxelCell.CONSTRUCTED;
        if (door) flags |= VoxelCell.DOOR;
        if (glass) flags |= VoxelCell.GLASS;
        if (furniture) flags |= VoxelCell.FURNITURE;
        if (!state.getFluidState().isEmpty()) flags |= VoxelCell.FLUID;

        boolean persistentLeaves = leaf
                && state.hasProperty(LeavesBlock.PERSISTENT)
                && state.getValue(LeavesBlock.PERSISTENT);
        return new VoxelCell(pos, id, flags, persistentLeaves);
    }

    private static boolean isGround(String path) {
        if (GROUND_EXACT.contains(path)) return true;
        if (path.endsWith("_ore")) return true;
        return path.endsWith("_nylium") || path.endsWith("_sandstone");
    }

    private static boolean isConstructed(String path) {
        if (path.contains("planks") || path.contains("bricks")) return true;
        if (path.endsWith("_stairs") || path.endsWith("_slab")
                || path.endsWith("_fence") || path.endsWith("_fence_gate")
                || path.endsWith("_wall") || path.endsWith("_trapdoor")) {
            return true;
        }
        return path.contains("concrete")
                || path.contains("terracotta")
                || path.endsWith("_wool")
                || path.endsWith("_carpet")
                || path.equals("cobblestone")
                || path.equals("mossy_cobblestone")
                || path.equals("stone_bricks")
                || path.equals("mossy_stone_bricks")
                || path.equals("quartz_block")
                || path.equals("smooth_quartz")
                || path.equals("purpur_block")
                || path.equals("iron_block")
                || path.equals("gold_block")
                || path.equals("copper_block");
    }

    private static boolean isFurniture(String path) {
        return FURNITURE_EXACT.contains(path)
                || path.endsWith("_bed")
                || path.endsWith("_torch")
                || path.endsWith("_lantern")
                || path.endsWith("_banner");
    }
}
