package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.task.combat.CombatDecision.Action;
import com.dwinovo.numen.core.task.combat.CombatDecision.HardVeto;
import com.dwinovo.numen.core.task.combat.CombatDecision.Reason;
import com.dwinovo.numen.core.task.combat.CombatDecision.Risk;
import com.dwinovo.numen.core.task.combat.CombatDecision.VetoCode;
import com.dwinovo.numen.core.task.combat.CombatDecision.VetoScope;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.AttackPhase;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.EngagementDirective;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.Loadout;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.StatusEffects;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.TerrainState;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.ThreatGroup;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.ThreatType;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.Vitals;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure high-level combat decision core.
 *
 * <p>This class decides whether the body should fight, shoot, retreat, or seek
 * verified safety. It does not execute tick-sensitive mechanics. Luna-authored
 * combat policies can later implement actions such as creeper hit-and-run while
 * this core remains the non-bypassable survival gate.
 */
public final class CombatDecisionEngine {

    private static final double CRITICAL_HEALTH_RATIO = 0.35;
    private static final double HEALTHY_MELEE_RATIO = 0.70;
    private static final double VERY_HEALTHY_RATIO = 0.82;
    private static final double CREEPER_BLAST_ABORT_DISTANCE = 7.0;

    private CombatDecisionEngine() {}

    public static CombatDecision decide(CombatDecisionInput input) {
        List<ThreatGroup> eligible = input.threats().stream()
                .filter(threat -> isEligible(threat, input.directive()))
                .toList();
        if (eligible.isEmpty()) {
            Reason reason = input.threats().isEmpty()
                    ? Reason.NO_ACTIVE_THREAT
                    : Reason.PASSIVE_THREAT_OUTSIDE_CURRENT_DIRECTIVE;
            return new CombatDecision(Action.NONE, Risk.LOW, null,
                    List.of(reason), Set.of());
        }

        ThreatGroup focus = eligible.stream()
                .max(Comparator.comparingDouble(CombatDecisionEngine::threatPriority))
                .orElseThrow();
        int totalThreats = eligible.stream().mapToInt(ThreatGroup::count).sum();
        LinkedHashSet<Reason> reasons = baseReasons(input, focus, totalThreats);
        EnumSetBuilder vetoes = hardVetoes(input, focus, totalThreats);
        Risk risk = assessRisk(input, eligible, totalThreats);

        Action action = chooseAction(input, focus, totalThreats, risk, reasons, vetoes);
        return new CombatDecision(action, risk, focus.type(),
                List.copyOf(reasons), vetoes.copy());
    }

    private static boolean isEligible(ThreatGroup threat, EngagementDirective directive) {
        return directive != EngagementDirective.SURVIVAL_ONLY
                || threat.isActivelyDangerous();
    }

    private static Action chooseAction(
            CombatDecisionInput input,
            ThreatGroup focus,
            int totalThreats,
            Risk risk,
            LinkedHashSet<Reason> reasons,
            EnumSetBuilder vetoes) {
        TerrainState terrain = input.terrain();
        Vitals vitals = input.self().vitals();

        if (terrain.isSecureInsideShelter()
                && !focus.hasLineOfSight()
                && focus.phase() != AttackPhase.EXPLOSION_CHARGE) {
            reasons.add(Reason.ALREADY_SECURE_IN_SHELTER);
            return Action.HOLD_SAFE_POSITION;
        }

        if (hasAnyEngagementVeto(vetoes)) {
            return chooseSafety(input, focus, reasons,
                    focus.type() != ThreatType.CREEPER);
        }

        if (vitals.effectiveHealthRatio() <= CRITICAL_HEALTH_RATIO) {
            return chooseSafety(input, focus, reasons, true);
        }

        if (totalThreats >= 3) {
            return chooseSafety(input, focus, reasons, true);
        }

        return switch (focus.type()) {
            case CREEPER -> decideCreeper(input, focus, reasons, vetoes);
            case SKELETON -> decideSkeleton(input, focus, reasons, vetoes);
            case SPIDER -> decideSpider(input, focus, reasons, vetoes);
            case PHANTOM -> decidePhantom(input, focus, reasons, vetoes);
            case ZOMBIE -> decideZombie(input, focus, totalThreats, reasons, vetoes);
            case PLAYER -> chooseSafety(input, focus, reasons, true);
            case IRON_GOLEM -> decideIronGolem(input, focus, reasons, vetoes);
            case OTHER_HOSTILE ->
                    decideGenericHostile(input, focus, totalThreats, risk, reasons, vetoes);
        };
    }

