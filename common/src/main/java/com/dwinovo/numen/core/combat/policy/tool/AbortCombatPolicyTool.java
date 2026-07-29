package com.dwinovo.numen.core.combat.policy.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.combat.policy.CombatPolicyRuntime;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Disable one owned policy so the executor can no longer select it. */
public final class AbortCombatPolicyTool implements NumenTool {

    @Override
    public String name() {
        return "abort_combat_policy";
    }

    @Override
    public String description() {
        return "Immediately deactivate and disable one combat policy owned by this companion. "
                + "The trusted runtime will no longer return it as active. It can only be reused "
                + "after an explicit activate_combat_policy call, which resets it to candidate.";
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
                    CombatPolicyRuntime.abort(
                            companion, policyId)).toString());
        } catch (RuntimeException error) {
            reply.accept(CombatPolicyToolSupport.error(error).toString());
        }
    }
}
