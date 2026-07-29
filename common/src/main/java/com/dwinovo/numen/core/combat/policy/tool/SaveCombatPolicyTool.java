package com.dwinovo.numen.core.combat.policy.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.combat.policy.CombatPolicy;
import com.dwinovo.numen.core.combat.policy.CombatPolicyJson;
import com.dwinovo.numen.core.combat.policy.CombatPolicyRepository;
import com.dwinovo.numen.core.combat.policy.CombatPolicyRuntime;
import com.dwinovo.numen.core.combat.policy.CombatPolicyValidation;
import com.dwinovo.numen.core.combat.policy.CombatPolicyValidator;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Save a validated declaration; never executes the policy. */
public final class SaveCombatPolicyTool implements NumenTool {

    @Override
    public String name() {
        return "save_combat_policy";
    }

    @Override
    public String description() {
        return "Validate and persist a bounded combat policy for this companion. "
                + "This manages declarations only: it cannot run code, commands, tools, or arbitrary "
                + "server operations. A policy is bound exactly to entity_type + adapter_id + schema, "
                + "contains at most 12 fixed-vocabulary steps, and starts as an inactive candidate. "
                + "Use activate_combat_policy after reviewing the returned validation.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return CombatPolicyToolSupport.saveSchema();
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer companion,
            Consumer<String> reply) {
        try {
            CombatPolicy policy;
            try {
                policy = CombatPolicyJson.parse(args);
            } catch (CombatPolicyJson.SyntaxException error) {
                CombatPolicyRuntime.recordValidation(
                        companion,
                        CombatPolicyJson.requestedPolicyId(args),
                        error.validation());
                reply.accept(
                        CombatPolicyToolSupport.rejected(
                                error.validation()).toString());
                return;
            }

            CombatPolicyValidation validation =
                    CombatPolicyValidator.validate(policy);
            if (!validation.valid()) {
                CombatPolicyRuntime.recordValidation(
                        companion, policy.id(), validation);
                reply.accept(
                        CombatPolicyToolSupport.rejected(validation).toString());
                return;
            }
            var snapshot = CombatPolicyRuntime.save(companion, policy);
            JsonObject result = CombatPolicyToolSupport.success(snapshot);
            result.add("validation",
                    CombatPolicyToolSupport.validation(validation));
            reply.accept(result.toString());
        } catch (CombatPolicyRepository.PolicyRejectedException error) {
            reply.accept(
                    CombatPolicyToolSupport.rejected(
                            error.validation()).toString());
        } catch (RuntimeException error) {
            reply.accept(CombatPolicyToolSupport.error(error).toString());
        }
    }
}