    private static Action decideCreeper(
            CombatDecisionInput input,
            ThreatGroup threat,
            LinkedHashSet<Reason> reasons,
            EnumSetBuilder vetoes) {
        boolean imminent = threat.phase() == AttackPhase.EXPLOSION_CHARGE
                || threat.nearestDistance() <= 2.8;
        if (imminent) {
            reasons.add(Reason.EXPLOSION_IS_IMMINENT);
            // Never lead an already primed creeper through a home's doorway.
            return input.terrain().hasEscapeRoute()
                    ? Action.RETREAT
                    : chooseSafety(input, threat, reasons, false);
        }
        if (!vetoes.vetoes(Action.RANGED_ENGAGE)
                && threat.nearestDistance() >= 6.0) {
            reasons.add(Reason.RANGED_WEAPON_AVAILABLE);
            return Action.RANGED_ENGAGE;
        }
        if (!vetoes.vetoes(Action.MELEE_ENGAGE)
                && input.self().vitals().effectiveHealthRatio() >= VERY_HEALTHY_RATIO
                && input.self().loadout().meleeAttackDamage() >= 5.0
                && input.terrain().kitingSpace()
                && threat.count() == 1) {
            reasons.add(Reason.HIT_AND_RUN_WINDOW_AVAILABLE);
            return Action.MELEE_ENGAGE;
        }
        return chooseSafety(input, threat, reasons, false);
    }

    private static Action decideSkeleton(
            CombatDecisionInput input,
            ThreatGroup threat,
            LinkedHashSet<Reason> reasons,
            EnumSetBuilder vetoes) {
        boolean shotCommitted = threat.hasLineOfSight()
                && (threat.phase() == AttackPhase.RANGED_CHARGE
                || threat.phase() == AttackPhase.PROJECTILE_RELEASED);
        if (shotCommitted && input.terrain().hardCoverReachable()) {
            reasons.add(Reason.HARD_COVER_BREAKS_LINE_OF_SIGHT);
            return Action.SEEK_HARD_COVER;
        }
        if (!vetoes.vetoes(Action.MELEE_ENGAGE)
                && input.self().loadout().hasShield()
                && threat.nearestDistance() <= 7.0
                && input.self().vitals().effectiveHealthRatio() >= HEALTHY_MELEE_RATIO) {
            reasons.add(Reason.SHIELD_AVAILABLE);
            return Action.MELEE_ENGAGE;
        }
        if (!vetoes.vetoes(Action.RANGED_ENGAGE)
                && (input.terrain().hardCoverReachable()
                || input.terrain().kitingSpace())) {
            reasons.add(Reason.RANGED_WEAPON_AVAILABLE);
            return Action.RANGED_ENGAGE;
        }
        return chooseSafety(input, threat, reasons, true);
    }

    private static Action decideSpider(
            CombatDecisionInput input,
            ThreatGroup threat,
            LinkedHashSet<Reason> reasons,
            EnumSetBuilder vetoes) {
        if (threat.count() == 1
                && !vetoes.vetoes(Action.MELEE_ENGAGE)
                && input.self().vitals().effectiveHealthRatio() >= HEALTHY_MELEE_RATIO) {
            reasons.add(Reason.SINGLE_LOW_COMPLEXITY_MELEE_THREAT);
            return Action.MELEE_ENGAGE;
        }
        // Open-air height is intentionally not considered safe: spiders climb.
        return chooseSafety(input, threat, reasons, true);
    }

    private static Action decidePhantom(
            CombatDecisionInput input,
            ThreatGroup threat,
            LinkedHashSet<Reason> reasons,
            EnumSetBuilder vetoes) {
        if (input.terrain().overheadCoverHere()) {
            reasons.add(Reason.OVERHEAD_COVER_COUNTERS_AIR_THREAT);
            return Action.HOLD_SAFE_POSITION;
        }
        if (input.terrain().canReachVerifiedShelter()
                || input.terrain().hardCoverReachable()) {
            reasons.add(Reason.OVERHEAD_COVER_COUNTERS_AIR_THREAT);
            return input.terrain().canReachVerifiedShelter()
                    ? Action.SEEK_TRUSTED_SHELTER
                    : Action.SEEK_HARD_COVER;
        }
        if (threat.phase() != AttackPhase.DIVING
                && !vetoes.vetoes(Action.RANGED_ENGAGE)) {
            reasons.add(Reason.RANGED_WEAPON_AVAILABLE);
            return Action.RANGED_ENGAGE;
        }
        return chooseSafety(input, threat, reasons, false);
    }

