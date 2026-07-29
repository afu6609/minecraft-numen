package com.dwinovo.numen.core.task.survival.shelter;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded live verifier for enclosure integrity, closed doors, usable stance
 * geometry and permanent block light.
 */
public final class ShelterVerifier {

    /**
     * Modern vanilla hostile spawning requires block light zero. Requiring one
     * or more is deliberately night-stable and does not mistake daytime skylight
     * through a damaged roof for permanent safety.
     */
    public static final int MIN_SAFE_BLOCK_LIGHT = 1;
    public static final int MAX_WORLD_SAMPLES = 6_000;
    private static final int DIAGNOSTIC_SAMPLE_LIMIT = 16;

    private ShelterVerifier() {}

    public static ShelterVerification verify(
            ShelterBlueprint blueprint, ShelterWorldSampler world) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(world, "world");
        String sampledDimension =
                Objects.requireNonNull(world.dimensionId(), "world dimensionId");
        if (!blueprint.dimensionId().equals(sampledDimension)) {
            return wrongDimension(
                    "shelter belongs to " + blueprint.dimensionId()
                            + ", not sampled dimension " + sampledDimension);
        }

        long maximumRequested =
                (long) blueprint.shellCells().size()
                        + (long) blueprint.doors().size() * 2
                        + (long) blueprint.interiorStances().size() * 3;
        if (maximumRequested > MAX_WORLD_SAMPLES) {
            return limitExceeded(
                    "verification would request at most " + maximumRequested
                            + " cells, over the " + MAX_WORLD_SAMPLES + "-sample limit");
        }

        MemoizedSampler samples = new MemoizedSampler(world);
        List<BlockPos> mismatchSamples = new ArrayList<>();
        List<BlockPos> breachSamples = new ArrayList<>();
        List<ShelterVerification.DoorStatus> doorStatuses = new ArrayList<>();
        List<ShelterVerification.StanceStatus> stanceStatuses = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int unloaded = 0;
        int mismatches = 0;
        int breaches = 0;

        for (ShelterBlueprint.ShellCell cell : blueprint.shellCells()) {
            ShelterWorldSampler.BlockSample sample =
                    samples.sample(cell.position());
            if (!sample.loaded()) {
                unloaded++;
                continue;
            }
            if (cell.expectedBlockId().isPresent()
                    && !cell.expectedBlockId().get().equals(sample.blockId())) {
                mismatches++;
                addDiagnostic(mismatchSamples, cell.position());
            }
            if (sample.occupiable() || (sample.door() && sample.doorOpen())) {
                breaches++;
                addDiagnostic(breachSamples, cell.position());
            }
        }

        boolean missingDoor = false;
        boolean openDoor = false;
        for (ShelterBlueprint.Doorway door : blueprint.doors()) {
            ShelterWorldSampler.BlockSample lower = samples.sample(door.lower());
            ShelterWorldSampler.BlockSample upper = samples.sample(door.lower().above());
            boolean loaded = lower.loaded() && upper.loaded();
            if (!loaded) {
                unloaded += (lower.loaded() ? 0 : 1) + (upper.loaded() ? 0 : 1);
            }
            boolean present = loaded
                    && lower.door() && upper.door()
                    && door.expectedBlockId().equals(lower.blockId())
                    && door.expectedBlockId().equals(upper.blockId());
            boolean open = present && (lower.doorOpen() || upper.doorOpen());
            missingDoor |= loaded && !present;
            openDoor |= open;
            doorStatuses.add(new ShelterVerification.DoorStatus(
                    door.lower(),
                    loaded,
                    present,
                    open,
                    lower.loaded() ? lower.blockLight() : -1,
                    lower.loaded() ? lower.skyLight() : -1));
        }

