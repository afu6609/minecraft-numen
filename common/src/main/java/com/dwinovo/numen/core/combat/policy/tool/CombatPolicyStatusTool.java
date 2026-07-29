package com.dwinovo.numen.core.combat.policy.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.combat.policy.CombatPolicyRuntime;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Read owned policy state and recent persisted validations. */
public final class CombatPolicyStatusTool implements NumenTool {

    private static final int MAX_RENDERED_VALIDATIONS = 16;

    @Override
    public String name() {
        return "combat_policy_status";
    }

    @Override
    public String description() {
        return "Inspect this companion's saved combat policies, candidate/trusted/disabled state, "
                + "activation, outcome counters, and recent validation records. Optionally filter "
                + "by an owned policy_id. This is read-only.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return CombatPolicyToolSupport.statusSchema();
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer companion,
            Consumer<String> reply) {
        try {
            String policyId = CombatPolicyToolSupport.optionalPolicyId(args);
            var policies = CombatPolicyRuntime.policies(companion, policyId);
            var validations =
                    CombatPolicyRuntime.validationRecords(companion, policyId);
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonArray policyJson = new JsonArray();
            policies.forEach(policy ->
                    policyJson.add(CombatPolicyToolSupport.snapshot(policy)));
            result.add("policies", policyJson);
            result.addProperty("validation_record_count", validations.size());
            JsonArray validationJson = new JsonArray();
            validations.stream()
                    .limit(MAX_RENDERED_VALIDATIONS)
                    .forEach(record -> validationJson.add(
                            CombatPolicyToolSupport.validationRecord(record)));
            result.add("recent_validations", validationJson);
            result.addProperty(
                    "validations_truncated",
                    validations.size() > MAX_RENDERED_VALIDATIONS);
            reply.accept(result.toString());
        } catch (RuntimeException error) {
            reply.accept(CombatPolicyToolSupport.error(error).toString());
        }
    }
}