    private static Action decideZombie(
            CombatDecisionInput input,
            ThreatGroup threat,
            int totalThreats,
            LinkedHashSet<Reason> reasons,
            EnumSetBuilder vetoes) {
        // Drowned and modded zombie variants may have a server-observed ranged
        // capability. Never apply the ordinary close-combat zombie rule to them.
        if (threat.rangedCapable()
                || threat.phase() == AttackPhase.RANGED_CHARGE
                || threat.phase() == AttackPhase.PROJECTILE_RELEASED) {
            reasons.add(Reason.RANGED_CAPABILITY_OBSERVED);
            return decideGenericHostile(
                    input, threat, totalThreats, Risk.HIGH, reasons, vetoes);
        }
        if (totalThreats == 1
                && !vetoes.vetoes(Action.MELEE_ENGAGE)
                && input.self().vitals().effectiveHealthRatio() >= HEALTHY_MELEE_RATIO) {
            reasons.add(Reason.SINGLE_LOW_COMPLEXITY_MELEE_THREAT);
            return Action.MELEE_ENGAGE;
        }
        if (totalThreats == 2
                && input.terrain().chokePointAvailable()
                && input.self().loadout().hasShield()
                && !vetoes.vetoes(Action.MELEE_ENGAGE)) {
            reasons.add(Reason.CHOKE_POINT_REDUCES_NUMBERS);
            return Action.MELEE_ENGAGE;
        }
        if (!vetoes.vetoes(Action.RANGED_ENGAGE)
                && threat.nearestDistance() >= 6.0
                && input.terrain().hasEscapeRoute()) {
            reasons.add(Reason.RANGED_WEAPON_AVAILABLE);
            return Action.RANGED_ENGAGE;
        }
        return chooseSafety(input, threat, reasons, true);
    }

    private static Action decideIronGolem(
            CombatDecisionInput input,
            ThreatGroup threat,
            LinkedHashSet<Reason> reasons,
            EnumSetBuilder vetoes) {
        // A passive village golem is never an autonomous clearing target. Actual
        // self-defence or an explicit user order may use ranged spacing, while
        // melee remains hard-vetoed because of launch/knockback damage.
        if (input.directive() == EngagementDirective.GUARD_AREA
                || !threat.targetingSelf()
                && input.directive() != EngagementDirective.EXPLICIT_COMBAT) {
            return chooseSafety(input, threat, reasons, true);
        }
        if (!vetoes.vetoes(Action.RANGED_ENGAGE)
                && threat.nearestDistance() >= 10.0
                && input.terrain().hasEscapeRoute()
                && input.self().vitals().effectiveHealthRatio() >= VERY_HEALTHY_RATIO) {
            reasons.add(Reason.RANGED_WEAPON_AVAILABLE);
            return Action.RANGED_ENGAGE;
        }
        return chooseSafety(input, threat, reasons, true);
    }

    private static Action decideGenericHostile(
            CombatDecisionInput input,
            ThreatGroup threat,
            int totalThreats,
            Risk risk,
            LinkedHashSet<Reason> reasons,
            EnumSetBuilder vetoes) {
        if (threat.bossLike()
                || threat.primaryMaxHealth() >= 80.0
                || threat.primaryAttackDamage() >= 12.0) {
            reasons.add(Reason.OVERWHELMING_TARGET);
            return chooseSafety(input, threat, reasons, true);
        }
        reasons.add(Reason.UNKNOWN_THREAT_CAPABILITY);
        if (threat.rangedCapable()) {
            reasons.add(Reason.RANGED_CAPABILITY_OBSERVED);
        }
        // Unknown/modded entities are intentionally never defaulted to melee.
        // A Luna-authored exact-binding policy may propose a strike later, but
        // it will still pass the hard vetoes and real-time supervisor.
        if (!vetoes.vetoes(Action.RANGED_ENGAGE)
                && threat.nearestDistance() >= 7.0
                && input.terrain().hasEscapeRoute()
                && (!threat.rangedCapable()
                || input.terrain().hardCoverReachable()
                || input.terrain().kitingSpace())
                && risk != Risk.CRITICAL) {
            reasons.add(Reason.RANGED_WEAPON_AVAILABLE);
            return Action.RANGED_ENGAGE;
        }
        return chooseSafety(input, threat, reasons, true);
    }

