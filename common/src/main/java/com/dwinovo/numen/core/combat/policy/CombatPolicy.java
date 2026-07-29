package com.dwinovo.numen.core.combat.policy;

import java.util.List;

/**
 * Immutable-shaped declaration authored by the brain and executed only by a
 * trusted server runtime.
 *
 * <p>The condition is a gate for its step. Null condition members are
 * wildcards; the condition object itself is required. Intent names remain an
 * adapter vocabulary so mod adapters can introduce normalized telegraphs
 * without widening the executable action set.</p>
 */
public record CombatPolicy(
        String id,
        String name,
        Binding binding,
        List<Step> steps) {

    public record Binding(
            String entityType,
            String adapterId,
            String schema) {
    }

    public record Step(
            CombatPolicyAction action,
            int ticks,
            Condition when) {
    }

    public record Condition(
            String intent,
            Double minDistance,
            Double maxDistance,
            Double minHealthRatio) {
    }
}
