package com.dwinovo.numen.core.task.survival.shelter;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded live-world verification pass for a static shelter blueprint. */
public record ShelterVerification(
        Status status,
        int uniqueWorldSamples,
        int unloadedSamples,
        int mismatchedBlueprintCells,
        List<BlockPos> mismatchSamples,
        int breachedShellCells,
        List<BlockPos> breachSamples,
        int darkStanceCount,
        int minimumBlockLight,
        List<DoorStatus> doors,
        List<StanceStatus> stances,
        List<String> reasons) {

    public enum Status {
        SAFE,
        UNSAFE,
        INCOMPLETE_UNLOADED,
        WRONG_DIMENSION,
        LIMIT_EXCEEDED
    }

    public ShelterVerification {
        Objects.requireNonNull(status, "status");
        mismatchSamples = immutablePositions(mismatchSamples, "mismatchSamples");
        breachSamples = immutablePositions(breachSamples, "breachSamples");
        doors = List.copyOf(Objects.requireNonNull(doors, "doors"));
        stances = List.copyOf(Objects.requireNonNull(stances, "stances"));
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }

    public boolean safe() {
        return status == Status.SAFE;
    }

    /** First stance in blueprint preference order which passed all live checks. */
    public Optional<BlockPos> preferredSafeStance() {
        return stances.stream()
                .filter(StanceStatus::safe)
                .map(StanceStatus::feet)
                .findFirst();
    }

    public record DoorStatus(
            BlockPos lower,
            boolean loaded,
            boolean present,
            boolean open,
            int blockLight,
            int skyLight) {
        public DoorStatus {
            Objects.requireNonNull(lower, "lower");
            lower = lower.immutable();
        }
    }

    public record StanceStatus(
            BlockPos feet,
            boolean loaded,
            boolean feetClear,
            boolean headClear,
            boolean supported,
            boolean hazardous,
            int blockLight,
            int skyLight,
            boolean safe) {
        public StanceStatus {
            Objects.requireNonNull(feet, "feet");
            feet = feet.immutable();
        }
    }

    private static List<BlockPos> immutablePositions(
            List<BlockPos> positions, String name) {
        Objects.requireNonNull(positions, name);
        return positions.stream().map(BlockPos::immutable).toList();
    }
}
