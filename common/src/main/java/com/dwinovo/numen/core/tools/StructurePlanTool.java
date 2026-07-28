package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Register or revise a persistent, explicit blueprint before performing a
 * multi-stage construction goal.
 */
public final class StructurePlanTool implements NumenTool {

    static final int MAX_CELLS = 1_536;
    private static final int MAX_AXIS = 32;
    private static final int MAX_HORIZONTAL_DISTANCE = 48;
    private static final int MAX_VERTICAL_DISTANCE = 32;
    private static final Gson GSON = new Gson();

    private record Args(String workflow_id, String name, String goal,
                        Boolean allow_replace, List<BuildTool.BlockSpec> blocks) {}

    @Override
    public String name() {
        return "structure_plan";
    }

    @Override
    public String description() {
        return "Create or revise a persistent exact-cell blueprint for a multi-step structure goal. "
                + "Use this after observing the site and inventory, before building a house, room, "
                + "farm, bridge, detailed furnishing, or other construction larger than a tiny "
                + "correction. The result gives a workflow_id, live progress, material ledger, and "
                + "next phase. Later calls to structure_execute build or demolish bounded batches "
                + "without resending every cell; structure_status verifies the world and survives "
                + "brain/server restarts. Each block is an absolute intended cell just like build. "
                + "Omit automatically-created partner cells (for example the upper half of a door "
                + "or the head half of a bed), then verify those cells from the world. Default "
                + "allow_replace=false protects occupied cells. Set it true only after a fresh site "
                + "observation proves that clearing the listed cells is intended.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("workflow_id", Map.of(
                "type", "string",
                "description", "Optional existing workflow id to revise. Omit to create one; use the returned id later."));
        props.put("name", Map.of(
                "type", "string",
                "description", "Short stable structure name, e.g. momo_oak_cottage."));
        props.put("goal", Map.of(
                "type", "string",
                "description", "Concise human goal and important design choices."));
        props.put("allow_replace", Map.of(
                "type", "boolean",
                "description", "Default false. Permit clearing wrong occupied cells only when the surveyed site makes this safe."));

        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("type", "array");
        blocks.put("description",
                "Complete explicit blueprint in absolute world coordinates, including furniture placement cells.");
        blocks.put("items", BuildTool.blockSpecSchema());
        blocks.put("minItems", 1);
        blocks.put("maxItems", MAX_CELLS);
        props.put("blocks", blocks);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("name", "goal", "blocks"));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        List<BuildTaskRecord.Target> targets =
                BuildTool.parseTargets(parsed.blocks(), MAX_CELLS);
        validateBounds(self, targets);
        StructureWorkflowStore.Workflow workflow = StructureWorkflowStore.put(
                self,
                parsed.workflow_id(),
                parsed.name(),
                parsed.goal(),
                Boolean.TRUE.equals(parsed.allow_replace()),
                targets);
        StructureWorkflowAssessment assessment =
                StructureWorkflowAssessment.inspect(workflow, self);
        JsonObject result = assessment.render(self, "build");
        result.addProperty("manifest_persisted", true);
        result.addProperty("checkpoint_batch_max", StructureExecuteTool.MAX_BATCH);
        reply.accept(result.toString());
    }

    private static void validateBounds(
            NumenPlayer self, List<BuildTaskRecord.Target> targets) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        BlockPos feet = self.blockPosition();
        for (BuildTaskRecord.Target target : targets) {
            BlockPos pos = target.pos();
            if (pos.getY() < self.level().getMinBuildHeight()
                    || pos.getY() >= self.level().getMaxBuildHeight()) {
                throw new IllegalArgumentException(
                        "blueprint cell outside build height at " + pos.toShortString());
            }
            if (!self.level().hasChunkAt(pos)) {
                throw new IllegalArgumentException(
                        "every blueprint cell must be loaded while planning; move closer to "
                                + pos.toShortString());
            }
            if (Math.abs((long) pos.getX() - feet.getX()) > MAX_HORIZONTAL_DISTANCE
                    || Math.abs((long) pos.getZ() - feet.getZ()) > MAX_HORIZONTAL_DISTANCE
                    || Math.abs((long) pos.getY() - feet.getY()) > MAX_VERTICAL_DISTANCE) {
                throw new IllegalArgumentException(
                        "blueprint cells must stay within 48 horizontal and 32 vertical blocks of me");
            }
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        if ((long) maxX - minX + 1 > MAX_AXIS
                || (long) maxY - minY + 1 > MAX_AXIS
                || (long) maxZ - minZ + 1 > MAX_AXIS) {
            throw new IllegalArgumentException(
                    "one structure workflow may span at most 32 blocks on each axis");
        }
    }
}
