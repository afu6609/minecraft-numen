package com.dwinovo.numen.core.combat.policy.tool;

import com.dwinovo.numen.core.combat.policy.CombatPolicy;
import com.dwinovo.numen.core.combat.policy.CombatPolicyAction;
import com.dwinovo.numen.core.combat.policy.CombatPolicySnapshot;
import com.dwinovo.numen.core.combat.policy.CombatPolicyValidation;
import com.dwinovo.numen.core.combat.policy.CombatPolicyValidationRecord;
import com.dwinovo.numen.core.combat.policy.CombatPolicyValidator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class CombatPolicyToolSupport {

    private static final Pattern POLICY_ID =
            Pattern.compile("[A-Za-z0-9_.:/-]{1,96}");

    private CombatPolicyToolSupport() {
    }

    static Map<String, Object> saveSchema() {
        Map<String, Object> conditionProps = new LinkedHashMap<>();
        conditionProps.put("intent", Map.of(
                "type", "string",
                "description", "Optional normalized adapter intent, e.g. PROJECTILE_RELEASE or AOE_CHARGE."));
        conditionProps.put("min_distance", Map.of(
                "type", "number",
                "minimum", 0,
                "maximum", CombatPolicyValidator.MAX_DISTANCE));
        conditionProps.put("max_distance", Map.of(
                "type", "number",
                "minimum", 0,
                "maximum", CombatPolicyValidator.MAX_DISTANCE));
        conditionProps.put("min_health_ratio", Map.of(
                "type", "number",
                "minimum", 0,
                "maximum", 1));
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("type", "object");
        condition.put("properties", conditionProps);
        condition.put("additionalProperties", false);

        Map<String, Object> stepProps = new LinkedHashMap<>();
        stepProps.put("action", Map.of(
                "type", "string",
                "enum", Arrays.stream(CombatPolicyAction.values())
                        .map(Enum::name)
                        .toList()));
        stepProps.put("ticks", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", CombatPolicyValidator.MAX_STEP_TICKS));
        stepProps.put("when", condition);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "object");
        step.put("properties", stepProps);
        step.put("required", List.of("action", "ticks", "when"));
        step.put("additionalProperties", false);

        Map<String, Object> steps = new LinkedHashMap<>();
        steps.put("type", "array");
        steps.put("minItems", 1);
        steps.put("maxItems", CombatPolicyValidator.MAX_STEPS);
        steps.put("items", step);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("policy_id", Map.of(
                "type", "string",
                "description", "Existing owned policy id to revise; omit when creating."));
        props.put("name", Map.of("type", "string", "maxLength", 80));
        props.put("entity_type", Map.of(
                "type", "string",
                "description", "Exact namespaced entity type, e.g. minecraft:creeper."));
        props.put("adapter_id", Map.of(
                "type", "string",
                "description", "Exact combat observation adapter id."));
        props.put("schema", Map.of(
                "type", "string",
                "description", "Exact adapter policy schema/version binding."));
        props.put("steps", steps);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of(
                "name", "entity_type", "adapter_id", "schema", "steps"));
        root.put("additionalProperties", false);
        return root;
    }

    static Map<String, Object> statusSchema() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of(
                "policy_id", Map.of(
                        "type", "string",
                        "description", "Optional owned policy id; omit to list all.")));
        root.put("additionalProperties", false);
        return root;
    }

    static Map<String, Object> idSchema() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of(
                "policy_id", Map.of("type", "string")));
        root.put("required", List.of("policy_id"));
        root.put("additionalProperties", false);
        return root;
    }

    static String optionalPolicyId(JsonObject args) {
        rejectUnknown(args, Set.of("policy_id"));
        if (!args.has("policy_id")) {
            return null;
        }
        return policyId(args.get("policy_id"));
    }

    static String requiredPolicyId(JsonObject args) {
        String id = optionalPolicyId(args);
        if (id == null) {
            throw new IllegalArgumentException("policy_id is required");
        }
        return id;
    }

    static JsonObject success(CombatPolicySnapshot snapshot) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("policy", snapshot(snapshot));
        return result;
    }

    static JsonObject rejected(CombatPolicyValidation validation) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", "combat_policy_validation_failed");
        result.add("validation", validation(validation));
        return result;
    }

    static JsonObject error(RuntimeException error) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        String message = error.getMessage();
        result.addProperty("error",
                message == null || message.isBlank()
                        ? error.getClass().getSimpleName()
                        : message);
        return result;
    }

    static JsonObject snapshot(CombatPolicySnapshot snapshot) {
        CombatPolicy policy = snapshot.policy();
        JsonObject result = new JsonObject();
        result.addProperty("policy_id", policy.id());
        result.addProperty("name", policy.name());
        result.addProperty("entity_type", policy.binding().entityType());
        result.addProperty("adapter_id", policy.binding().adapterId());
        result.addProperty("schema", policy.binding().schema());
        result.addProperty("state",
                snapshot.state().name().toLowerCase(Locale.ROOT));
        result.addProperty("active", snapshot.active());
        result.addProperty(
                "consecutive_successes", snapshot.consecutiveSuccesses());
        result.addProperty(
                "successes_until_trusted",
                Math.max(0, 3 - snapshot.consecutiveSuccesses()));
        result.addProperty("total_successes", snapshot.totalSuccesses());
        result.addProperty("total_failures", snapshot.totalFailures());
        result.addProperty("created_at", snapshot.createdAt());
        result.addProperty("updated_at", snapshot.updatedAt());
        result.addProperty("revision", snapshot.revision());
        if (snapshot.lastOutcome() != null) {
            result.addProperty("last_outcome", snapshot.lastOutcome());
        }
        JsonArray steps = new JsonArray();
        for (CombatPolicy.Step step : policy.steps()) {
            JsonObject item = new JsonObject();
            item.addProperty("action", step.action().name());
            item.addProperty("ticks", step.ticks());
            JsonObject when = new JsonObject();
            if (step.when().intent() != null) {
                when.addProperty("intent", step.when().intent());
            }
            if (step.when().minDistance() != null) {
                when.addProperty("min_distance", step.when().minDistance());
            }
            if (step.when().maxDistance() != null) {
                when.addProperty("max_distance", step.when().maxDistance());
            }
            if (step.when().minHealthRatio() != null) {
                when.addProperty(
                        "min_health_ratio", step.when().minHealthRatio());
            }
            item.add("when", when);
            steps.add(item);
        }
        result.add("steps", steps);
        return result;
    }

    static JsonObject validation(CombatPolicyValidation validation) {
        JsonObject result = new JsonObject();
        result.addProperty("valid", validation.valid());
        result.addProperty("total_ticks", validation.totalTicks());
        result.add("issues", issues(validation.issues()));
        return result;
    }

    static JsonObject validationRecord(
            CombatPolicyValidationRecord record) {
        JsonObject result = new JsonObject();
        result.addProperty("policy_id", record.policyId());
        result.addProperty("timestamp", record.timestamp());
        result.addProperty("valid", record.valid());
        result.addProperty("total_ticks", record.totalTicks());
        result.add("issues", issues(record.issues()));
        return result;
    }

    private static JsonArray issues(
            List<CombatPolicyValidation.Issue> issues) {
        JsonArray result = new JsonArray();
        for (CombatPolicyValidation.Issue issue : issues) {
            JsonObject item = new JsonObject();
            item.addProperty("path", issue.path());
            item.addProperty("code", issue.code());
            item.addProperty("message", issue.message());
            result.add(item);
        }
        return result;
    }

    private static String policyId(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("policy_id must be a string");
        }
        String id = element.getAsString();
        if (!POLICY_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "policy_id has an invalid format");
        }
        return id;
    }

    private static void rejectUnknown(
            JsonObject args, Set<String> allowed) {
        if (args == null) {
            throw new IllegalArgumentException("arguments must be an object");
        }
        for (String key : args.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                        "unknown argument " + key);
            }
        }
    }
}
