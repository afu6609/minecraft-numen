package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.act.PlaceResolution;
import com.dwinovo.numen.core.act.Placement;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.core.task.BuildValidity;
import com.dwinovo.numen.core.task.MultiBlockPlacement;
import com.dwinovo.numen.core.task.PlacementStatePredictor;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Read-only preflight for exact build cells. It previews vanilla state creation
 * from real standable cells and returns machine-branchable diagnoses before a
 * build task starts walking.
 */
public final class PlacementFeasibilityTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_TARGETS = 16;
    private static final int DEFAULT_RELOCATION_RADIUS = 3;
    private static final int MAX_RELOCATION_RADIUS = 4;
    private static final int STANCE_RADIUS = 3;
    private static final int MAX_STANCES_PER_CELL = 12;
    private static final int MAX_RELOCATION_CELLS = 8;
    private static final int MAX_RETURNED_STANCES = 8;

    private record PositionSpec(Integer x, Integer y, Integer z) {}

    private record Args(
            String workflow_id,
            List<PositionSpec> positions,
            Integer limit,
            List<BuildTool.BlockSpec> blocks,
            Boolean allow_replace,
            Integer relocation_radius) {}

    private record Source(
            List<BuildTaskRecord.Target> selectedTargets,
            Map<BlockPos, Integer> reservedCellCounts,
            boolean allowReplace,
            String workflowId,
            Long revision,
            Bounds bounds,
            int totalCandidates,
            boolean totalCandidatesExact,
            boolean truncated) {}

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    private record CellFacts(
            boolean survivalOk,
            boolean spaceAvailable,
            boolean entityClear,
            JsonArray footprint) {}

    private static final class StateOption {
        final BlockState state;
        final LinkedHashSet<BlockPos> stances = new LinkedHashSet<>();

        StateOption(BlockState state) {
            this.state = state;
        }
    }

    private record Scan(
            List<BlockPos> candidates,
            List<BlockPos> exactStances,
            LinkedHashMap<String, StateOption> options,
            Set<PlaceResolution.Reason> failures) {}

    private record Relocation(BlockPos pos, BlockState state, BlockPos stance) {}

    private record RepairProposal(
            JsonObject patch,
            String kind,
            BlockPos originalPosition,
            BlockPos replacementPosition,
            BlockPos stance) {}

    private record Analysis(
            JsonObject result,
            RepairProposal repair,
            boolean repairSearchPerformed) {}

    @Override
    public String name() {
        return "placement_feasibility";
    }

    @Override
    public String description() {
        return "Read-only placement preflight for exact build cells. Prefer workflow_id "
                + "(or `latest`) with optional positions to inspect remaining saved cells; "
                + "alternatively pass explicit blocks in build format. It checks current match, "
                + "support, survival rules, multi-block footprint, entities, real standable "
                + "positions, line of sight, and the exact state vanilla would create from each "
                + "position. Results use stable reason enums and may include a recommended_patch "
                + "that can be passed directly to structure_patch. No block, entity, inventory "
                + "or workflow is modified.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("workflow_id", Map.of(
                "type", "string",
                "description", "Saved workflow id, or latest. Omit when passing explicit blocks."));

        Map<String, Object> positionProps = new LinkedHashMap<>();
        positionProps.put("x", Map.of("type", "integer"));
        positionProps.put("y", Map.of("type", "integer"));
        positionProps.put("z", Map.of("type", "integer"));
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("type", "object");
        position.put("properties", positionProps);
        position.put("required", List.of("x", "y", "z"));
        position.put("additionalProperties", false);
        Map<String, Object> positions = new LinkedHashMap<>();
        positions.put("type", "array");
        positions.put("description",
                "Optional exact saved primary cells to inspect. Without this, inspect remaining mismatches.");
        positions.put("items", position);
        positions.put("minItems", 1);
        positions.put("maxItems", MAX_TARGETS);
        props.put("positions", positions);

        props.put("limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", MAX_TARGETS,
                "description", "Maximum cells to inspect; default " + DEFAULT_LIMIT + "."));

        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("type", "array");
        blocks.put("description",
                "Explicit target cells in build format. Mutually exclusive with workflow_id.");
        blocks.put("items", BuildTool.blockSpecSchema());
        blocks.put("minItems", 1);
        blocks.put("maxItems", MAX_TARGETS);
        props.put("blocks", blocks);

        props.put("allow_replace", Map.of(
                "type", "boolean",
                "description", "Explicit-block mode only; assume wrong cells may be cleared. Default true."));
        props.put("relocation_radius", Map.of(
                "type", "integer",
                "minimum", 0,
                "maximum", MAX_RELOCATION_RADIUS,
                "description", "Search radius for a safe same-block relocation; default 3, 0 disables."));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("oneOf", List.of(
                Map.of("required", List.of("workflow_id")),
                Map.of("required", List.of("blocks"))));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer self,
            Consumer<String> reply) {
        validateCoordinateArrays(args);
        Args parsed = GSON.fromJson(args, Args.class);
        int limit = parsed.limit() == null ? DEFAULT_LIMIT : parsed.limit();
        if (limit < 1 || limit > MAX_TARGETS) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_TARGETS);
        }
        int relocationRadius = parsed.relocation_radius() == null
                ? DEFAULT_RELOCATION_RADIUS
                : parsed.relocation_radius();
        if (relocationRadius < 0 || relocationRadius > MAX_RELOCATION_RADIUS) {
            throw new IllegalArgumentException(
                    "relocation_radius must be between 0 and "
                            + MAX_RELOCATION_RADIUS);
        }

        Source source = resolveSource(parsed, self, limit);
        JsonArray results = new JsonArray();
        RepairProposal repair = null;
        boolean repairSearchAvailable = true;
        int feasible = 0;
        int matched = 0;
        for (BuildTaskRecord.Target target : source.selectedTargets()) {
            Analysis analysis = analyze(
                    self, target, source, relocationRadius,
                    repairSearchAvailable);
            results.add(analysis.result());
            if (analysis.result().get("feasible").getAsBoolean()) {
                feasible++;
            }
            if (analysis.result().get("already_matches").getAsBoolean()) {
                matched++;
            }
            if (repair == null && analysis.repair() != null) {
                repair = analysis.repair();
            }
            if (analysis.repairSearchPerformed()) {
                repairSearchAvailable = false;
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("mode",
                source.workflowId() == null ? "explicit_blocks" : "workflow");
        if (source.workflowId() != null) {
            root.addProperty("workflow_id", source.workflowId());
            root.addProperty("revision", source.revision());
        }
        root.addProperty("read_only", true);
        root.addProperty("total_candidates", source.totalCandidates());
        root.addProperty("total_candidates_exact",
                source.totalCandidatesExact());
        root.addProperty("analyzed", results.size());
        root.addProperty("truncated", source.truncated());
        root.addProperty("feasible_count", feasible);
        root.addProperty("already_matched_count", matched);
        root.addProperty("repair_searches_per_call", 1);
        root.add("results", results);
        if (repair != null) {
            root.add("recommended_patch", repair.patch());
            root.addProperty("repair_kind", repair.kind());
            root.add("repair_for", posJson(repair.originalPosition()));
            root.add("replacement_position", posJson(repair.replacementPosition()));
            root.add("suggested_stance", posJson(repair.stance()));
            root.addProperty("more_repairs_require_recheck",
                    results.size() - feasible > 1);
        }
        reply.accept(root.toString());
    }

    private static Source resolveSource(
            Args parsed, NumenPlayer self, int limit) {
        boolean hasWorkflow = parsed.workflow_id() != null
                && !parsed.workflow_id().isBlank();
        boolean hasBlocks = parsed.blocks() != null && !parsed.blocks().isEmpty();
        if (hasWorkflow == hasBlocks) {
            throw new IllegalArgumentException(
                    "pass exactly one of workflow_id or blocks");
        }
        if (!hasWorkflow && parsed.positions() != null) {
            throw new IllegalArgumentException(
                    "positions can only filter a saved workflow");
        }

        List<BuildTaskRecord.Target> all;
        List<BuildTaskRecord.Target> candidates;
        boolean allowReplace;
        String workflowId = null;
        Long revision = null;
        if (hasWorkflow) {
            StructureWorkflowStore.Workflow workflow =
                    StructureWorkflowStore.resolve(self, parsed.workflow_id());
            all = workflow.cells.stream()
                    .map(StructureWorkflowStore::target)
                    .toList();
            candidates = selectWorkflowTargets(
                    all, parsed.positions(), self, limit);
            allowReplace = workflow.allowReplace;
            workflowId = workflow.id;
            revision = workflow.revision;
        } else {
            all = BuildTool.parseTargets(parsed.blocks(), MAX_TARGETS);
            candidates = all;
            allowReplace = parsed.allow_replace() == null
                    || parsed.allow_replace();
        }

        int total = candidates.size();
        boolean truncated = total > limit;
        boolean totalExact = !hasWorkflow
                || (parsed.positions() != null && !parsed.positions().isEmpty())
                || !truncated;
        List<BuildTaskRecord.Target> selected =
                List.copyOf(candidates.subList(0, Math.min(total, limit)));
        return new Source(
                selected,
                reservedCellCounts(all),
                allowReplace,
                workflowId,
                revision,
                bounds(all),
                total,
                totalExact,
                truncated);
    }

    private static List<BuildTaskRecord.Target> selectWorkflowTargets(
            List<BuildTaskRecord.Target> all,
            List<PositionSpec> positions,
            NumenPlayer self,
            int limit) {
        if (positions == null || positions.isEmpty()) {
            return all.stream()
                    .filter(target -> !footprintNeighborhoodLoaded(
                            self.level(),
                            target.pos(),
                            target.desiredState())
                            || !MultiBlockPlacement.matches(
                            self.level(), target))
                    .limit((long) limit + 1L)
                    .toList();
        }
        Map<Long, BuildTaskRecord.Target> byPos = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : all) {
            byPos.put(target.pos().asLong(), target);
        }
        List<BuildTaskRecord.Target> selected = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (PositionSpec spec : positions) {
            if (spec == null) {
                throw new IllegalArgumentException(
                        "positions must not contain null");
            }
            if (spec.x() == null || spec.y() == null || spec.z() == null) {
                throw new IllegalArgumentException(
                        "each workflow position needs x, y and z");
            }
            BlockPos pos = new BlockPos(spec.x(), spec.y(), spec.z());
            if (!seen.add(pos.asLong())) {
                throw new IllegalArgumentException(
                        "duplicate position " + pos.toShortString());
            }
            BuildTaskRecord.Target target = byPos.get(pos.asLong());
            if (target == null) {
                throw new IllegalArgumentException(
                        "workflow has no primary cell at "
                                + pos.toShortString());
            }
            selected.add(target);
        }
        return List.copyOf(selected);
    }

    private static Analysis analyze(
            NumenPlayer self,
            BuildTaskRecord.Target target,
            Source source,
            int relocationRadius,
            boolean allowRepairSearch) {
        Level level = self.level();
        JsonObject out = new JsonObject();
        out.add("position", posJson(target.pos()));
        out.addProperty("block_id",
                BuiltInRegistries.BLOCK.getKey(target.block()).toString());
        out.add("desired_state", stateJson(target.desiredState()));

        if (!footprintNeighborhoodLoaded(
                level, target.pos(), target.desiredState())) {
            out.add("current_state", stateJson(Blocks.VOID_AIR.defaultBlockState()));
            finish(out, false, false, "UNLOADED",
                    "target chunk is not loaded");
            addEmptyDetailArrays(out);
            return new Analysis(out, null, false);
        }

        BlockState current = level.getBlockState(target.pos());
        boolean matches = MultiBlockPlacement.matches(level, target);
        out.add("current_state", stateJson(current));
        out.addProperty("already_matches", matches);
        out.addProperty("inventory_count",
                inventoryCount(self, target.item()));
        if (matches) {
            out.addProperty("feasible", true);
            out.addProperty("reason", "ALREADY_MATCHES");
            out.addProperty("message",
                    "the requested state and every atomic partner cell already match");
            addEmptyDetailArrays(out);
            return new Analysis(out, null, false);
        }
        if (target.block() == Blocks.AIR) {
            out.addProperty("feasible", false);
            out.addProperty("reason", "NOT_A_PLACEABLE_TARGET");
            out.addProperty("message",
                    "air is a clearing target; placement feasibility does not apply");
            addEmptyDetailArrays(out);
            return new Analysis(out, null, false);
        }

        Set<BlockPos> reservedOther = reservedByOtherTargets(source, target);

        CellFacts requestedFacts = cellFacts(
                self, target.pos(), target.desiredState(),
                reservedOther, source.allowReplace());
        out.addProperty("survival_ok", requestedFacts.survivalOk());
        out.addProperty("footprint_available",
                requestedFacts.spaceAvailable());
        out.addProperty("entity_clear", requestedFacts.entityClear());
        out.add("footprint", requestedFacts.footprint());

        List<Direction> supports =
                Placement.supportDirections(level, target.pos());
        out.add("support", supportJson(target.pos(), supports));
        boolean requiresClear = requiresClearBeforePlacement(
                level, target, source.allowReplace());
        out.addProperty("requires_clear_before_placement", requiresClear);
        if (requiresClear) {
            out.addProperty("candidate_stances_checked", 0);
            out.add("achievable_states", new JsonArray());
            out.add("suggested_stances", new JsonArray());
            out.addProperty("feasible", false);
            out.addProperty("reason", "RECHECK_AFTER_CLEAR");
            out.addProperty("message",
                    message("RECHECK_AFTER_CLEAR", target));
            out.addProperty("repair_available", false);
            return new Analysis(out, null, false);
        }

        Scan scan = scanAt(
                self, target, target.pos(), reservedOther,
                source.allowReplace(), requiresClear, MAX_STANCES_PER_CELL);
        out.addProperty("candidate_stances_checked",
                scan.candidates().size());
        out.add("achievable_states", optionsJson(target, scan.options()));
        List<BlockPos> suggested = !scan.exactStances().isEmpty()
                ? scan.exactStances()
                : firstOptionStances(scan.options());
        out.add("suggested_stances", positionsJson(
                suggested, MAX_RETURNED_STANCES));

        boolean exact = !scan.exactStances().isEmpty()
                && !requiresClear
                && requestedFacts.survivalOk()
                && requestedFacts.spaceAvailable()
                && requestedFacts.entityClear();
        String reason = classify(
                exact, requestedFacts, supports, scan, requiresClear);
        out.addProperty("feasible", exact);
        out.addProperty("reason", reason);
        out.addProperty("message", message(reason, target));

        RepairProposal repair = null;
        boolean repairSearchPerformed = !exact
                && !requiresClear
                && source.workflowId() != null
                && repairableReason(reason)
                && allowRepairSearch;
        if (repairSearchPerformed) {
            StateOption sameCellAlternative =
                    bestAlternative(target, scan.options());
            if (sameCellAlternative != null && footprintInside(
                    source.bounds(), target.pos(),
                    sameCellAlternative.state)) {
                BlockPos stance = sameCellAlternative.stances.iterator().next();
                repair = repair(
                        source, target.pos(), target.pos(),
                        sameCellAlternative.state, target.item(),
                        "UPDATE_STATE", stance);
            } else if (relocationRadius > 0) {
                Relocation relocation = findRelocation(
                        self, target, source, reservedOther,
                        relocationRadius);
                if (relocation != null) {
                    repair = repair(
                            source, target.pos(), relocation.pos(),
                            relocation.state(), target.item(),
                            "RELOCATE", relocation.stance());
                }
            }
        }
        if (repair != null) {
            out.addProperty("repair_available", true);
            out.addProperty("repair_kind", repair.kind());
            out.add("replacement_position",
                    posJson(repair.replacementPosition()));
            out.add("suggested_repair_stance", posJson(repair.stance()));
        } else {
            out.addProperty("repair_available", false);
        }
        return new Analysis(out, repair, repairSearchPerformed);
    }

    private static void finish(
            JsonObject out,
            boolean matches,
            boolean feasible,
            String reason,
            String message) {
        out.addProperty("already_matches", matches);
        out.addProperty("feasible", feasible);
        out.addProperty("reason", reason);
        out.addProperty("message", message);
    }

    private static void addEmptyDetailArrays(JsonObject out) {
        out.addProperty("survival_ok", false);
        out.addProperty("footprint_available", false);
        out.addProperty("entity_clear", false);
        out.add("footprint", new JsonArray());
        out.add("support", new JsonArray());
        out.addProperty("candidate_stances_checked", 0);
        out.add("suggested_stances", new JsonArray());
        out.add("achievable_states", new JsonArray());
    }

    private static Scan scanAt(
            NumenPlayer self,
            BuildTaskRecord.Target requested,
            BlockPos placeAt,
            Set<BlockPos> reservedOther,
            boolean allowReplace,
            boolean rejectDirectTargetClick,
            int stanceLimit) {
        Level level = self.level();
        Set<BlockPos> requestedFootprint =
                footprintPositions(placeAt, requested.desiredState());
        Set<BlockPos> excludedFeet = new LinkedHashSet<>(reservedOther);
        excludedFeet.addAll(requestedFootprint);
        List<BlockPos> candidates = candidateFeet(
                placeAt,
                excludedFeet,
                STANCE_RADIUS,
                feet -> stanceCellsLoaded(level, feet)
                        && BlockHelper.isStandable(level, feet))
                .stream()
                .limit(stanceLimit)
                .toList();

        LinkedHashMap<String, StateOption> options = new LinkedHashMap<>();
        List<BlockPos> exactStances = new ArrayList<>();
        Set<PlaceResolution.Reason> failures = new LinkedHashSet<>();
        ItemStack stack = new ItemStack(requested.item());
        BuildTaskRecord.Target placedAt = targetAt(requested, placeAt);
        for (BlockPos feet : candidates) {
            Vec3 eye = Vec3.atBottomCenterOf(feet).add(
                    0.0, self.getEyeHeight(Pose.CROUCHING), 0.0);
            PlaceResolution resolution = Placement.resolveDetailedFromEye(
                    self,
                    placeAt,
                    eye,
                    PlacementStatePredictor.aimY(placedAt),
                    hit -> {
                        if (rejectDirectTargetClick
                                && hit.getBlockPos().equals(placeAt)) {
                            return false;
                        }
                        BlockState predicted = PlacementStatePredictor.predict(
                                self, stack, hit, null, null);
                        if (predicted == null
                                || predicted.getBlock() != requested.block()
                                || stanceIntersectsFootprint(
                                feet, placeAt, predicted)
                                || !physicallyPossible(
                                self, placeAt, predicted,
                                reservedOther, allowReplace)) {
                            return false;
                        }
                        StateOption option = options.computeIfAbsent(
                                stateKey(predicted),
                                ignored -> new StateOption(predicted));
                        option.stances.add(feet.immutable());
                        return requested.acceptsPlacedState(predicted);
                    });
            if (resolution.ok()) {
                exactStances.add(feet.immutable());
            } else {
                failures.add(resolution.reason());
            }
        }
        return new Scan(
                candidates,
                List.copyOf(exactStances),
                options,
                failures);
    }

    private static Relocation findRelocation(
            NumenPlayer self,
            BuildTaskRecord.Target target,
            Source source,
            Set<BlockPos> reservedOther,
            int radius) {
        int checked = 0;
        Relocation alternative = null;
        for (BlockPos offset : relocationOffsets(radius)) {
            if (checked++ >= MAX_RELOCATION_CELLS) {
                break;
            }
            BlockPos pos = target.pos().offset(offset);
            if (pos.equals(target.pos())
                    || !footprintInside(
                    source.bounds(), pos, target.desiredState())
                    || !footprintNeighborhoodLoaded(
                    self.level(), pos, target.desiredState())
                    || reservedOther.contains(pos)) {
                continue;
            }
            BuildTaskRecord.Target relocatedTarget = targetAt(target, pos);
            if (requiresClearBeforePlacement(
                    self.level(), relocatedTarget, source.allowReplace())) {
                continue;
            }
            Scan scan = scanAt(
                    self, target, pos, reservedOther,
                    source.allowReplace(), false,
                    MAX_STANCES_PER_CELL / 2);
            if (!scan.exactStances().isEmpty()) {
                return new Relocation(
                        pos.immutable(),
                        target.desiredState(),
                        scan.exactStances().get(0));
            }
            if (alternative == null) {
                StateOption option = bestAlternative(target, scan.options());
                if (option != null && footprintInside(
                        source.bounds(), pos, option.state)) {
                    alternative = new Relocation(
                            pos.immutable(),
                            option.state,
                            option.stances.iterator().next());
                }
            }
        }
        return alternative;
    }

    private static BuildTaskRecord.Target targetAt(
            BuildTaskRecord.Target source, BlockPos pos) {
        return new BuildTaskRecord.Target(
                source.desiredState(),
                source.item(),
                pos,
                source.label(),
                source.facing(),
                source.axis(),
                source.topHalf());
    }

    private static CellFacts cellFacts(
            NumenPlayer self,
            BlockPos pos,
            BlockState state,
            Set<BlockPos> reservedOther,
            boolean allowReplace) {
        Level level = self.level();
        JsonArray cellsJson = new JsonArray();
        boolean survivalOk = true;
        boolean spaceAvailable = true;
        boolean entityClear = true;
        List<MultiBlockPlacement.Cell> cells =
                MultiBlockPlacement.footprint(pos, state);
        for (int index = 0; index < cells.size(); index++) {
            MultiBlockPlacement.Cell cell = cells.get(index);
            BlockState current = level.getBlockState(cell.pos());
            boolean survivalChecked = index == 0
                    || state.getBlock() instanceof BedBlock;
            boolean survives = !survivalChecked
                    || cell.state().canSurvive(level, cell.pos());
            boolean reserved = reservedOther.contains(cell.pos());
            boolean replaceable = MovementHelper.isReplaceable(
                    cell.pos().getX(),
                    cell.pos().getY(),
                    cell.pos().getZ(),
                    current,
                    ChunkLoadedTest.ALWAYS);
            boolean mayClear = allowReplace
                    && !BlockHelper.shouldAvoidBreaking(level, cell.pos());
            boolean space = !reserved
                    && (replaceable || mayClear);
            boolean clear = !blockedByEntity(
                    self, cell.pos(), cell.state());

            survivalOk &= survives;
            spaceAvailable &= space;
            entityClear &= clear;

            JsonObject cellJson = new JsonObject();
            cellJson.add("position", posJson(cell.pos()));
            cellJson.add("desired_state", stateJson(cell.state()));
            cellJson.add("current_state", stateJson(current));
            cellJson.addProperty("survival_checked", survivalChecked);
            cellJson.addProperty("survival_ok", survives);
            cellJson.addProperty("replaceable_now", replaceable);
            cellJson.addProperty("clearable_if_allowed", mayClear);
            cellJson.addProperty("reserved_by_other_target", reserved);
            cellJson.addProperty("entity_clear", clear);
            cellsJson.add(cellJson);
        }
        return new CellFacts(
                survivalOk, spaceAvailable, entityClear, cellsJson);
    }

    private static boolean physicallyPossible(
            NumenPlayer self,
            BlockPos pos,
            BlockState state,
            Set<BlockPos> reservedOther,
            boolean allowReplace) {
        Level level = self.level();
        if (!footprintNeighborhoodLoaded(level, pos, state)) {
            return false;
        }
        List<MultiBlockPlacement.Cell> cells =
                MultiBlockPlacement.footprint(pos, state);
        for (int index = 0; index < cells.size(); index++) {
            MultiBlockPlacement.Cell cell = cells.get(index);
            if ((index == 0 || state.getBlock() instanceof BedBlock)
                    && !cell.state().canSurvive(level, cell.pos())) {
                return false;
            }
            if (reservedOther.contains(cell.pos())) {
                return false;
            }
            BlockState current = level.getBlockState(cell.pos());
            boolean replaceable = MovementHelper.isReplaceable(
                    cell.pos().getX(),
                    cell.pos().getY(),
                    cell.pos().getZ(),
                    current,
                    ChunkLoadedTest.ALWAYS);
            boolean mayClear = allowReplace
                    && !BlockHelper.shouldAvoidBreaking(level, cell.pos());
            if ((!replaceable && !mayClear)
                    || blockedByEntity(self, cell.pos(), cell.state())) {
                return false;
            }
        }
        return true;
    }

    private static boolean blockedByEntity(
            NumenPlayer self, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(
                self.level(), pos);
        return !shape.isEmpty() && !self.level().isUnobstructed(
                self,
                shape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    static List<BlockPos> candidateFeet(
            BlockPos placeAt,
            Set<BlockPos> excluded,
            int radius,
            Predicate<BlockPos> standable) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy : new int[]{0, 1, -1}) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos feet = placeAt.offset(dx, dy, dz);
                    if (!excluded.contains(feet) && standable.test(feet)) {
                        candidates.add(feet.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt((BlockPos p) ->
                        horizontalDistanceSqr(placeAt, p))
                .thenComparingInt(p ->
                        Math.abs(p.getY() - placeAt.getY()))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(candidates);
    }

    static List<BlockPos> relocationOffsets(int radius) {
        List<BlockPos> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                offsets.add(new BlockPos(dx, 0, dz));
            }
        }
        offsets.sort(Comparator
                .comparingInt((BlockPos p) ->
                        p.getX() * p.getX() + p.getZ() * p.getZ())
                .thenComparingInt(p ->
                        Math.abs(p.getX()) + Math.abs(p.getZ()))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(offsets);
    }

    private static int horizontalDistanceSqr(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static String classify(
            boolean exact,
            CellFacts facts,
            List<Direction> supports,
            Scan scan,
            boolean requiresClear) {
        if (exact) {
            return "FEASIBLE";
        }
        if (!facts.entityClear()) {
            return "BLOCKED_BY_ENTITY";
        }
        if (!facts.spaceAvailable()) {
            return "FOOTPRINT_BLOCKED";
        }
        if (!facts.survivalOk()) {
            return "SURVIVAL_FAILED";
        }
        if (requiresClear) {
            return "RECHECK_AFTER_CLEAR";
        }
        if (supports.isEmpty()) {
            return "NO_SUPPORT";
        }
        if (scan.candidates().isEmpty()) {
            return "NO_STANDABLE_STANCE";
        }
        if (!scan.options().isEmpty()
                || scan.failures().contains(
                PlaceResolution.Reason.STATE_MISMATCH)) {
            return "STATE_MISMATCH";
        }
        if (scan.failures().contains(
                PlaceResolution.Reason.BLOCKED_BY_ENTITY)) {
            return "BLOCKED_BY_ENTITY";
        }
        if (scan.failures().contains(
                PlaceResolution.Reason.NO_LINE_OF_SIGHT)) {
            return "OCCLUDED";
        }
        if (scan.failures().contains(
                PlaceResolution.Reason.OUT_OF_REACH)) {
            return "OUT_OF_REACH";
        }
        return "NO_FEASIBLE_PLACEMENT";
    }

    private static String message(
            String reason, BuildTaskRecord.Target target) {
        return switch (reason) {
            case "FEASIBLE" -> "at least one real standable cell can create the requested state";
            case "BLOCKED_BY_ENTITY" -> "an entity overlaps the required placement footprint";
            case "FOOTPRINT_BLOCKED" -> "the requested atomic footprint intersects an occupied or reserved cell";
            case "SURVIVAL_FAILED" -> "the requested state fails vanilla support/survival rules here";
            case "RECHECK_AFTER_CLEAR" -> "a wrong solid block must be cleared first; placement rays must be rechecked after that world change";
            case "NO_SUPPORT" -> "no neighbour exposes a support face accepted by the live resolver";
            case "NO_STANDABLE_STANCE" -> "no free standable cell was found near the target";
            case "STATE_MISMATCH" -> "reachable clicks exist, but they create another facing, half, or state";
            case "OCCLUDED" -> "candidate stances exist, but walls or blocks occlude every support-face ray";
            case "OUT_OF_REACH" -> "candidate stances exist, but support faces remain beyond interaction reach";
            default -> "no verified placement was found for "
                    + target.label() + " at " + target.shortPos();
        };
    }

    private static boolean repairableReason(String reason) {
        return switch (reason) {
            case "STATE_MISMATCH",
                    "NO_SUPPORT",
                    "SURVIVAL_FAILED",
                    "FOOTPRINT_BLOCKED",
                    "NO_STANDABLE_STANCE",
                    "NO_FEASIBLE_PLACEMENT" -> true;
            default -> false;
        };
    }

    private static StateOption bestAlternative(
            BuildTaskRecord.Target target,
            Map<String, StateOption> options) {
        return options.values().stream()
                .filter(option ->
                        !target.acceptsPlacedState(option.state)
                                && !option.stances.isEmpty())
                .min(Comparator
                        .comparingInt((StateOption option) ->
                                propertyDifferenceCount(
                                        target.desiredState(), option.state))
                        .thenComparing(option -> stateKey(option.state)))
                .orElse(null);
    }

    private static int propertyDifferenceCount(
            BlockState expected, BlockState actual) {
        int differences = expected.getBlock() == actual.getBlock() ? 0 : 1000;
        for (Property<?> property : expected.getProperties()) {
            if (!actual.hasProperty(property)
                    || !propertyValue(expected, property).equals(
                    propertyValue(actual, property))) {
                differences++;
            }
        }
        return differences;
    }

    private static RepairProposal repair(
            Source source,
            BlockPos oldPos,
            BlockPos newPos,
            BlockState state,
            Item item,
            String kind,
            BlockPos stance) {
        JsonObject patch = new JsonObject();
        patch.addProperty("workflow_id", source.workflowId());
        patch.addProperty("expected_revision", source.revision());
        JsonArray upsert = new JsonArray();
        upsert.add(blockSpecJson(newPos, state, item));
        patch.add("upsert", upsert);
        if (!oldPos.equals(newPos)) {
            JsonArray removals = new JsonArray();
            removals.add(posJson(oldPos));
            patch.add("remove_positions", removals);
        }
        return new RepairProposal(
                patch,
                kind,
                oldPos.immutable(),
                newPos.immutable(),
                stance.immutable());
    }

    private static JsonArray optionsJson(
            BuildTaskRecord.Target target,
            Map<String, StateOption> options) {
        JsonArray array = new JsonArray();
        for (StateOption option : options.values()) {
            JsonObject json = new JsonObject();
            json.add("state", stateJson(option.state));
            json.addProperty("matches_requested",
                    target.acceptsPlacedState(option.state));
            json.add("stances", positionsJson(
                    new ArrayList<>(option.stances),
                    MAX_RETURNED_STANCES));
            array.add(json);
        }
        return array;
    }

    private static List<BlockPos> firstOptionStances(
            Map<String, StateOption> options) {
        if (options.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(options.values().iterator().next().stances);
    }

    private static JsonArray supportJson(
            BlockPos placeAt, List<Direction> supports) {
        JsonArray array = new JsonArray();
        for (Direction direction : supports) {
            JsonObject json = new JsonObject();
            json.addProperty("direction", direction.getName());
            json.add("against", posJson(placeAt.relative(direction)));
            array.add(json);
        }
        return array;
    }

    private static JsonArray positionsJson(
            List<BlockPos> positions, int limit) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < Math.min(positions.size(), limit); i++) {
            array.add(posJson(positions.get(i)));
        }
        return array;
    }

    private static JsonObject posJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static JsonObject blockSpecJson(
            BlockPos pos, BlockState state, Item item) {
        JsonObject json = posJson(pos);
        json.addProperty("block_id",
                BuiltInRegistries.ITEM.getKey(item).toString());
        JsonObject properties = new JsonObject();
        for (Property<?> property : state.getProperties()) {
            properties.addProperty(
                    property.getName(), propertyValue(state, property));
        }
        if (properties.size() > 0) {
            json.add("properties", properties);
        }
        return json;
    }

    private static JsonObject stateJson(BlockState state) {
        JsonObject json = new JsonObject();
        json.addProperty("block_id",
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        for (Property<?> property : state.getProperties()) {
            properties.addProperty(
                    property.getName(), propertyValue(state, property));
        }
        json.add("properties", properties);
        return json;
    }

    private static String stateKey(BlockState state) {
        StringBuilder key = new StringBuilder(
                BuiltInRegistries.BLOCK.getKey(
                        state.getBlock()).toString());
        for (Property<?> property : state.getProperties()) {
            key.append('|')
                    .append(property.getName())
                    .append('=')
                    .append(propertyValue(state, property));
        }
        return key.toString();
    }

    private static <T extends Comparable<T>> String propertyValue(
            BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static int inventoryCount(
            NumenPlayer self, Item item) {
        int count = 0;
        for (ItemStack stack : self.getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static Set<BlockPos> footprintPositions(
            BlockPos pos, BlockState state) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (MultiBlockPlacement.Cell cell :
                MultiBlockPlacement.footprint(pos, state)) {
            positions.add(cell.pos().immutable());
        }
        return positions;
    }

    static boolean stanceIntersectsFootprint(
            BlockPos feet, BlockPos pos, BlockState state) {
        Set<BlockPos> footprint = footprintPositions(pos, state);
        return footprint.contains(feet) || footprint.contains(feet.above());
    }

    private static Map<BlockPos, Integer> reservedCellCounts(
            List<BuildTaskRecord.Target> targets) {
        Map<BlockPos, Integer> counts = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : targets) {
            for (BlockPos pos : footprintPositions(
                    target.pos(), target.desiredState())) {
                counts.merge(pos, 1, Integer::sum);
            }
        }
        return Map.copyOf(counts);
    }

    private static Set<BlockPos> reservedByOtherTargets(
            Source source, BuildTaskRecord.Target target) {
        Map<BlockPos, Integer> counts =
                new LinkedHashMap<>(source.reservedCellCounts());
        for (BlockPos pos : footprintPositions(
                target.pos(), target.desiredState())) {
            counts.computeIfPresent(pos, (ignored, count) ->
                    count <= 1 ? null : count - 1);
        }
        return Set.copyOf(counts.keySet());
    }

    private static boolean footprintInside(
            Bounds bounds, BlockPos pos, BlockState state) {
        for (BlockPos cell : footprintPositions(pos, state)) {
            if (!bounds.contains(cell)) {
                return false;
            }
        }
        return true;
    }

    private static boolean footprintNeighborhoodLoaded(
            Level level, BlockPos pos, BlockState state) {
        for (BlockPos cell : footprintPositions(pos, state)) {
            if (!level.hasChunkAt(cell)) {
                return false;
            }
            for (Direction direction : Direction.values()) {
                if (!level.hasChunkAt(cell.relative(direction))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean stanceCellsLoaded(Level level, BlockPos feet) {
        return level.hasChunkAt(feet.below())
                && level.hasChunkAt(feet)
                && level.hasChunkAt(feet.above());
    }

    private static boolean requiresClearBeforePlacement(
            Level level,
            BuildTaskRecord.Target target,
            boolean allowReplace) {
        for (MultiBlockPlacement.Cell cell : MultiBlockPlacement.footprint(
                target.pos(), target.desiredState())) {
            BlockState current = level.getBlockState(cell.pos());
            if (BuildValidity.valid(current, cell.state(), false)) {
                continue;
            }
            boolean replaceable = MovementHelper.isReplaceable(
                    cell.pos().getX(),
                    cell.pos().getY(),
                    cell.pos().getZ(),
                    current,
                    ChunkLoadedTest.ALWAYS);
            if (!replaceable
                    && allowReplace
                    && !BlockHelper.shouldAvoidBreaking(level, cell.pos())) {
                return true;
            }
        }
        return false;
    }

    private static void validateCoordinateArrays(JsonObject args) {
        if (args == null) {
            throw new IllegalArgumentException(
                    "placement feasibility arguments are required");
        }
        if (args.has("blocks")) {
            if (!args.get("blocks").isJsonArray()) {
                throw new IllegalArgumentException("blocks must be an array");
            }
            StructurePatchTool.validateBlockSpecs(
                    args.getAsJsonArray("blocks"), "blocks");
        }
        if (args.has("positions")) {
            if (!args.get("positions").isJsonArray()) {
                throw new IllegalArgumentException("positions must be an array");
            }
            StructurePatchTool.validatePositions(
                    args.getAsJsonArray("positions"), "positions");
        }
    }

    private static Bounds bounds(
            List<BuildTaskRecord.Target> targets) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BuildTaskRecord.Target target : targets) {
            for (BlockPos pos : footprintPositions(
                    target.pos(), target.desiredState())) {
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
