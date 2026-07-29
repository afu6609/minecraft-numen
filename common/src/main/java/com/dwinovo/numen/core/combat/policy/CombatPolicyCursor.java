package com.dwinovo.numen.core.combat.policy;

import java.util.List;
import java.util.Optional;

/**
 * Pure bounded per-tick evaluator for one validated policy.
 *
 * <p>A matching step emits for its declared number of ticks. Conditions are
 * checked again every tick; if the active condition no longer matches, the
 * cursor skips it immediately and examines at most one complete policy cycle.
 * It never spins waiting for a condition and never touches Minecraft state.
 * The cursor intentionally cycles; its executor must impose a separate bounded
 * combat-episode deadline and safety-preemption policy.</p>
 */
public final class CombatPolicyCursor {

    private final List<CombatPolicy.Step> steps;
    private int nextIndex;
    private int ticksRemaining;

    public CombatPolicyCursor(CombatPolicy policy) {
        CombatPolicyValidation validation =
                CombatPolicyValidator.validate(policy);
        if (!validation.valid()) {
            throw new IllegalArgumentException(
                    "combat policy cursor requires a valid policy");
        }
        this.steps = List.copyOf(policy.steps());
    }

    /**
     * Evaluate one tick. Distance and health comparisons are inclusive.
     *
     * @return the selected declaration and the number of future ticks left in
     *         this step after the current emission, or empty when no condition
     *         matches this tick
     */
    public Optional<Selection> tick(
            String intent, double distance, double healthRatio) {
        requireInput(distance, healthRatio);

        int examined = 0;
        if (ticksRemaining > 0) {
            CombatPolicy.Step active = steps.get(nextIndex);
            if (matches(active.when(), intent, distance, healthRatio)) {
                return Optional.of(emit(nextIndex, active));
            }
            ticksRemaining = 0;
            advance();
            examined = 1;
        }

        while (examined < steps.size()) {
            int selectedIndex = nextIndex;
            CombatPolicy.Step candidate = steps.get(selectedIndex);
            if (matches(candidate.when(), intent, distance, healthRatio)) {
                ticksRemaining = candidate.ticks();
                return Optional.of(emit(selectedIndex, candidate));
            }
            advance();
            examined++;
        }
        return Optional.empty();
    }

    /** Return the cursor to the first declared step with no active countdown. */
    public void reset() {
        nextIndex = 0;
        ticksRemaining = 0;
    }

    /** Pure condition predicate shared with server-side execution adapters. */
    public static boolean matches(
            CombatPolicy.Condition condition,
            String intent,
            double distance,
            double healthRatio) {
        if (condition == null) {
            return false;
        }
        if (condition.intent() != null
                && !condition.intent().equals(intent)) {
            return false;
        }
        if (condition.minDistance() != null
                && distance < condition.minDistance()) {
            return false;
        }
        if (condition.maxDistance() != null
                && distance > condition.maxDistance()) {
            return false;
        }
        return condition.minHealthRatio() == null
                || healthRatio >= condition.minHealthRatio();
    }

    private Selection emit(int selectedIndex, CombatPolicy.Step step) {
        ticksRemaining--;
        int futureTicks = ticksRemaining;
        if (ticksRemaining == 0) {
            advance();
        }
        return new Selection(selectedIndex, step, futureTicks);
    }

    private void advance() {
        nextIndex = (nextIndex + 1) % steps.size();
    }

    private static void requireInput(double distance, double healthRatio) {
        if (!Double.isFinite(distance) || distance < 0.0) {
            throw new IllegalArgumentException(
                    "distance must be finite and non-negative");
        }
        if (!Double.isFinite(healthRatio)
                || healthRatio < 0.0 || healthRatio > 1.0) {
            throw new IllegalArgumentException(
                    "healthRatio must be finite and between 0 and 1");
        }
    }

    public record Selection(
            int stepIndex,
            CombatPolicy.Step step,
            int ticksRemaining) {

        public CombatPolicyAction action() {
            return step.action();
        }
    }
}
