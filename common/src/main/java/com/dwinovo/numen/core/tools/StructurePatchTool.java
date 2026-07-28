package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Atomically revise only selected cells of a persistent structure blueprint.
 */
public final class StructurePatchTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private static final Set<String> ROOT_FIELDS = Set.of(
            "workflow_id", "expected_revision", "upsert", "remove_positions");
    private static final Set<String> BLOCK_FIELDS = Set.of(
            "block_id", "x", "y", "z", "facing", "axis", "half", "properties");
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");

    private record PositionSpec(Integer x, Integer y, Integer z) {}

    private record Args(
            String workflow_id,
            Long expected_revision,
            List<BuildTool.BlockSpec> upsert,
            List<PositionSpec> remove_positions) {}

    @Override
    public String name() {
        return "structure_patch";
    }

    @Override
    public String description() {
        return "Atomically patch selected cells of an existing persistent structure blueprint "
                + "without resending the complete manifest. Pass the exact workflow_id and the "
                + "revision returned by structure_plan or structure_status. `upsert` adds or "
                + "replaces desired cells while preserving each already-saved cell's original "
                + "before state; a newly-added cell records its current world state for safe "
                + "demolition/undo. `remove_positions` deletes exact saved coordinates from the manifest. "
                + "The whole patch is rejected on a stale revision, invalid coordinate, duplicate, "
                + "remove/upsert overlap, no-op upsert, unloaded or out-of-range result, or size violation. "
                + "Use this after observing a small design or placement problem, then call "
                + "structure_execute again with the returned incremented revision.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("workflow_id", Map.of(
                "type", "string",
                "description", "Exact saved workflow id; `latest` is intentionally not accepted."));
        props.put("expected_revision", Map.of(
                "type", "integer",
                "minimum", 1,
                "description", "Revision from the latest structure_plan, structure_patch or structure_status result."));

        Map<String, Object> upsert = new LinkedHashMap<>();
        upsert.put("type", "array");
        upsert.put("description",
                "Desired cells to add or replace, using the same exact block-state format as structure_plan.");
        upsert.put("items", BuildTool.blockSpecSchema());
        upsert.put("minItems", 1);
        upsert.put("maxItems", StructurePlanTool.MAX_CELLS);
        props.put("upsert", upsert);

        Map<String, Object> positionProps = new LinkedHashMap<>();
        positionProps.put("x", Map.of("type", "integer", "description", "Saved cell x."));
        positionProps.put("y", Map.of("type", "integer", "description", "Saved cell y."));
        positionProps.put("z", Map.of("type", "integer", "description", "Saved cell z."));
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("type", "object");
        position.put("properties", positionProps);
        position.put("required", List.of("x", "y", "z"));
        position.put("additionalProperties", false);

        Map<String, Object> removePositions = new LinkedHashMap<>();
        removePositions.put("type", "array");
        removePositions.put("description", "Exact saved positions to remove from the blueprint.");
        removePositions.put("items", position);
        removePositions.put("minItems", 1);
        removePositions.put("maxItems", StructurePlanTool.MAX_CELLS);
        props.put("remove_positions", removePositions);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("workflow_id", "expected_revision"));
        root.put("anyOf", List.of(
                Map.of("required", List.of("upsert")),
                Map.of("required", List.of("remove_positions"))));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer self,
            Consumer<String> reply) {
        validateRawArgs(args);
        Args parsed = GSON.fromJson(args, Args.class);
        if (parsed.workflow_id() == null || parsed.workflow_id().isBlank()) {
            throw new IllegalArgumentException("workflow_id must not be empty");
        }
        if (parsed.expected_revision() == null || parsed.expected_revision() < 1) {
            throw new IllegalArgumentException("expected_revision must be at least 1");
        }

        List<BuildTaskRecord.Target> upserts = parseUpserts(parsed.upsert());
        List<BlockPos> removals = parseRemovals(parsed.remove_positions());
        if (upserts.isEmpty() && removals.isEmpty()) {
            throw new IllegalArgumentException(
                    "structure patch must upsert or remove at least one cell");
        }

        StructureWorkflowStore.Workflow workflow = StructureWorkflowStore.patch(
                self,
                parsed.workflow_id(),
                parsed.expected_revision(),
                upserts,
                removals);
        StructureWorkflowAssessment assessment =
                StructureWorkflowAssessment.inspect(workflow, self);
        JsonObject result = assessment.render(self, "build");
        result.addProperty("manifest_persisted", true);
        result.addProperty("previous_revision", parsed.expected_revision());
        result.addProperty("upserted_cells", upserts.size());
        result.addProperty("removed_cells", removals.size());
        reply.accept(result.toString());
    }

    static List<BlockPos> parseRemovals(List<PositionSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        if (specs.size() > StructurePlanTool.MAX_CELLS) {
            throw new IllegalArgumentException(
                    "remove_positions accepts at most "
                            + StructurePlanTool.MAX_CELLS + " positions");
        }
        List<BlockPos> positions = new ArrayList<>(specs.size());
        Set<BlockPos> seen = new LinkedHashSet<>();
        for (PositionSpec spec : specs) {
            if (spec == null) {
                throw new IllegalArgumentException("remove positions must not be null");
            }
            if (spec.x() == null || spec.y() == null || spec.z() == null) {
                throw new IllegalArgumentException(
                        "each remove position needs x, y and z");
            }
            BlockPos pos = new BlockPos(spec.x(), spec.y(), spec.z());
            if (!seen.add(pos)) {
                throw new IllegalArgumentException(
                        "duplicate remove position " + pos.toShortString());
            }
            positions.add(pos);
        }
        return List.copyOf(positions);
    }

    private static List<BuildTaskRecord.Target> parseUpserts(
            List<BuildTool.BlockSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        return BuildTool.parseTargets(specs, StructurePlanTool.MAX_CELLS);
    }

    /**
     * MCP schemas are descriptive; the server transport does not enforce them.
     * Validate required coordinates before Gson can turn a missing primitive into
     * zero, and reject metadata that would make a "directly usable" patch invalid.
     */
    static void validateRawArgs(JsonObject args) {
        if (args == null) {
            throw new IllegalArgumentException("structure patch arguments are required");
        }
        rejectUnknownFields(args, ROOT_FIELDS, "structure patch");
        requireString(args, "workflow_id");
        requireWholeLong(args, "expected_revision", 1);
        if (args.has("upsert")) {
            JsonArray upserts = requireArray(args, "upsert");
            if (upserts.size() < 1) {
                throw new IllegalArgumentException(
                        "upsert must contain at least one cell when provided");
            }
            validateBlockSpecs(upserts, "upsert");
        }
        if (args.has("remove_positions")) {
            JsonArray removals = requireArray(args, "remove_positions");
            if (removals.size() < 1) {
                throw new IllegalArgumentException(
                        "remove_positions must contain at least one position when provided");
            }
            validatePositions(removals, "remove_positions");
        }
    }

    static void validateBlockSpecs(JsonArray specs, String field) {
        for (int index = 0; index < specs.size(); index++) {
            JsonObject spec = requireObject(specs.get(index), field + "[" + index + "]");
            rejectUnknownFields(spec, BLOCK_FIELDS, field + "[" + index + "]");
            requireString(spec, "block_id");
            requireWholeInt(spec, "x");
            requireWholeInt(spec, "y");
            requireWholeInt(spec, "z");
        }
    }

    static void validatePositions(JsonArray specs, String field) {
        for (int index = 0; index < specs.size(); index++) {
            JsonObject spec = requireObject(specs.get(index), field + "[" + index + "]");
            rejectUnknownFields(spec, POSITION_FIELDS, field + "[" + index + "]");
            requireWholeInt(spec, "x");
            requireWholeInt(spec, "y");
            requireWholeInt(spec, "z");
        }
    }

    private static JsonArray requireArray(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonObject requireObject(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void requireString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
    }

    private static void requireWholeInt(JsonObject object, String field) {
        long value = requireWholeLong(object, field, Long.MIN_VALUE);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must fit a signed 32-bit integer");
        }
    }

    private static long requireWholeLong(
            JsonObject object, String field, long minimum) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        final long parsed;
        try {
            parsed = primitive.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException(field + " must be an integer", error);
        }
        if (parsed < minimum) {
            throw new IllegalArgumentException(
                    field + " must be at least " + minimum);
        }
        return parsed;
    }

    private static void rejectUnknownFields(
            JsonObject object, Set<String> allowed, String label) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(
                        label + " has unsupported field " + field);
            }
        }
    }
}