    private static Action chooseSafety(
            CombatDecisionInput input,
            ThreatGroup focus,
            LinkedHashSet<Reason> reasons,
            boolean preferShelter) {
        TerrainState terrain = input.terrain();
        boolean projectileThreat = focus.type() == ThreatType.SKELETON
                || focus.type() == ThreatType.PHANTOM
                || focus.phase() == AttackPhase.RANGED_CHARGE
                || focus.phase() == AttackPhase.PROJECTILE_RELEASED;

        if (preferShelter && terrain.canReachVerifiedShelter()) {
            reasons.add(Reason.VERIFIED_SHELTER_AVAILABLE);
            return Action.SEEK_TRUSTED_SHELTER;
        }
        if (projectileThreat && terrain.hardCoverReachable()) {
            reasons.add(Reason.HARD_COVER_BREAKS_LINE_OF_SIGHT);
            return Action.SEEK_HARD_COVER;
        }
        if (terrain.hasEscapeRoute()) {
            reasons.add(Reason.ESCAPE_ROUTE_AVAILABLE);
            return Action.RETREAT;
        }
        if (!preferShelter && terrain.canReachVerifiedShelter()
                && focus.phase() != AttackPhase.EXPLOSION_CHARGE) {
            reasons.add(Reason.VERIFIED_SHELTER_AVAILABLE);
            return Action.SEEK_TRUSTED_SHELTER;
        }
        if (terrain.hardCoverReachable()) {
            reasons.add(Reason.HARD_COVER_BREAKS_LINE_OF_SIGHT);
            return Action.SEEK_HARD_COVER;
        }
        reasons.add(Reason.NO_SAFE_ESCAPE_ROUTE);
        return Action.HOLD_DEFENSIVE_POSITION;
    }

    private static EnumSetBuilder hardVetoes(
            CombatDecisionInput input,
            ThreatGroup focus,
            int totalThreats) {
        EnumSetBuilder result = new EnumSetBuilder();
        Loadout loadout = input.self().loadout();
        StatusEffects effects = input.self().effects();

        if (focus.type() == ThreatType.PLAYER) {
            result.add(VetoScope.ANY_ENGAGEMENT, VetoCode.PROTECTED_TARGET);
        }
        if (focus.type() == ThreatType.IRON_GOLEM
                || focus.airborne()
                || focus.primaryAttackDamage() >= 10.0) {
            result.add(VetoScope.MELEE, VetoCode.HEAVY_MELEE_TARGET);
        }
        if (focus.bossLike()
                && input.directive() != EngagementDirective.EXPLICIT_COMBAT) {
            result.add(VetoScope.ANY_ENGAGEMENT, VetoCode.OVERWHELMING_TARGET);
        }
        if (input.self().vitals().effectiveHealthRatio() <= CRITICAL_HEALTH_RATIO) {
            result.add(VetoScope.ANY_ENGAGEMENT, VetoCode.CRITICAL_VITAL_STATE);
        }
        if (focus.type() == ThreatType.CREEPER
                && focus.phase() == AttackPhase.EXPLOSION_CHARGE
                && focus.nearestDistance() <= CREEPER_BLAST_ABORT_DISTANCE) {
            result.add(VetoScope.ANY_ENGAGEMENT, VetoCode.IMMINENT_EXPLOSION);
        }
        if (totalThreats >= 3) {
            result.add(VetoScope.MELEE, VetoCode.OUTNUMBERED_FOR_MELEE);
        }
        if (!loadout.hasMeleeWeapon() || loadout.meleeAttackDamage() <= 0.0) {
            result.add(VetoScope.MELEE, VetoCode.NO_MELEE_CAPABILITY);
        }
        if (!loadout.canUseRangedWeapon()) {
            result.add(VetoScope.RANGED, VetoCode.NO_RANGED_CAPABILITY);
        }
        if (effects.hasMeleeImpairment()) {
            result.add(VetoScope.MELEE, VetoCode.MELEE_IMPAIRING_EFFECT);
        }
        if (!input.terrain().hasEscapeRoute()
                && (focus.type() == ThreatType.CREEPER || totalThreats >= 2)) {
            result.add(VetoScope.MELEE, VetoCode.NO_MELEE_ESCAPE_ROUTE);
        }
        if (focus.type() == ThreatType.PHANTOM || focus.airborne()) {
            result.add(VetoScope.MELEE, VetoCode.AIRBORNE_TARGET);
        }
        return result;
    }

