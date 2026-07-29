package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.task.combat.CombatDecisionInput.ThreatType;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explainable high-level result; a later real-time policy executes the action. */
public record CombatDecision(
        Action action,
        Risk risk,
        ThreatType focusThreat,
        List<Reason> reasons,
        Set<HardVeto> hardVetoes) {

    public CombatDecision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(hardVetoes, "hardVetoes");
        reasons = List.copyOf(reasons);
        hardVetoes = Set.copyOf(hardVetoes);
        if (isVetoed(action, hardVetoes)) {
            throw new IllegalArgumentException("selected action is forbidden by a hard veto");
        }
    }

    public boolean isVetoed(Action candidate) {
        return isVetoed(candidate, hardVetoes);
    }

    private static boolean isVetoed(Action candidate, Set<HardVeto> vetoes) {
        VetoScope scope = switch (candidate) {
            case MELEE_ENGAGE -> VetoScope.MELEE;
            case RANGED_ENGAGE -> VetoScope.RANGED;
            default -> null;
        };
        if (scope == null) return false;
        return vetoes.stream().anyMatch(veto ->
                veto.scope() == VetoScope.ANY_ENGAGEMENT || veto.scope() == scope);
    }

    public enum Action {
        NONE,
        HOLD_SAFE_POSITION,
        HOLD_DEFENSIVE_POSITION,
        MELEE_ENGAGE,
        RANGED_ENGAGE,
        RETREAT,
        SEEK_HARD_COVER,
        SEEK_TRUSTED_SHELTER
    }

    public enum Risk {
        LOW,
        MODERATE,
        HIGH,
        CRITICAL
    }

    /** Stable machine-readable explanations suitable for logs and model context. */
    public enum Reason {
        NO_ACTIVE_THREAT,
        PASSIVE_THREAT_OUTSIDE_CURRENT_DIRECTIVE,
        PROTECTED_TARGET_NEVER_AUTONOMOUSLY_ATTACKED,
        EXPLOSION_IS_IMMINENT,
        RANGED_ATTACK_IS_CHARGING,
        PROJECTILE_ALREADY_RELEASED,
        AIRBORNE_DIVE_THREAT,
        LOW_EFFECTIVE_HEALTH,
        STRONG_DEFENSIVE_STATS,
        DANGEROUS_STATUS_EFFECT,
        LOW_HUNGER_LIMITS_SPRINT,
        OUTNUMBERED,
        MELEE_WEAPON_AVAILABLE,
        RANGED_WEAPON_AVAILABLE,
        SHIELD_AVAILABLE,
        HEALING_RESOURCES_AVAILABLE,
        VERIFIED_SHELTER_AVAILABLE,
        ALREADY_SECURE_IN_SHELTER,
        HARD_COVER_BREAKS_LINE_OF_SIGHT,
        OVERHEAD_COVER_COUNTERS_AIR_THREAT,
        ESCAPE_ROUTE_AVAILABLE,
        NO_SAFE_ESCAPE_ROUTE,
        OPEN_SPACE_SUPPORTS_KITING,
        CHOKE_POINT_REDUCES_NUMBERS,
        SINGLE_LOW_COMPLEXITY_MELEE_THREAT,
        HIT_AND_RUN_WINDOW_AVAILABLE,
        RANGED_CAPABILITY_OBSERVED,
        UNKNOWN_THREAT_CAPABILITY,
        OVERWHELMING_TARGET
    }

    public enum VetoScope {
        ANY_ENGAGEMENT,
        MELEE,
        RANGED
    }

    public enum VetoCode {
        PROTECTED_TARGET,
        CRITICAL_VITAL_STATE,
        IMMINENT_EXPLOSION,
        OUTNUMBERED_FOR_MELEE,
        NO_MELEE_CAPABILITY,
        NO_RANGED_CAPABILITY,
        MELEE_IMPAIRING_EFFECT,
        NO_MELEE_ESCAPE_ROUTE,
        AIRBORNE_TARGET,
        HEAVY_MELEE_TARGET,
        OVERWHELMING_TARGET
    }

    /** A hard prohibition is scoped, unlike a soft reason used in risk scoring. */
    public record HardVeto(VetoScope scope, VetoCode code) {
        public HardVeto {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(code, "code");
        }
    }
}
