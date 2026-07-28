package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Reconcile a saved structure workflow against the live loaded world. */
public final class StructureStatusTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String workflow_id, String operation) {}

    @Override
    public String name() {
        return "structure_status";
    }

    @Override
    public String description() {
        return "Inspect live progress for a persistent structure workflow. It recomputes blueprint "
                + "matches, conflicts, loaded cells, carried materials and exact shortfalls; it does "
                + "not rely on a task merely claiming success. Pass the workflow_id returned by "
                + "structure_plan, or omit/use latest for this companion's most recently used "
                + "workflow. Use operation=build while constructing and operation=demolish when "
                + "undoing that exact saved structure.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("workflow_id", Map.of(
                "type", "string",
                "description", "Optional workflow id; omit or use latest for the most recent one."));
        props.put("operation", Map.of(
                "type", "string",
                "enum", List.of("build", "demolish"),
                "description", "Status perspective; default build."));
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
        String operation = operation(parsed.operation());
        StructureWorkflowStore.Workflow workflow =
                StructureWorkflowStore.resolve(self, parsed.workflow_id());
        StructureWorkflowAssessment assessment =
                StructureWorkflowAssessment.inspect(workflow, self);
        reply.accept(assessment.render(self, operation).toString());
    }

    static String operation(String value) {
        if (value == null || value.isBlank()) {
            return "build";
        }
        String normalized = value.trim().toLowerCase();
        if (!normalized.equals("build") && !normalized.equals("demolish")) {
            throw new IllegalArgumentException(
                    "operation must be build or demolish");
        }
        return normalized;
    }
}
