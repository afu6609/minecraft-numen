package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.dispatchAsync;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.core.task.PlayerInv;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turn the next part of a saved blueprint into one bounded local player task.
 * The model plans once; the normal task engine walks and acts through the batch.
 */
public final class StructureExecuteTool implements NumenTool {

    static final int MAX_BATCH = 128;
    private static final int DEFAULT_BATCH = 64;
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;
    private static final long TICKS_PER_CELL = 20 * 20;
    private static final Gson GSON = new Gson();

    private record Args(String workflow_id, String operation, Integer max_cells) {}

    @Override
    public String name() {
        return "structure_execute";
    }

    @Override
    public String description() {
        return "Execute ONE checkpoint batch from a persistent structure workflow as a real player. "
                + "For build, it uses the saved blueprint, refuses unloaded/conflicting cells and "
                + "reports material shortfalls before starting. For demolish, it clears only saved "
                + "coordinates whose current block still matches that blueprint and skips changed "
                + "cells. A batch may contain 1-128 exact cells (default 64), so walking, aiming, "
                + "placing and breaking continue locally without one model call per block. It still "
                + "looks like normal player work, not an instant /fill. BACKGROUND task: after a "
                + "task_id is returned, end the turn and wait for task_finished; then call "
                + "structure_status to verify/replan before the next batch.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("workflow_id", Map.of(
                "type", "string",
                "description", "Optional saved workflow id; omit or use latest for the most recent one."));
        props.put("operation", Map.of(
                "type", "string",
                "enum", List.of("build", "demolish"),
                "description", "Build the blueprint or demolish that exact saved structure; default build."));
        props.put("max_cells", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", MAX_BATCH,
                "description", "Maximum exact cells in this checkpoint; default 64."));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        String operation = StructureStatusTool.operation(parsed.operation());
        int maxCells = parsed.max_cells() == null ? DEFAULT_BATCH : parsed.max_cells();
        if (maxCells < 1 || maxCells > MAX_BATCH) {
            throw new IllegalArgumentException(
                    "max_cells must be from 1 to " + MAX_BATCH);
        }

        StructureWorkflowStore.Workflow workflow =
                StructureWorkflowStore.resolve(self, parsed.workflow_id());
        StructureWorkflowAssessment assessment =
                StructureWorkflowAssessment.inspect(workflow, self);
        if (assessment.unloaded > 0) {
            throw new IllegalArgumentException(
                    "workflow has " + assessment.unloaded
                            + " unloaded cell(s); move near the saved bounds before executing");
        }

        List<BuildTaskRecord.Target> batch;
        boolean replaceExisting;
        if (operation.equals("demolish")) {
            batch = demolitionBatch(assessment, maxCells);
            replaceExisting = true;
        } else {
            if (!assessment.buildConflicts.isEmpty()) {
                throw new IllegalArgumentException(
                        "workflow has " + assessment.buildConflicts.size()
                                + " occupied conflict(s) and allow_replace=false; inspect/revise first");
            }
            batch = buildBatch(assessment, self, maxCells);
            replaceExisting = workflow.allowReplace;
        }

        if (batch.isEmpty()) {
            reply.accept(assessment.render(self, operation).toString());
            return;
        }

        StructureWorkflowStore.touch(self, workflow, operation);
        long timeout = Math.max(
                MIN_TIMEOUT_TICKS, (long) batch.size() * TICKS_PER_CELL);
        dispatchAsync(self, new BuildTaskRecord(
                toolCallId,
                ctx(toolCallId, self).deadline(timeout),
                batch,
                replaceExisting,
                0),
                reply);
    }

    private static List<BuildTaskRecord.Target> buildBatch(
            StructureWorkflowAssessment assessment, NumenPlayer self, int maxCells) {
        List<BuildTaskRecord.Target> candidates =
                new ArrayList<>(assessment.buildCandidates);
        candidates.sort(Comparator
                .comparingInt((BuildTaskRecord.Target target) ->
                        target.desiredState().isAir() ? 0 : 1)
                .thenComparingInt(target -> target.pos().getY()));
        List<BuildTaskRecord.Target> batch =
                new ArrayList<>(candidates.subList(0, Math.min(maxCells, candidates.size())));

        Map<Item, Integer> needed = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : batch) {
            if (!target.desiredState().isAir()) {
                needed.merge(target.item(), 1, Integer::sum);
            }
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            int have = PlayerInv.count(self.getInventory(), entry.getKey());
            if (have < entry.getValue()) {
                missing.add(BuiltInRegistries.ITEM.getKey(entry.getKey())
                        + " x" + (entry.getValue() - have));
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "next structure batch needs materials: " + String.join(", ", missing)
                            + "; gather/craft them and check structure_status again");
        }
        return List.copyOf(batch);
    }

    private static List<BuildTaskRecord.Target> demolitionBatch(
            StructureWorkflowAssessment assessment, int maxCells) {
        List<BuildTaskRecord.Target> candidates =
                new ArrayList<>(assessment.demolitionCandidates);
        candidates.sort(Comparator
                .comparingInt((BuildTaskRecord.Target target) -> target.pos().getY())
                .reversed());
        if (candidates.isEmpty() && !assessment.demolitionConflicts.isEmpty()) {
            throw new IllegalArgumentException(
                    "no saved blueprint block still matches; "
                            + assessment.demolitionConflicts.size()
                            + " changed cell(s) were skipped for safety");
        }
        List<BuildTaskRecord.Target> batch = new ArrayList<>();
        for (int index = 0; index < Math.min(maxCells, candidates.size()); index++) {
            BuildTaskRecord.Target source = candidates.get(index);
            batch.add(new BuildTaskRecord.Target(
                    Blocks.AIR,
                    Items.AIR,
                    source.pos(),
                    "air",
                    null,
                    null,
                    null));
        }
        return List.copyOf(batch);
    }
}