        int darkStances = 0;
        int minimumBlockLight = Integer.MAX_VALUE;
        int safeStances = 0;
        for (BlockPos feetPos : blueprint.interiorStances()) {
            ShelterWorldSampler.BlockSample feet = samples.sample(feetPos);
            ShelterWorldSampler.BlockSample head = samples.sample(feetPos.above());
            ShelterWorldSampler.BlockSample floor = samples.sample(feetPos.below());
            boolean loaded = feet.loaded() && head.loaded() && floor.loaded();
            if (!loaded) {
                unloaded += (feet.loaded() ? 0 : 1)
                        + (head.loaded() ? 0 : 1)
                        + (floor.loaded() ? 0 : 1);
            }
            boolean feetClear = loaded && feet.occupiable();
            boolean headClear = loaded && head.occupiable();
            boolean supported = loaded && floor.sturdyTop() && !floor.hazardous();
            boolean hazardous = loaded
                    && (feet.hazardous() || head.hazardous() || floor.hazardous());
            int blockLight = feet.loaded() ? feet.blockLight() : -1;
            int skyLight = feet.loaded() ? feet.skyLight() : -1;
            boolean spawnableGeometry =
                    feetClear && headClear && supported && !hazardous;
            if (spawnableGeometry) {
                minimumBlockLight = Math.min(minimumBlockLight, blockLight);
                if (blockLight < MIN_SAFE_BLOCK_LIGHT) {
                    darkStances++;
                }
            }
            boolean safe = spawnableGeometry
                    && blockLight >= MIN_SAFE_BLOCK_LIGHT;
            if (safe) {
                safeStances++;
            }
            stanceStatuses.add(new ShelterVerification.StanceStatus(
                    feetPos,
                    loaded,
                    feetClear,
                    headClear,
                    supported,
                    hazardous,
                    blockLight,
                    skyLight,
                    safe));
        }
        if (minimumBlockLight == Integer.MAX_VALUE) {
            minimumBlockLight = -1;
        }
        // The same unloaded coordinate can participate in shell, door and stance
        // checks. Report unique samples rather than inflating the count by role.
        unloaded = samples.unloadedCount();

        if (unloaded > 0) {
            reasons.add("one or more required shelter cells are unloaded");
        }
        if (mismatches > 0) {
            reasons.add("saved blueprint cells no longer match the live world");
        }
        if (breaches > 0) {
            reasons.add("the live rectangular enclosure has passable breaches");
        }
        if (missingDoor) {
            reasons.add("a registered two-block door is missing or replaced");
        }
        if (openDoor) {
            reasons.add("a registered shelter door is open");
        }
        if (safeStances == 0) {
            reasons.add("no loaded, supported and lit interior stance is usable");
        }
        if (darkStances > 0) {
            reasons.add("one or more spawnable interior stances have zero block light");
        }

        ShelterVerification.Status status;
        if (unloaded > 0) {
            status = ShelterVerification.Status.INCOMPLETE_UNLOADED;
        } else if (mismatches == 0
                && breaches == 0
                && !missingDoor
                && !openDoor
                && safeStances > 0
                && darkStances == 0) {
            status = ShelterVerification.Status.SAFE;
        } else {
            status = ShelterVerification.Status.UNSAFE;
        }
        return new ShelterVerification(
                status,
                samples.size(),
                unloaded,
                mismatches,
                mismatchSamples,
                breaches,
                breachSamples,
                darkStances,
                minimumBlockLight,
                doorStatuses,
                stanceStatuses,
                reasons);
    }

    private static ShelterVerification limitExceeded(String reason) {
        return new ShelterVerification(
                ShelterVerification.Status.LIMIT_EXCEEDED,
                0,
                0,
                0,
                List.of(),
                0,
                List.of(),
                0,
                -1,
                List.of(),
                List.of(),
                List.of(reason));
    }

    private static ShelterVerification wrongDimension(String reason) {
        return new ShelterVerification(
                ShelterVerification.Status.WRONG_DIMENSION,
                0,
                0,
                0,
                List.of(),
                0,
                List.of(),
                0,
                -1,
                List.of(),
                List.of(),
                List.of(reason));
    }

    private static void addDiagnostic(List<BlockPos> samples, BlockPos pos) {
        if (samples.size() < DIAGNOSTIC_SAMPLE_LIMIT) {
            samples.add(pos.immutable());
        }
    }

    private static final class MemoizedSampler {
        private final ShelterWorldSampler delegate;
        private final Map<BlockPos, ShelterWorldSampler.BlockSample> cache =
                new HashMap<>();

        private MemoizedSampler(ShelterWorldSampler delegate) {
            this.delegate = delegate;
        }

        private ShelterWorldSampler.BlockSample sample(BlockPos pos) {
            BlockPos key = pos.immutable();
            return cache.computeIfAbsent(key, ignored -> Objects.requireNonNull(
                    delegate.sample(key), "world sampler returned null"));
        }

        private int size() {
            return cache.size();
        }

        private int unloadedCount() {
            return (int) cache.values().stream()
                    .filter(sample -> !sample.loaded())
                    .count();
        }
    }
}
