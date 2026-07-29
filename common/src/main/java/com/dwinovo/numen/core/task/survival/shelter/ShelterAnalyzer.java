package com.dwinovo.numen.core.task.survival.shelter;

import com.dwinovo.numen.core.task.MultiBlockPlacement;
import com.dwinovo.numen.core.tools.StructureWorkflowSnapshots;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded recognizer for the first supported shelter shape: a small rectangular
 * room represented by a complete absolute-cell structure workflow.
 */
public final class ShelterAnalyzer {

    /** Mirrors the workflow writer's public planning bound without trusting callers. */
    public static final int MAX_SOURCE_CELLS = 1_536;
    /** A bed or door may expand one saved cell into two occupied cells. */
    public static final int MAX_EXPANDED_CELLS = MAX_SOURCE_CELLS * 2;
    public static final int MAX_AXIS = 32;
    public static final int MAX_INTERIOR_AREA = 30 * 30;
    public static final int MAX_SHELL_CELLS = 2_500;
    public static final int MAX_DOORS = 16;
    public static final double MIN_SHELL_PLAN_COVERAGE = 0.70;

    private ShelterAnalyzer() {}

    public static ShelterAnalysis analyze(
            StructureWorkflowSnapshots.WorkflowSnapshot workflow) {
        if (workflow == null) {
            throw new IllegalArgumentException("workflow must not be null");
        }
        List<StructureWorkflowSnapshots.CellSnapshot> source = workflow.cells();
        if (source.isEmpty()) {
            return rejected(
                    ShelterAnalysis.Outcome.NOT_A_SHELTER, 0, 0,
                    "workflow has no cells");
        }
        if (source.size() > MAX_SOURCE_CELLS) {
            return rejected(
                    ShelterAnalysis.Outcome.LIMIT_EXCEEDED,
                    source.size(), 0,
                    "workflow exceeds the " + MAX_SOURCE_CELLS + "-cell analysis limit");
        }
        if (workflow.dimensionId().isEmpty()) {
            return rejected(
                    ShelterAnalysis.Outcome.NOT_A_SHELTER,
                    source.size(), 0,
                    "legacy workflow has no dimension id; explicitly patch or "
                            + "re-plan it in its original dimension before registration");
        }

        Map<BlockPos, BlockState> desired = new LinkedHashMap<>();
        List<BlockPos> beds = new ArrayList<>();
        List<BlockPos> torches = new ArrayList<>();
        List<BlockPos> lights = new ArrayList<>();
        List<String> issues = new ArrayList<>();

        for (StructureWorkflowSnapshots.CellSnapshot cell : source) {
            BlockState primary = cell.desiredState();
            List<MultiBlockPlacement.Cell> footprint =
                    MultiBlockPlacement.footprint(cell.position(), primary);
            if ((long) desired.size() + footprint.size() > MAX_EXPANDED_CELLS) {
                return rejected(
                        ShelterAnalysis.Outcome.LIMIT_EXCEEDED,
                        source.size(), desired.size(),
                        "expanded atomic footprints exceed "
                                + MAX_EXPANDED_CELLS + " cells");
            }
            for (MultiBlockPlacement.Cell part : footprint) {
                BlockPos pos = part.pos().immutable();
                BlockState previous = desired.putIfAbsent(pos, part.state());
                if (previous != null && !previous.equals(part.state())) {
                    return rejected(
                            ShelterAnalysis.Outcome.NOT_A_SHELTER,
                            source.size(), desired.size(),
                            "workflow footprints conflict at " + pos.toShortString());
                }
            }

            if (primary.getBlock() instanceof BedBlock
                    && (!primary.hasProperty(BlockStateProperties.BED_PART)
                    || primary.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT)) {
                beds.add(cell.position().immutable());
            }
            String blockPath = BuiltInRegistries.BLOCK
                    .getKey(primary.getBlock()).getPath();
            if (blockPath.endsWith("torch")) {
                torches.add(cell.position().immutable());
            }
            if (primary.getLightEmission() > 0) {
                lights.add(cell.position().immutable());
            }
        }

        List<Map.Entry<BlockPos, BlockState>> occupied = desired.entrySet().stream()
                .filter(entry -> !entry.getValue().isAir())
                .toList();
        if (occupied.isEmpty()) {
            return rejected(
                    ShelterAnalysis.Outcome.NOT_A_SHELTER,
                    source.size(), desired.size(),
                    "workflow contains no occupied structure cells");
        }

        ShelterBlueprint.Bounds bounds = bounds(occupied);
        if (bounds.width() > MAX_AXIS
                || bounds.height() > MAX_AXIS
                || bounds.depth() > MAX_AXIS) {
            return rejected(
                    ShelterAnalysis.Outcome.LIMIT_EXCEEDED,
                    source.size(), desired.size(),
                    "structure bounds exceed " + MAX_AXIS + " blocks on one axis");
        }
        if (bounds.width() < 3 || bounds.depth() < 3 || bounds.height() < 4) {
            return rejected(
                    ShelterAnalysis.Outcome.NOT_A_SHELTER,
                    source.size(), desired.size(),
                    "rectangular shelter needs at least 3x4x3 outer bounds");
        }
        long interiorArea =
                (long) (bounds.width() - 2) * (bounds.depth() - 2);
        if (interiorArea > MAX_INTERIOR_AREA) {
            return rejected(
                    ShelterAnalysis.Outcome.LIMIT_EXCEEDED,
                    source.size(), desired.size(),
                    "interior footprint exceeds " + MAX_INTERIOR_AREA + " cells");
        }

        List<BlockPos> lowerDoors = lowerDoors(desired);
        if (lowerDoors.size() > MAX_DOORS) {
            return rejected(
                    ShelterAnalysis.Outcome.LIMIT_EXCEEDED,
                    source.size(), desired.size(),
                    "shelter exposes more than " + MAX_DOORS + " lower doors");
        }
        int floorY = inferFloorY(bounds, lowerDoors);
        int roofY = bounds.maxY();
        if (floorY < bounds.minY() || floorY + 3 > roofY) {
            return rejected(
                    ShelterAnalysis.Outcome.NOT_A_SHELTER,
                    source.size(), desired.size(),
                    "could not infer a two-block-high room between floor and roof");
        }

        List<BlockPos> geometricShell = rectangularShell(bounds, floorY, roofY);
        if (geometricShell.size() > MAX_SHELL_CELLS) {
            return rejected(
                    ShelterAnalysis.Outcome.LIMIT_EXCEEDED,
                    source.size(), desired.size(),
                    "rectangular shell exceeds " + MAX_SHELL_CELLS + " samples");
        }
        long plannedShell = geometricShell.stream()
                .filter(pos -> isPlannedEnclosure(desired.get(pos), pos))
                .count();
        double shellCoverage = plannedShell / (double) geometricShell.size();
        if (shellCoverage < MIN_SHELL_PLAN_COVERAGE) {
            return rejected(
                    ShelterAnalysis.Outcome.NOT_A_SHELTER,
                    source.size(), desired.size(),
                    "planned rectangular shell coverage "
                            + Math.round(shellCoverage * 100.0)
                            + "% is below "
                            + Math.round(MIN_SHELL_PLAN_COVERAGE * 100.0) + "%");
        }

        List<ShelterBlueprint.Doorway> doors =
                doorways(lowerDoors, desired, bounds, floorY, issues);
        if (doors.isEmpty()) {
            return rejected(
                    ShelterAnalysis.Outcome.NOT_A_SHELTER,
                    source.size(), desired.size(),
                    "no lower door on an outer wall at floor level");
        }

        List<BlockPos> stances = interiorStances(
                desired, bounds, floorY, doors);
        if (stances.isEmpty()) {
            return rejected(
                    ShelterAnalysis.Outcome.NOT_A_SHELTER,
                    source.size(), desired.size(),
                    "no two-block-high supported interior stance");
        }

        if (beds.isEmpty()) {
            issues.add("no bed is recorded in the shelter workflow");
        }
        if (lights.isEmpty()) {
            issues.add("no light-emitting block is recorded; live light verification is required");
        }
        if (shellCoverage < 0.90) {
            issues.add("shell blueprint is incomplete; live geometry must fill unspecified cells");
        }

        List<ShelterBlueprint.ShellCell> shellCells = geometricShell.stream()
                .map(pos -> {
                    BlockState expected = desired.get(pos);
                    Optional<String> expectedId =
                            expected == null || expected.isAir()
                                    ? Optional.empty()
                                    : Optional.of(blockId(expected));
                    return new ShelterBlueprint.ShellCell(pos, expectedId);
                })
                .toList();
        ShelterBlueprint blueprint = new ShelterBlueprint(
                workflow.id(),
                workflow.revision(),
                workflow.dimensionId().orElseThrow(),
                bounds,
                floorY,
                roofY,
                shellCoverage,
                shellCells,
                doors,
                stances,
                new ShelterBlueprint.Amenities(
                        deduplicate(beds),
                        deduplicate(torches),
                        deduplicate(lights)),
                issues);
        return new ShelterAnalysis(
                ShelterAnalysis.Outcome.SUPPORTED_RECTANGULAR,
                Optional.of(blueprint),
                source.size(),
                desired.size(),
                issues);
    }

