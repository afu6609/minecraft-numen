package com.dwinovo.numen.core.combat.policy;

/** Read-only runtime/status view of one owned policy. */
public record CombatPolicySnapshot(
        CombatPolicy policy,
        CombatPolicyState state,
        boolean active,
        int consecutiveSuccesses,
        long totalSuccesses,
        long totalFailures,
        long createdAt,
        long updatedAt,
        long revision,
        String lastOutcome) {
}
