package com.dwinovo.numen.core.task.survival.shelter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Static shelter semantics derived from one immutable structure workflow.
 *
 * <p>This is an analysis result, not a claim that the live world is safe. Call
 * {@link ShelterVerifier#verify(ShelterBlueprint, ShelterWorldSampler)} before
 * routing a body into it.</p>
 */
public record ShelterBlueprint(
        String workflowId,
        long workflowRevision,
        String dimensionId,
        Bounds bounds,
        int floorY,
        int roofY,
        double shellPlanCoverage,
        List<ShellCell> shellCells,
        List<Doorway> doors,
        List<BlockPos> interiorStances,
        Amenities amenities,
        List<String> warnings) {

    public ShelterBlueprint {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(shellCells, "shellCells");
        Objects.requireNonNull(doors, "doors");
        Objects.requireNonNull(interiorStances, "interiorStances");
        Objects.requireNonNull(amenities, "amenities");
        Objects.requireNonNull(warnings, "warnings");
        if (floorY < bounds.minY() || roofY > bounds.maxY() || floorY >= roofY) {
            throw new IllegalArgumentException("invalid shelter floor/roof");
        }
        if (!Double.isFinite(shellPlanCoverage)
                || shellPlanCoverage < 0.0 || shellPlanCoverage > 1.0) {
            throw new IllegalArgumentException("shell coverage must be in [0,1]");
        }
        shellCells = List.copyOf(shellCells);
        doors = List.copyOf(doors);
        interiorStances = interiorStances.stream()
                .map(BlockPos::immutable)
                .toList();
        warnings = List.copyOf(warnings);
    }

    /** Inclusive absolute block bounds. */
    public record Bounds(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {

        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("invalid shelter bounds");
            }
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        public boolean horizontallyInside(BlockPos pos) {
            return pos.getX() > minX && pos.getX() < maxX
                    && pos.getZ() > minZ && pos.getZ() < maxZ;
        }

        public BlockPos centerAt(int y) {
            return new BlockPos(
                    minX + (maxX - minX) / 2,
                    y,
                    minZ + (maxZ - minZ) / 2);
        }
    }

    /**
     * One cell of the rectangular enclosure. A present expected block id means
     * the blueprint explicitly specified the block; an empty value means the
     * geometry still requires a live non-passable enclosure cell.
     */
    public record ShellCell(BlockPos position, Optional<String> expectedBlockId) {
        public ShellCell {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(expectedBlockId, "expectedBlockId");
            position = position.immutable();
        }
    }

    /** Lower door cell plus unambiguous inside/outside geometry. */
    public record Doorway(
            BlockPos lower,
            String expectedBlockId,
            Direction inward,
            BlockPos inside,
            BlockPos outside) {
        public Doorway {
            Objects.requireNonNull(lower, "lower");
            Objects.requireNonNull(expectedBlockId, "expectedBlockId");
            Objects.requireNonNull(inward, "inward");
            Objects.requireNonNull(inside, "inside");
            Objects.requireNonNull(outside, "outside");
            if (!inward.getAxis().isHorizontal()) {
                throw new IllegalArgumentException("door inward direction must be horizontal");
            }
            lower = lower.immutable();
            inside = inside.immutable();
            outside = outside.immutable();
        }
    }

    public record Amenities(
            List<BlockPos> beds,
            List<BlockPos> torches,
            List<BlockPos> lightSources) {
        public Amenities {
            beds = immutablePositions(beds, "beds");
            torches = immutablePositions(torches, "torches");
            lightSources = immutablePositions(lightSources, "lightSources");
        }

        private static List<BlockPos> immutablePositions(
                List<BlockPos> positions, String name) {
            Objects.requireNonNull(positions, name);
            return positions.stream().map(BlockPos::immutable).toList();
        }
    }
}