    private static ShelterBlueprint.Bounds bounds(
            List<Map.Entry<BlockPos, BlockState>> occupied) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Map.Entry<BlockPos, BlockState> entry : occupied) {
            BlockPos pos = entry.getKey();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new ShelterBlueprint.Bounds(
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static List<BlockPos> lowerDoors(
            Map<BlockPos, BlockState> desired) {
        return desired.entrySet().stream()
                .filter(entry -> entry.getValue().getBlock() instanceof DoorBlock)
                .filter(entry -> !entry.getValue().hasProperty(
                        BlockStateProperties.DOUBLE_BLOCK_HALF)
                        || entry.getValue().getValue(
                        BlockStateProperties.DOUBLE_BLOCK_HALF)
                        == DoubleBlockHalf.LOWER)
                .map(Map.Entry::getKey)
                .map(BlockPos::immutable)
                .sorted(Comparator
                        .comparingInt((BlockPos pos) -> pos.getY())
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .toList();
    }

    private static int inferFloorY(
            ShelterBlueprint.Bounds bounds, List<BlockPos> lowerDoors) {
        if (lowerDoors.isEmpty()) {
            return bounds.minY();
        }
        Map<Integer, Integer> counts = new HashMap<>();
        for (BlockPos door : lowerDoors) {
            counts.merge(door.getY() - 1, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Comparator
                        .comparingInt(Map.Entry<Integer, Integer>::getValue)
                        .thenComparing(
                                Map.Entry<Integer, Integer>::getKey,
                                Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse(bounds.minY());
    }

    private static List<BlockPos> rectangularShell(
            ShelterBlueprint.Bounds bounds, int floorY, int roofY) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                cells.add(new BlockPos(x, floorY, z));
                cells.add(new BlockPos(x, roofY, z));
            }
        }
        for (int y = floorY + 1; y < roofY; y++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                cells.add(new BlockPos(x, y, bounds.minZ()));
                cells.add(new BlockPos(x, y, bounds.maxZ()));
            }
            for (int z = bounds.minZ() + 1; z < bounds.maxZ(); z++) {
                cells.add(new BlockPos(bounds.minX(), y, z));
                cells.add(new BlockPos(bounds.maxX(), y, z));
            }
        }
        return List.copyOf(cells);
    }

    private static boolean isPlannedEnclosure(
            BlockState state, BlockPos pos) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock) {
            return true;
        }
        return !state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty();
    }

    private static List<ShelterBlueprint.Doorway> doorways(
            List<BlockPos> lowerDoors,
            Map<BlockPos, BlockState> desired,
            ShelterBlueprint.Bounds bounds,
            int floorY,
            List<String> issues) {
        List<ShelterBlueprint.Doorway> result = new ArrayList<>();
        for (BlockPos lower : lowerDoors) {
            if (lower.getY() != floorY + 1) {
                continue;
            }
            List<Direction> inwardCandidates = new ArrayList<>(2);
            if (lower.getX() == bounds.minX()) {
                inwardCandidates.add(Direction.EAST);
            }
            if (lower.getX() == bounds.maxX()) {
                inwardCandidates.add(Direction.WEST);
            }
            if (lower.getZ() == bounds.minZ()) {
                inwardCandidates.add(Direction.SOUTH);
            }
            if (lower.getZ() == bounds.maxZ()) {
                inwardCandidates.add(Direction.NORTH);
            }
            List<Direction> valid = inwardCandidates.stream()
                    .filter(direction ->
                            bounds.horizontallyInside(lower.relative(direction)))
                    .toList();
            if (valid.size() != 1) {
                issues.add("door at " + lower.toShortString()
                        + " has ambiguous inside/outside geometry");
                continue;
            }
            Direction inward = valid.get(0);
            BlockState state = desired.get(lower);
            result.add(new ShelterBlueprint.Doorway(
                    lower,
                    blockId(state),
                    inward,
                    lower.relative(inward),
                    lower.relative(inward.getOpposite())));
        }
        return List.copyOf(result);
    }

    private static List<BlockPos> interiorStances(
            Map<BlockPos, BlockState> desired,
            ShelterBlueprint.Bounds bounds,
            int floorY,
            List<ShelterBlueprint.Doorway> doors) {
        List<BlockPos> result = new ArrayList<>();
        Set<BlockPos> preferred = new LinkedHashSet<>();
        for (ShelterBlueprint.Doorway door : doors) {
            preferred.add(door.inside());
        }
        for (int x = bounds.minX() + 1; x < bounds.maxX(); x++) {
            for (int z = bounds.minZ() + 1; z < bounds.maxZ(); z++) {
                BlockPos feet = new BlockPos(x, floorY + 1, z);
                if (isPlannedClear(desired.get(feet), feet)
                        && isPlannedClear(desired.get(feet.above()), feet.above())
                        && isPlannedSupport(
                        desired.get(feet.below()), feet.below())) {
                    result.add(feet);
                }
            }
        }
        BlockPos center = bounds.centerAt(floorY + 1);
        result.sort(Comparator
                .comparingInt((BlockPos pos) -> preferred.contains(pos) ? 0 : 1)
                .thenComparingLong(pos -> squaredHorizontalDistance(pos, center))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(result);
    }

    private static boolean isPlannedClear(BlockState state, BlockPos pos) {
        return state == null
                || state.isAir()
                || state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty();
    }

    private static boolean isPlannedSupport(BlockState state, BlockPos pos) {
        return state != null
                && !state.isAir()
                && state.isFaceSturdy(EmptyBlockGetter.INSTANCE, pos, Direction.UP);
    }

    private static long squaredHorizontalDistance(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static String blockId(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.toString();
    }

    private static List<BlockPos> deduplicate(List<BlockPos> positions) {
        return List.copyOf(new LinkedHashSet<>(positions));
    }

    private static ShelterAnalysis rejected(
            ShelterAnalysis.Outcome outcome,
            int sourceCells,
            int expandedCells,
            String reason) {
        return new ShelterAnalysis(
                outcome,
                Optional.empty(),
                sourceCells,
                expandedCells,
                List.of(reason));
    }
}
