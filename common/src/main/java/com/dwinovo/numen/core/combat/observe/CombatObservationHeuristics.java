package com.dwinovo.numen.core.combat.observe;

import java.util.List;

/** Pure conservative fallbacks used when vanilla exposes no attack telegraph. */
final class CombatObservationHeuristics {

    private CombatObservationHeuristics() {
    }

    static CombatIntent nearbyTarget(
            double closingSpeed, List<String> evidence) {
        if (closingSpeed > 0.04) {
            return new CombatIntent(
                    CombatAction.APPROACH,
                    CombatPhase.EXECUTING,
                    0.58,
                    null,
                    null,
                    evidence);
        }
        return new CombatIntent(
                CombatAction.UNKNOWN,
                CombatPhase.OBSERVED,
                0.35,
                null,
                null,
                evidence);
    }
}
