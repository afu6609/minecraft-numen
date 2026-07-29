package com.dwinovo.numen.core.spatial;

/** Immutable integer position used by the server-side spatial model. */
public record VoxelPos(int x, int y, int z) {

    public int chebyshevDistance(VoxelPos other) {
        return Math.max(
                Math.max(Math.abs(x - other.x), Math.abs(y - other.y)),
                Math.abs(z - other.z));
    }
}
