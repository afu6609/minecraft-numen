package com.dwinovo.numen.core.combat.observe;

/** One fact snapshot paired with the adapter's normalized prediction. */
public record CombatObservation(
        CombatFacts facts,
        CombatIntent intent,
        String adapter,
        String schema) {
}
