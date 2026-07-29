package com.dwinovo.numen.core.spatial;

/** One occupied voxel after Minecraft block states have been semantically classified. */
public record VoxelCell(
        VoxelPos pos,
        String blockId,
        int flags,
        boolean persistentLeaves) {

    public static final int GROUND = 1;
    public static final int LOG = 1 << 1;
    public static final int LEAF = 1 << 2;
    public static final int CONSTRUCTED = 1 << 3;
    public static final int DOOR = 1 << 4;
    public static final int GLASS = 1 << 5;
    public static final int FURNITURE = 1 << 6;
    public static final int FLUID = 1 << 7;

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }
}