    private static LinkedHashSet<Reason> baseReasons(
            CombatDecisionInput input,
            ThreatGroup focus,
            int totalThreats) {
        LinkedHashSet<Reason> reasons = new LinkedHashSet<>();
        Vitals vitals = input.self().vitals();
        Loadout loadout = input.self().loadout();
        StatusEffects effects = input.self().effects();

        if (focus.type() == ThreatType.PLAYER) {
            reasons.add(Reason.PROTECTED_TARGET_NEVER_AUTONOMOUSLY_ATTACKED);
        }
        if (focus.bossLike()
                || focus.primaryMaxHealth() >= 80.0
                || focus.primaryAttackDamage() >= 12.0) {
            reasons.add(Reason.OVERWHELMING_TARGET);
        }
        if (focus.rangedCapable()) {
            reasons.add(Reason.RANGED_CAPABILITY_OBSERVED);
        }
        if (focus.phase() == AttackPhase.EXPLOSION_CHARGE) {
            reasons.add(Reason.EXPLOSION_IS_IMMINENT);
        }
        if (focus.phase() == AttackPhase.RANGED_CHARGE) {
            reasons.add(Reason.RANGED_ATTACK_IS_CHARGING);
        } else if (focus.phase() == AttackPhase.PROJECTILE_RELEASED) {
            reasons.add(Reason.PROJECTILE_ALREADY_RELEASED);
        } else if (focus.phase() == AttackPhase.DIVING) {
            reasons.add(Reason.AIRBORNE_DIVE_THREAT);
        }
        if (vitals.effectiveHealthRatio() <= CRITICAL_HEALTH_RATIO) {
            reasons.add(Reason.LOW_EFFECTIVE_HEALTH);
        }
        if (vitals.armor() >= 12.0
                || vitals.armorToughness() >= 4.0
                || effects.resistanceLevel() > 0) {
            reasons.add(Reason.STRONG_DEFENSIVE_STATS);
        }
        if (effects.hasDangerousDamageOverTime() || effects.hasMeleeImpairment()) {
            reasons.add(Reason.DANGEROUS_STATUS_EFFECT);
        }
        if (!vitals.canSprint()) {
            reasons.add(Reason.LOW_HUNGER_LIMITS_SPRINT);
        }
        if (totalThreats >= 3) {
            reasons.add(Reason.OUTNUMBERED);
        }
        if (loadout.hasMeleeWeapon()) {
            reasons.add(Reason.MELEE_WEAPON_AVAILABLE);
        }
        if (loadout.canUseRangedWeapon()) {
            reasons.add(Reason.RANGED_WEAPON_AVAILABLE);
        }
        if (loadout.hasShield()) {
            reasons.add(Reason.SHIELD_AVAILABLE);
        }
        if (loadout.healingItems() > 0 || loadout.foodItems() > 0) {
            reasons.add(Reason.HEALING_RESOURCES_AVAILABLE);
        }
        if (input.terrain().canReachVerifiedShelter()) {
            reasons.add(Reason.VERIFIED_SHELTER_AVAILABLE);
        }
        if (input.terrain().hardCoverReachable()) {
            reasons.add(Reason.HARD_COVER_BREAKS_LINE_OF_SIGHT);
        }
        if (input.terrain().hasEscapeRoute()) {
            reasons.add(Reason.ESCAPE_ROUTE_AVAILABLE);
        }
        if (input.terrain().kitingSpace()) {
            reasons.add(Reason.OPEN_SPACE_SUPPORTS_KITING);
        }
        if (input.terrain().chokePointAvailable()) {
            reasons.add(Reason.CHOKE_POINT_REDUCES_NUMBERS);
        }
        return reasons;
    }

