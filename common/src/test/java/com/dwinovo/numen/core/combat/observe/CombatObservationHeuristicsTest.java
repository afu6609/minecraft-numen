package com.dwinovo.numen.core.combat.observe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatObservationHeuristicsTest {

    @Test
    void nearbyClosingMobIsApproachNotInventedMeleeWindup() {
        CombatIntent intent = CombatObservationHeuristics.nearbyTarget(
                0.2, List.of("target is nearby"));

        assertEquals(CombatAction.APPROACH, intent.action());
        assertEquals(CombatPhase.EXECUTING, intent.phase());
        assertTrue(intent.confidence() < 0.6);
    }

    @Test
    void nearbyStationaryMobStaysUnknownWithoutAttackEvidence() {
        CombatIntent intent = CombatObservationHeuristics.nearbyTarget(
                0.0, List.of("target is nearby"));

        assertEquals(CombatAction.UNKNOWN, intent.action());
        assertEquals(CombatPhase.OBSERVED, intent.phase());
        assertTrue(intent.confidence() < 0.5);
    }
}
