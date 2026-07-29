package com.dwinovo.numen.core.combat.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pure strict validator for the bounded policy declaration. */
public final class CombatPolicyValidator {

    public static final int MAX_STEPS = 12;
    public static final int MAX_STEP_TICKS = 40;
    public static final int MAX_TOTAL_TICKS = 160;
    public static final double MAX_DISTANCE = 64.0;

    private static final Pattern ENTITY_TYPE = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern BINDING_TOKEN = Pattern.compile(
            "[A-Za-z0-9_.:/-]+");
    private static final Pattern INTENT = Pattern.compile(
            "[A-Z][A-Z0-9_]{0,63}");

    private CombatPolicyValidator() {
    }

    public static CombatPolicyValidation validate(CombatPolicy policy) {
        List<CombatPolicyValidation.Issue> issues = new ArrayList<>();
        if (policy == null) {
            issues.add(issue("$", "required", "policy is required"));
            return new CombatPolicyValidation(false, 0, issues);
        }

        optionalId(policy.id(), "$.policy_id", issues);
        boundedText(policy.name(), "$.name", 1, 80, issues);
        validateBinding(policy.binding(), issues);

        List<CombatPolicy.Step> steps = policy.steps();
        int totalTicks = 0;
        if (steps == null) {
            issues.add(issue("$.steps", "required", "steps are required"));
        } else {
            if (steps.isEmpty()) {
                issues.add(issue("$.steps", "min_items", "at least one step is required"));
            }
            if (steps.size() > MAX_STEPS) {
                issues.add(issue("$.steps", "max_items",
                        "a policy may contain at most " + MAX_STEPS + " steps"));
            }
            for (int index = 0; index < steps.size(); index++) {
                CombatPolicy.Step step = steps.get(index);
                String path = "$.steps[" + index + "]";
                if (step == null) {
                    issues.add(issue(path, "required", "step is required"));
                    continue;
                }
                if (step.action() == null) {
                    issues.add(issue(path + ".action", "required", "action is required"));
                }
                if (step.ticks() < 1 || step.ticks() > MAX_STEP_TICKS) {
                    issues.add(issue(path + ".ticks", "range",
                            "ticks must be between 1 and " + MAX_STEP_TICKS));
                } else {
                    totalTicks += step.ticks();
                }
                validateCondition(step.when(), path + ".when", issues);
            }
        }
        if (totalTicks > MAX_TOTAL_TICKS) {
            issues.add(issue("$.steps", "total_ticks",
                    "policy cycle exceeds " + MAX_TOTAL_TICKS + " ticks"));
        }
        return new CombatPolicyValidation(issues.isEmpty(), totalTicks, issues);
    }

    private static void validateBinding(
            CombatPolicy.Binding binding,
            List<CombatPolicyValidation.Issue> issues) {
        if (binding == null) {
            issues.add(issue("$.binding", "required", "binding is required"));
            return;
        }
        token(binding.entityType(), "$.entity_type", ENTITY_TYPE, 128, issues);
        token(binding.adapterId(), "$.adapter_id", BINDING_TOKEN, 96, issues);
        token(binding.schema(), "$.schema", BINDING_TOKEN, 96, issues);
    }

    private static void validateCondition(
            CombatPolicy.Condition condition,
            String path,
            List<CombatPolicyValidation.Issue> issues) {
        if (condition == null) {
            issues.add(issue(path, "required", "condition object is required"));
            return;
        }
        if (condition.intent() != null
                && (!INTENT.matcher(condition.intent()).matches())) {
            issues.add(issue(path + ".intent", "format",
                    "intent must be an uppercase normalized token"));
        }
        finiteRange(condition.minDistance(), path + ".min_distance",
                0.0, MAX_DISTANCE, issues);
        finiteRange(condition.maxDistance(), path + ".max_distance",
                0.0, MAX_DISTANCE, issues);
        if (condition.minDistance() != null && condition.maxDistance() != null
                && Double.isFinite(condition.minDistance())
                && Double.isFinite(condition.maxDistance())
                && condition.minDistance() > condition.maxDistance()) {
            issues.add(issue(path, "distance_order",
                    "min_distance must not exceed max_distance"));
        }
        finiteRange(condition.minHealthRatio(), path + ".min_health_ratio",
                0.0, 1.0, issues);
    }

    private static void optionalId(
            String value,
            String path,
            List<CombatPolicyValidation.Issue> issues) {
        if (value == null) {
            return;
        }
        if (value.isBlank() || value.length() > 96
                || !BINDING_TOKEN.matcher(value).matches()) {
            issues.add(issue(path, "format",
                    "policy_id must be a non-blank safe token up to 96 characters"));
        }
    }

    private static void boundedText(
            String value,
            String path,
            int min,
            int max,
            List<CombatPolicyValidation.Issue> issues) {
        if (value == null || value.isBlank()
                || value.length() < min || value.length() > max) {
            issues.add(issue(path, "length",
                    "value length must be between " + min + " and " + max));
            return;
        }
        if (hasControlCharacter(value)) {
            issues.add(issue(path, "control_character",
                    "control characters are not allowed"));
        }
    }

    private static void token(
            String value,
            String path,
            Pattern pattern,
            int max,
            List<CombatPolicyValidation.Issue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(issue(path, "required", "value is required"));
        } else if (value.length() > max || !pattern.matcher(value).matches()) {
            issues.add(issue(path, "format", "value has an invalid format"));
        }
    }

    private static void finiteRange(
            Double value,
            String path,
            double min,
            double max,
            List<CombatPolicyValidation.Issue> issues) {
        if (value == null) {
            return;
        }
        if (!Double.isFinite(value) || value < min || value > max) {
            issues.add(issue(path, "range",
                    "value must be finite and between " + min + " and " + max));
        }
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static CombatPolicyValidation.Issue issue(
            String path, String code, String message) {
        return new CombatPolicyValidation.Issue(path, code, message);
    }
}
