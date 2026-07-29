package com.dwinovo.numen.core.combat.observe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CombatIntentTest {

    @Test
    void normalizesPredictionBoundsAndCopiesEvidence() {
        List<String> evidence = new ArrayList<>(List.of("windup"));
        CombatIntent intent = new CombatIntent(
                CombatAction.AOE_CHARGE, CombatPhase.WINDUP, 1.4, -2, -8, evidence);
        evidence.add("mutated");

        assertEquals(1.0, intent.confidence());
        assertEquals(0, intent.predictedEffectMinTicks());
        assertEquals(0, intent.predictedEffectMaxTicks());
        assertEquals(List.of("windup"), intent.evidence());
        assertThrows(UnsupportedOperationException.class, () -> intent.evidence().add("x"));
    }
}
