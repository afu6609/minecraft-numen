package com.dwinovo.numen.core.combat.policy;

import java.util.List;

/** One persisted validation attempt, scoped to its companion owner. */
public record CombatPolicyValidationRecord(
        String policyId,
        long timestamp,
        boolean valid,
        int totalTicks,
        List<CombatPolicyValidation.Issue> issues) {

    public CombatPolicyValidationRecord {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
