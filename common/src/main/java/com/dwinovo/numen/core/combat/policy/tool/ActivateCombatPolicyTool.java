package com.dwinovo.numen.core.combat.policy.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.combat.policy.CombatPolicyRuntime;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Activate one owned declaration for its exact binding. */
public final class ActivateCombatPolicyTool implements NumenTool {

    @Override
    public String name() {
        return "activate_combat_policy";
    }

    @Override
    public String description() {
        return "Activate one validated policy owned by this companion. Only one policy can be active "
                + "for the same entity_type + adapter_id + schema binding. Re-enabling a disabled "
                + "policy returns it to candidate state. This does not directly execute an action.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return CombatPolicyToolSupport.idSchema();
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer companion,
            Consumer<String> reply) {
        try {
            String policyId = CombatPolicyToolSupport.requiredPolicyId(args);
            reply.accept(CombatPolicyToolSupport.success(
                    CombatPolicyRuntime.activate(
                            companion, policyId)).toString());
        } catch (RuntimeException error) {
            reply.accept(CombatPolicyToolSupport.error(error).toString());
        }
    }
}