    private static Risk assessRisk(
            CombatDecisionInput input,
            List<ThreatGroup> threats,
            int totalThreats) {
        Vitals vitals = input.self().vitals();
        StatusEffects effects = input.self().effects();
        boolean imminentExplosion = threats.stream().anyMatch(threat ->
                threat.type() == ThreatType.CREEPER
                        && threat.phase() == AttackPhase.EXPLOSION_CHARGE
                        && threat.nearestDistance() <= CREEPER_BLAST_ABORT_DISTANCE);
        int score = 0;
        double healthRatio = vitals.effectiveHealthRatio();
        if (healthRatio <= CRITICAL_HEALTH_RATIO) score += 6;
        else if (healthRatio <= 0.55) score += 3;
        else if (healthRatio <= 0.75) score += 1;

        if (vitals.armor() < 4.0) score += 2;
        else if (vitals.armor() >= 12.0) score -= 1;
        if (vitals.armorToughness() >= 4.0) score -= 1;
        score -= Math.min(2, effects.resistanceLevel());
        if (effects.strengthLevel() >= 2 && input.self().loadout().hasMeleeWeapon()) {
            score -= 1;
        }
        if (effects.speedLevel() > 0 && input.terrain().hasEscapeRoute()) {
            score -= 1;
        }
        if (effects.regenerationLevel() > 0
                && vitals.effectiveHealthRatio() > CRITICAL_HEALTH_RATIO) {
            score -= 1;
        }
        if (input.self().loadout().healingItems() > 0
                && (input.terrain().hardCoverReachable()
                || input.terrain().canReachVerifiedShelter())) {
            score -= 1;
        }
        if (!vitals.canSprint()) score += 1;
        if (effects.witherLevel() > 0) score += 3;
        if (effects.poisonLevel() > 0) score += 2;
        if (effects.weaknessLevel() > 0 || effects.slownessLevel() > 0) score += 1;

        if (totalThreats >= 3) score += 4;
        else if (totalThreats == 2) score += 2;

        double nearest = threats.stream()
                .mapToDouble(ThreatGroup::nearestDistance)
                .min()
                .orElse(Double.MAX_VALUE);
        if (nearest <= 3.0) score += 3;
        else if (nearest <= 7.0) score += 1;

        for (ThreatGroup threat : threats) {
            score += switch (threat.type()) {
                case CREEPER, PLAYER, IRON_GOLEM -> 2;
                case SKELETON, PHANTOM -> 1;
                default -> 0;
            };
            if (threat.hasLineOfSight()
                    && (threat.type() == ThreatType.SKELETON
                    || threat.type() == ThreatType.CREEPER)) {
                score += 1;
            }
            if (threat.phase() == AttackPhase.EXPLOSION_CHARGE
                    || threat.phase() == AttackPhase.PROJECTILE_RELEASED
                    || threat.phase() == AttackPhase.DIVING) {
                score += 3;
            }
            if (threat.rangedCapable()) score += 1;
            if (threat.airborne()) score += 1;
            if (threat.primaryMaxHealth() >= 40.0) score += 2;
            if (threat.primaryAttackDamage() >= 8.0) score += 2;
            if (threat.bossLike()) score += 6;
        }

        if (!input.terrain().hasEscapeRoute()) score += 2;
        if (input.terrain().isSecureInsideShelter()) score -= 4;
        else if (input.terrain().canReachVerifiedShelter()) score -= 1;

        if (imminentExplosion) return Risk.CRITICAL;
        if (score <= 2) return Risk.LOW;
        if (score <= 5) return Risk.MODERATE;
        if (score <= 8) return Risk.HIGH;
        return Risk.CRITICAL;
    }

    private static double threatPriority(ThreatGroup threat) {
        double score = threat.count() * 3.0
                + Math.max(0.0, 24.0 - threat.nearestDistance()) / 4.0;
        if (threat.targetingSelf()) score += 5.0;
        if (threat.phase().isAttackCommitment()) score += 6.0;
        if (threat.rangedCapable()) score += 2.0;
        if (threat.bossLike()) score += 8.0;
        score += Math.min(6.0, threat.primaryAttackDamage() / 2.0);
        score += switch (threat.type()) {
            case CREEPER -> 7.0;
            case SKELETON, PHANTOM -> 4.0;
            case PLAYER, IRON_GOLEM -> 3.0;
            default -> 1.0;
        };
        return score;
    }

    private static boolean hasAnyEngagementVeto(EnumSetBuilder vetoes) {
        return vetoes.values.stream().anyMatch(
                veto -> veto.scope() == VetoScope.ANY_ENGAGEMENT);
    }

    /** Tiny insertion-ordered set wrapper to keep the public result immutable. */
    private static final class EnumSetBuilder {
        private final LinkedHashSet<HardVeto> values = new LinkedHashSet<>();

        void add(VetoScope scope, VetoCode code) {
            values.add(new HardVeto(scope, code));
        }

        boolean vetoes(Action action) {
            VetoScope scope = switch (action) {
                case MELEE_ENGAGE -> VetoScope.MELEE;
                case RANGED_ENGAGE -> VetoScope.RANGED;
                default -> null;
            };
            return scope != null && values.stream().anyMatch(veto ->
                    veto.scope() == VetoScope.ANY_ENGAGEMENT || veto.scope() == scope);
        }

        Set<HardVeto> copy() {
            return Set.copyOf(values);
        }
    }
}
