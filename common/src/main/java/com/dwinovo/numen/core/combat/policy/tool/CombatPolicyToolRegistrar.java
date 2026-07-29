package com.dwinovo.numen.core.combat.policy.tool;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.agent.tool.NumenTool;

import java.util.List;

/** Single integration point for the four body-scoped policy management tools. */
public final class CombatPolicyToolRegistrar {

    private static boolean registered;

    private CombatPolicyToolRegistrar() {
    }

    public static synchronized void registerAll() {
        if (registered) {
            return;
        }
        List<NumenTool> tools = List.of(
                new SaveCombatPolicyTool(),
                new CombatPolicyStatusTool(),
                new ActivateCombatPolicyTool(),
                new AbortCombatPolicyTool());
        for (NumenTool tool : tools) {
            NumenTool existing = ToolRegistry.get(tool.name());
            if (existing == null) {
                ToolRegistry.register(tool);
            } else if (!existing.getClass().equals(tool.getClass())) {
                throw new IllegalStateException(
                        "tool name " + tool.name() + " is already owned by "
                                + existing.getClass().getName());
            }
        }
        // Set only after the complete roster is present. If registration is
        // interrupted, a retry accepts already-installed matching classes.
        registered = true;
    }
}
