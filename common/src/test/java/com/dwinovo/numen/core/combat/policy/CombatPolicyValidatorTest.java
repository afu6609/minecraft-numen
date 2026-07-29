package com.dwinovo.numen.core.combat.policy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatPolicyValidatorTest {

    @Test
    void acceptsOnlyTheBoundedActionVocabulary() {
        List<CombatPolicy.Step> steps = Arrays.stream(CombatPolicyAction.values())
                .map(action -> new CombatPolicy.Step(
                        action,
                        5,
                        new CombatPolicy.Condition(
                                "MELEE_WINDUP", 1.0, 8.0, 0.4)))
                .toList();
        CombatPolicyValidation result =
                CombatPolicyValidator.validate(policy(null, steps));

        assertTrue(result.valid());
        assertEquals(40, result.totalTicks());
    }

    @Test
    void rejectsStepAndCycleBounds() {
        List<CombatPolicy.Step> tooMany = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            tooMany.add(step(1));
        }
        CombatPolicyValidation count =
                CombatPolicyValidator.validate(policy(null, tooMany));
        assertFalse(count.valid());
        assertTrue(count.issues().stream()
                .anyMatch(issue -> issue.code().equals("max_items")));

        CombatPolicyValidation duration =
                CombatPolicyValidator.validate(policy(
                        null, List.of(step(40), step(40), step(40), step(40), step(1))));
        assertFalse(duration.valid());
        assertTrue(duration.issues().stream()
                .anyMatch(issue -> issue.code().equals("total_ticks")));

        CombatPolicyValidation single =
                CombatPolicyValidator.validate(policy(null, List.of(step(41))));
        assertFalse(single.valid());
        assertTrue(single.issues().stream()
                .anyMatch(issue -> issue.path().equals("$.steps[0].ticks")));
    }

    @Test
    void rejectsInvalidConditionAndBinding() {
        CombatPolicy policy = new CombatPolicy(
                null,
                "unsafe",
                new CombatPolicy.Binding("Creeper", "adapter with spaces", ""),
                List.of(new CombatPolicy.Step(
                        CombatPolicyAction.RETREAT,
                        10,
                        new CombatPolicy.Condition(
                                "fusing", 9.0, 2.0, 1.1))));

        CombatPolicyValidation result = CombatPolicyValidator.validate(policy);

        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.path().equals("$.entity_type")));
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.code().equals("distance_order")));
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.path().endsWith("min_health_ratio")));
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.path().endsWith("intent")));
    }

    @Test
    void strictJsonRejectsUnknownCodeOrCommandFields() {
        var withCommand = JsonParser.parseString("""
                {
                  "name":"bad",
                  "entity_type":"minecraft:zombie",
                  "adapter_id":"numen:generic",
                  "schema":"v1",
                  "steps":[{
                    "action":"MELEE_STRIKE",
                    "ticks":4,
                    "when":{},
                    "command":"/kill @e"
                  }]
                }
                """).getAsJsonObject();
        CombatPolicyJson.SyntaxException unknown = assertThrows(
                CombatPolicyJson.SyntaxException.class,
                () -> CombatPolicyJson.parse(withCommand));
        assertEquals("unknown_field",
                unknown.validation().issues().get(0).code());

        var fractionalTicks = JsonParser.parseString("""
                {
                  "name":"bad",
                  "entity_type":"minecraft:zombie",
                  "adapter_id":"numen:generic",
                  "schema":"v1",
                  "steps":[{
                    "action":"HOLD",
                    "ticks":1.5,
                    "when":{}
                  }]
                }
                """).getAsJsonObject();
        assertThrows(
                CombatPolicyJson.SyntaxException.class,
                () -> CombatPolicyJson.parse(fractionalTicks));
    }

    private static CombatPolicy policy(
            String id, List<CombatPolicy.Step> steps) {
        return new CombatPolicy(
                id,
                "creeper spacing",
                new CombatPolicy.Binding(
                        "minecraft:creeper", "numen:creeper", "v1"),
                steps);
    }

    private static CombatPolicy.Step step(int ticks) {
        return new CombatPolicy.Step(
                CombatPolicyAction.HOLD,
                ticks,
                new CombatPolicy.Condition(null, null, null, null));
    }
}
