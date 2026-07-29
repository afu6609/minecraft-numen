package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.combat.policy.CombatPolicyRuntime;
import com.dwinovo.numen.entity.NumenPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight death bridge for policy episodes.
 *
 * <p>The chain normally persists one outcome at its quiet-window boundary. A
 * body death destroys that chain first, so the lifecycle hook uses this bounded
 * UUID-to-policy marker to record the otherwise-lost failure exactly once.</p>
 */
public final class CombatPolicyExecutionTracker {

    private static final ConcurrentHashMap<UUID, String> ACTIVE =
            new ConcurrentHashMap<>();

    private CombatPolicyExecutionTracker() {}

    public static void mark(NumenPlayer companion, String policyId) {
        if (companion == null || policyId == null || policyId.isBlank()) return;
        ACTIVE.put(companion.getUUID(), policyId);
    }

    public static void clear(NumenPlayer companion) {
        if (companion != null) ACTIVE.remove(companion.getUUID());
    }

    public static void recordDeath(NumenPlayer companion) {
        if (companion == null) return;
        String policyId = ACTIVE.remove(companion.getUUID());
        if (policyId == null) return;
        try {
            CombatPolicyRuntime.recordExecutionOutcome(
                    companion,
                    policyId,
                    false,
                    "body died during the supervised policy episode");
        } catch (RuntimeException ignored) {
            // Lifecycle cleanup and the death event must still complete if a
            // world is already unloading or the policy was manually disabled.
        }
    }
}
