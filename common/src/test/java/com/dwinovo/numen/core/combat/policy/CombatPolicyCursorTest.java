package com.dwinovo.numen.core.combat.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CombatPolicyCursorTest {

    @Test
    void skipsUnmatchedStepsCountsDownAndLoops() {
        CombatPolicyCursor cursor = new CombatPolicyCursor(policy(List.of(
                step(CombatPolicyAction.GUARD, 2,
                        new CombatPolicy.Condition(
                                "RANGED_CHARGE", 2.0, 12.0, 0.4)),
                step(CombatPolicyAction.RETREAT, 1,
                        new CombatPolicy.Condition(
                                "AOE_CHARGE", null, 5.0, null)))));

        var first = cursor.tick("RANGED_CHARGE", 6.0, 0.8).orElseThrow();
        assertEquals(CombatPolicyAction.GUARD, first.action());
        assertEquals(1, first.ticksRemaining());

        var second = cursor.tick("RANGED_CHARGE", 6.0, 0.8).orElseThrow();
        assertEquals(CombatPolicyAction.GUARD, second.action());
        assertEquals(0, second.ticksRemaining());

        var third = cursor.tick("AOE_CHARGE", 3.0, 0.8).orElseThrow();
        assertEquals(CombatPolicyAction.RETREAT, third.action());
        assertEquals(0, third.ticksRemaining());

        var looped = cursor.tick("RANGED_CHARGE", 6.0, 0.8).orElseThrow();
        assertEquals(CombatPolicyAction.GUARD, looped.action());
    }

    @Test
    void activeStepIsInterruptedWhenItsConditionChanges() {
        CombatPolicyCursor cursor = new CombatPolicyCursor(policy(List.of(
                step(CombatPolicyAction.APPROACH, 20,
                        new CombatPolicy.Condition(
                                "READY", 5.0, 20.0, 0.5)),
                step(CombatPolicyAction.SEEK_SHELTER, 4,
                        new CombatPolicy.Condition(
                                "MELEE_WINDUP", null, 4.0, null)))));

        assertEquals(
                CombatPolicyAction.APPROACH,
                cursor.tick("READY", 10.0, 1.0).orElseThrow().action());
        var interrupted =
                cursor.tick("MELEE_WINDUP", 3.0, 1.0).orElseThrow();
        assertEquals(CombatPolicyAction.SEEK_SHELTER, interrupted.action());
        assertEquals(3, interrupted.ticksRemaining());
    }

    @Test
    void returnsEmptyAfterOneBoundedCycleWhenNothingMatches() {
        CombatPolicyCursor cursor = new CombatPolicyCursor(policy(List.of(
                step(CombatPolicyAction.MELEE_STRIKE, 2,
                        new CombatPolicy.Condition(
                                "RECOVER", null, 2.0, 0.9)),
                step(CombatPolicyAction.RANGED_SHOT, 2,
                        new CombatPolicy.Condition(
                                "IDLE", 8.0, 16.0, 0.9)))));

        assertFalse(cursor.tick("AOE_CHARGE", 4.0, 0.2).isPresent());
        assertFalse(cursor.tick("AOE_CHARGE", 4.0, 0.2).isPresent());
    }

    @Test
    void resetReturnsToFirstStep() {
        CombatPolicyCursor cursor = new CombatPolicyCursor(policy(List.of(
                step(CombatPolicyAction.HOLD, 1,
                        new CombatPolicy.Condition(null, null, null, null)),
                step(CombatPolicyAction.GUARD, 1,
                        new CombatPolicy.Condition(null, null, null, null)))));

        assertEquals(
                CombatPolicyAction.HOLD,
                cursor.tick(null, 0.0, 1.0).orElseThrow().action());
        assertEquals(
                CombatPolicyAction.GUARD,
                cursor.tick(null, 0.0, 1.0).orElseThrow().action());
        cursor.reset();
        assertEquals(
                CombatPolicyAction.HOLD,
                cursor.tick(null, 0.0, 1.0).orElseThrow().action());
    }

    @Test
    void validatesRuntimeInputs() {
        CombatPolicyCursor cursor = new CombatPolicyCursor(policy(List.of(
                step(CombatPolicyAction.HOLD, 1,
                        new CombatPolicy.Condition(null, null, null, null)))));

        assertThrows(
                IllegalArgumentException.class,
                () -> cursor.tick(null, Double.NaN, 1.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> cursor.tick(null, 1.0, 1.1));
    }

    private static CombatPolicy policy(List<CombatPolicy.Step> steps) {
        return new CombatPolicy(
                "combat-test",
                "cursor test",
                new CombatPolicy.Binding(
                        "minecraft:creeper",
                        "numen:creeper",
                        "combat_observation_v1"),
                steps);
    }

    private static CombatPolicy.Step step(
            CombatPolicyAction action,
            int ticks,
            CombatPolicy.Condition condition) {
        return new CombatPolicy.Step(action, ticks, condition);
    }
}
