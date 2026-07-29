package com.dwinovo.numen.core.spatial;

import java.util.Collection;

/** Inclusive world-coordinate bounds. */
public record SpatialBounds(
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ) {

    public static SpatialBounds of(Collection<VoxelPos> cells) {
        if (cells == null || cells.isEmpty()) {
            throw new IllegalArgumentException("cells must not be empty");
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (VoxelPos cell : cells) {
            minX = Math.min(minX, cell.x());
            minY = Math.min(minY, cell.y());
            minZ = Math.min(minZ, cell.z());
            maxX = Math.max(maxX, cell.x());
            maxY = Math.max(maxY, cell.y());
            maxZ = Math.max(maxZ, cell.z());
        }
        return new SpatialBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public double centerX() {
        return (minX + maxX) / 2.0;
    }

    public double centerY() {
        return (minY + maxY) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ) / 2.0;
    }

    public int volume() {
        long volume = (long) sizeX() * sizeY() * sizeZ();
        return volume > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) volume;
    }

    public int intersectionVolume(SpatialBounds other) {
        int x = Math.max(0, Math.min(maxX, other.maxX) - Math.max(minX, other.minX) + 1);
        int y = Math.max(0, Math.min(maxY, other.maxY) - Math.max(minY, other.minY) + 1);
        int z = Math.max(0, Math.min(maxZ, other.maxZ) - Math.max(minZ, other.minZ) + 1);
        return x * y * z;
    }

    public double centerDistance(SpatialBounds other) {
        double dx = centerX() - other.centerX();
        double dy = centerY() - other.centerY();
        double dz = centerZ() - other.centerZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
