package com.dwinovo.numen.core.combat.observe;

import java.util.List;

/**
 * A prediction, not an assertion: facts remain authoritative while this value
 * describes the entity's most likely near-term action.
 */
public record CombatIntent(
        CombatAction action,
        CombatPhase phase,
        double confidence,
        Integer predictedEffectMinTicks,
        Integer predictedEffectMaxTicks,
        List<String> evidence) {

    public CombatIntent {
        action = action == null ? CombatAction.UNKNOWN : action;
        phase = phase == null ? CombatPhase.UNKNOWN : phase;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        if (predictedEffectMinTicks != null) {
            predictedEffectMinTicks = Math.max(0, predictedEffectMinTicks);
        }
        if (predictedEffectMaxTicks != null) {
            predictedEffectMaxTicks = Math.max(0, predictedEffectMaxTicks);
        }
        if (predictedEffectMinTicks != null && predictedEffectMaxTicks != null
                && predictedEffectMaxTicks < predictedEffectMinTicks) {
            predictedEffectMaxTicks = predictedEffectMinTicks;
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static CombatIntent idle(String evidence) {
        return new CombatIntent(
                CombatAction.IDLE, CombatPhase.READY, 0.8, null, null, List.of(evidence));
    }
}
