package com.dwinovo.numen.core.combat.policy;

import java.util.List;

/** Stable, serializable validation result retained alongside saved policies. */
public record CombatPolicyValidation(
        boolean valid,
        int totalTicks,
        List<Issue> issues) {

    public CombatPolicyValidation {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static CombatPolicyValidation invalid(
            String path, String code, String message) {
        return new CombatPolicyValidation(
                false, 0, List.of(new Issue(path, code, message)));
    }

    public record Issue(String path, String code, String message) {
    }
}
