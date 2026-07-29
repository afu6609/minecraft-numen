package com.dwinovo.numen.core.combat.observe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatTraceTest {

    @Test
    void keepsOnlySignificantChangesAndBoundsHistory() {
        CombatTrace trace = new CombatTrace(2);

        assertTrue(trace.append(observation(1, 8.0, 20.0F, CombatAction.APPROACH)));
        assertFalse(trace.append(observation(2, 8.4, 20.0F, CombatAction.APPROACH)));
        assertTrue(trace.append(observation(3, 5.9, 20.0F, CombatAction.APPROACH)));
        assertTrue(trace.append(observation(4, 5.8, 20.0F, CombatAction.MELEE_WINDUP)));

        assertEquals(2, trace.events().size());
        assertEquals(3, trace.events().get(0).facts().serverTick());
        assertEquals(CombatAction.MELEE_WINDUP,
                trace.events().get(1).intent().action());
    }

    @Test
    void recordsHeartbeatForPersistentState() {
        CombatTrace trace = new CombatTrace(4);
        assertTrue(trace.append(observation(1, 10.0, 20.0F, CombatAction.IDLE)));
        assertFalse(trace.append(observation(99, 10.0, 20.0F, CombatAction.IDLE)));
        assertTrue(trace.append(observation(101, 10.0, 20.0F, CombatAction.IDLE)));
    }

    private static CombatObservation observation(
            long tick, double distance, float health, CombatAction action) {
        CombatFacts facts = new CombatFacts(
                tick, 7, "uuid", "minecraft:zombie", "Zombie",
                new CombatFacts.Vector(0, 0, 0),
                new CombatFacts.Vector(0, 0, 0),
                health, 20.0F, distance, true, true, false, null,
                42, "momo", true, true, false, false, Map.of());
        CombatIntent intent = new CombatIntent(
                action,
                action == CombatAction.MELEE_WINDUP
                        ? CombatPhase.WINDUP : CombatPhase.EXECUTING,
                0.8, null, null, List.of("test"));
        return new CombatObservation(
                facts, intent, "test", CombatObservationService.POLICY_SCHEMA);
    }
}
