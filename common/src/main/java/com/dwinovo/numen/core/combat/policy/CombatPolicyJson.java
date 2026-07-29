package com.dwinovo.numen.core.combat.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Strict JSON decoder for the model-facing policy DSL. */
public final class CombatPolicyJson {

    private static final Set<String> ROOT_KEYS = Set.of(
            "policy_id", "name", "entity_type", "adapter_id", "schema", "steps");
    private static final Set<String> STEP_KEYS = Set.of("action", "ticks", "when");
    private static final Set<String> CONDITION_KEYS = Set.of(
            "intent", "min_distance", "max_distance", "min_health_ratio");

    private CombatPolicyJson() {
    }

    public static CombatPolicy parse(JsonObject json) {
        if (json == null) {
            throw syntax("$", "type", "policy must be a JSON object");
        }
        rejectUnknown(json, ROOT_KEYS, "$");
        String id = optionalString(json, "policy_id", "$.policy_id");
        String name = optionalString(json, "name", "$.name");
        String entityType = optionalString(json, "entity_type", "$.entity_type");
        String adapterId = optionalString(json, "adapter_id", "$.adapter_id");
        String schema = optionalString(json, "schema", "$.schema");

        List<CombatPolicy.Step> steps = null;
        if (json.has("steps")) {
            JsonElement element = json.get("steps");
            if (!element.isJsonArray()) {
                throw syntax("$.steps", "type", "steps must be an array");
            }
            steps = parseSteps(element.getAsJsonArray());
        }
        return new CombatPolicy(
                id,
                name,
                new CombatPolicy.Binding(entityType, adapterId, schema),
                steps);
    }

    /**
     * Best-effort identifier used only to associate a rejected parse attempt
     * with a validation record. It never authorizes lookup or mutation.
     */
    public static String requestedPolicyId(JsonObject json) {
        if (json == null || !json.has("policy_id")) {
            return null;
        }
        JsonElement value = json.get("policy_id");
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static List<CombatPolicy.Step> parseSteps(JsonArray array) {
        List<CombatPolicy.Step> result = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            String path = "$.steps[" + index + "]";
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) {
                throw syntax(path, "type", "step must be an object");
            }
            JsonObject object = element.getAsJsonObject();
            rejectUnknown(object, STEP_KEYS, path);

            CombatPolicyAction action = null;
            String actionText = optionalString(object, "action", path + ".action");
            if (actionText != null) {
                try {
                    action = CombatPolicyAction.valueOf(actionText);
                } catch (IllegalArgumentException ignored) {
                    String allowed = Arrays.stream(CombatPolicyAction.values())
                            .map(Enum::name)
                            .collect(Collectors.joining(", "));
                    throw syntax(path + ".action", "enum",
                            "action must be one of: " + allowed);
                }
            }
            int ticks = object.has("ticks")
                    ? requiredInteger(object.get("ticks"), path + ".ticks")
                    : 0;
            CombatPolicy.Condition condition = null;
            if (object.has("when")) {
                JsonElement when = object.get("when");
                if (!when.isJsonObject()) {
                    throw syntax(path + ".when", "type",
                            "when must be an object");
                }
                condition = parseCondition(when.getAsJsonObject(), path + ".when");
            }
            result.add(new CombatPolicy.Step(action, ticks, condition));
        }
        return result;
    }

    private static CombatPolicy.Condition parseCondition(
            JsonObject object, String path) {
        rejectUnknown(object, CONDITION_KEYS, path);
        return new CombatPolicy.Condition(
                optionalString(object, "intent", path + ".intent"),
                optionalNumber(object, "min_distance", path + ".min_distance"),
                optionalNumber(object, "max_distance", path + ".max_distance"),
                optionalNumber(object, "min_health_ratio",
                        path + ".min_health_ratio"));
    }

    private static String optionalString(
            JsonObject object, String key, String path) {
        if (!object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw syntax(path, "type", "value must be a string");
        }
        return element.getAsString();
    }

    private static Double optionalNumber(
            JsonObject object, String key, String path) {
        if (!object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw syntax(path, "type", "value must be a number");
        }
        return element.getAsDouble();
    }

    private static int requiredInteger(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive()) {
            throw syntax(path, "type", "value must be an integer");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw syntax(path, "type", "value must be an integer");
        }
        try {
            BigDecimal value = primitive.getAsBigDecimal().stripTrailingZeros();
            if (value.scale() > 0) {
                throw syntax(path, "type", "value must be an integer");
            }
            return value.intValueExact();
        } catch (ArithmeticException error) {
            throw syntax(path, "range", "integer is outside the supported range");
        }
    }

    private static void rejectUnknown(
            JsonObject object, Set<String> allowed, String path) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw syntax(path + "." + key, "unknown_field",
                        "field is not part of the combat policy DSL");
            }
        }
    }

    private static SyntaxException syntax(
            String path, String code, String message) {
        return new SyntaxException(
                CombatPolicyValidation.invalid(path, code, message));
    }

    public static final class SyntaxException extends IllegalArgumentException {
        private final CombatPolicyValidation validation;

        private SyntaxException(CombatPolicyValidation validation) {
            super(validation.issues().get(0).message());
            this.validation = validation;
        }

        public CombatPolicyValidation validation() {
            return validation;
        }
    }
}
