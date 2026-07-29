package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.task.combat.CombatDecision.Action;
import com.dwinovo.numen.core.task.combat.CombatDecision.Reason;
import com.dwinovo.numen.core.task.combat.CombatDecision.Risk;
import com.dwinovo.numen.core.task.combat.CombatDecision.VetoCode;
import com.dwinovo.numen.core.task.combat.CombatDecision.VetoScope;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.AttackPhase;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.EngagementDirective;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.Loadout;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.RangedWeapon;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.SelfState;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.StatusEffects;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.TerrainState;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.ThreatGroup;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.ThreatType;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.Vitals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDecisionEngineTest {

    @Test
    void survivalModeIgnoresPassiveNearbyMob() {
        CombatDecision decision = decide(healthy(), openTerrain(),
                threat(ThreatType.ZOMBIE, 1, 8.0, false, false, AttackPhase.IDLE),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.NONE, decision.action());
        assertTrue(decision.reasons().contains(
                Reason.PASSIVE_THREAT_OUTSIDE_CURRENT_DIRECTIVE));
    }

    @Test
    void closePrimedCreeperAlwaysAbortsCombatAndRetreats() {
        CombatDecision decision = decide(healthy(), openTerrain(),
                threat(ThreatType.CREEPER, 1, 2.5, true, true,
                        AttackPhase.EXPLOSION_CHARGE),
                EngagementDirective.EXPLICIT_COMBAT);

        assertEquals(Action.RETREAT, decision.action());
        assertEquals(Risk.CRITICAL, decision.risk());
        assertTrue(hasVeto(decision, VetoScope.ANY_ENGAGEMENT,
                VetoCode.IMMINENT_EXPLOSION));
        assertTrue(decision.isVetoed(Action.MELEE_ENGAGE));
        assertTrue(decision.isVetoed(Action.RANGED_ENGAGE));
    }

    @Test
    void distantCreeperUsesBowInsteadOfClosingDistance() {
        CombatDecision decision = decide(healthy(), openTerrain(),
                threat(ThreatType.CREEPER, 1, 12.0, true, true, AttackPhase.APPROACHING),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.RANGED_ENGAGE, decision.action());
    }

    @Test
    void healthyBodyMayRunLunaAuthoredHitAndRunAgainstSingleCreeper() {
        SelfState noBow = new SelfState(
                new Vitals(20, 20, 0, 20, 12, 2),
                new Loadout(true, 7, RangedWeapon.NONE, 0, true, 4, 0),
                noEffects());
        CombatDecision decision = decide(noBow, openTerrain(),
                threat(ThreatType.CREEPER, 1, 5.0, true, true, AttackPhase.APPROACHING),
                EngagementDirective.EXPLICIT_COMBAT);

        assertEquals(Action.MELEE_ENGAGE, decision.action());
        assertTrue(decision.reasons().contains(Reason.HIT_AND_RUN_WINDOW_AVAILABLE));
    }

    @Test
    void skeletonShotWindupSeeksHardCoverBeforeAttacking() {
        CombatDecision decision = decide(healthy(), openTerrain(),
                threat(ThreatType.SKELETON, 1, 10.0, true, true,
                        AttackPhase.RANGED_CHARGE),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.SEEK_HARD_COVER, decision.action());
        assertTrue(decision.reasons().contains(Reason.RANGED_ATTACK_IS_CHARGING));
    }

    @Test
    void shieldSupportsClosingOnNearbySkeletonWhenNoCoverExists() {
        TerrainState noCover = new TerrainState(
                false, false, 0, 2, true, false,
                false, false, 0, false, false, false);
        CombatDecision decision = decide(healthy(), noCover,
                threat(ThreatType.SKELETON, 1, 5.0, true, true, AttackPhase.APPROACHING),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.MELEE_ENGAGE, decision.action());
        assertTrue(decision.reasons().contains(Reason.SHIELD_AVAILABLE));
    }

    @Test
    void criticalHealthUsesVerifiedShelterEvenWhenWellEquipped() {
        SelfState hurt = new SelfState(
                new Vitals(5, 20, 0, 18, 16, 4),
                new Loadout(true, 8, RangedWeapon.BOW, 32, true, 5, 1),
                noEffects());
        CombatDecision decision = decide(hurt, openTerrain(),
                threat(ThreatType.ZOMBIE, 1, 5.0, true, true, AttackPhase.APPROACHING),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.SEEK_TRUSTED_SHELTER, decision.action());
        assertTrue(hasVeto(decision, VetoScope.ANY_ENGAGEMENT,
                VetoCode.CRITICAL_VITAL_STATE));
    }

    @Test
    void unlitOrBrokenRememberedHouseIsNotTrustedAsSafety() {
        TerrainState unsafeHouse = new TerrainState(
                false, false, 0, 2, true, false,
                false, true, 5, true, false, false);
        SelfState hurt = new SelfState(
                new Vitals(4, 20, 0, 12, 3, 0),
                new Loadout(true, 5, RangedWeapon.NONE, 0, false, 1, 0),
                noEffects());

        CombatDecision decision = decide(hurt, unsafeHouse,
                threat(ThreatType.SPIDER, 2, 4.0, true, true, AttackPhase.APPROACHING),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.RETREAT, decision.action());
        assertFalse(decision.reasons().contains(Reason.VERIFIED_SHELTER_AVAILABLE));
    }

    @Test
    void phantomMakesOverheadCoverTheFirstGoal() {
        CombatDecision decision = decide(healthy(), openTerrain(),
                threat(ThreatType.PHANTOM, 1, 8.0, true, true, AttackPhase.DIVING),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.SEEK_TRUSTED_SHELTER, decision.action());
        assertTrue(decision.reasons().contains(Reason.OVERHEAD_COVER_COUNTERS_AIR_THREAT));
        assertTrue(hasVeto(decision, VetoScope.MELEE, VetoCode.AIRBORNE_TARGET));
    }

    @Test
    void singleZombieIsSafeMeleeButCrowdUsesShelter() {
        CombatDecision single = decide(healthy(), openTerrain(),
                threat(ThreatType.ZOMBIE, 1, 4.0, true, true, AttackPhase.APPROACHING),
                EngagementDirective.SURVIVAL_ONLY);
        CombatDecision crowd = decide(healthy(), openTerrain(),
                threat(ThreatType.ZOMBIE, 4, 5.0, true, true, AttackPhase.APPROACHING),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.MELEE_ENGAGE, single.action());
        assertEquals(Action.SEEK_TRUSTED_SHELTER, crowd.action());
        assertTrue(hasVeto(crowd, VetoScope.MELEE,
                VetoCode.OUTNUMBERED_FOR_MELEE));
    }

    @Test
    void playerIsAlwaysProtected() {
        CombatDecision decision = decide(healthy(), openTerrain(),
                threat(ThreatType.PLAYER, 1, 3.0, true, true,
                        AttackPhase.MELEE_WINDUP),
                EngagementDirective.EXPLICIT_COMBAT);

        assertEquals(Action.SEEK_TRUSTED_SHELTER, decision.action());
        assertTrue(hasVeto(decision, VetoScope.ANY_ENGAGEMENT,
                VetoCode.PROTECTED_TARGET));
    }

    @Test
    void ironGolemAllowsOnlySpacedExplicitOrSelfDefenceCombat() {
        CombatDecision decision = decide(healthy(), openTerrain(),
                threat(ThreatType.IRON_GOLEM, 1, 12.0, true, false,
                        AttackPhase.IDLE),
                EngagementDirective.EXPLICIT_COMBAT);

        assertEquals(Action.RANGED_ENGAGE, decision.action());
        assertTrue(hasVeto(decision, VetoScope.MELEE,
                VetoCode.HEAVY_MELEE_TARGET));
        assertFalse(hasVeto(decision, VetoScope.ANY_ENGAGEMENT,
                VetoCode.PROTECTED_TARGET));
    }

    @Test
    void unknownAndOverwhelmingEntitiesNeverDefaultToMelee() {
        ThreatGroup unknown = new ThreatGroup(
                ThreatType.OTHER_HOSTILE,
                1,
                4.0,
                true,
                true,
                AttackPhase.UNKNOWN,
                1.0,
                20.0,
                4.0,
                false,
                false,
                false);
        ThreatGroup boss = new ThreatGroup(
                ThreatType.OTHER_HOSTILE,
                1,
                9.0,
                true,
                true,
                AttackPhase.UNKNOWN,
                1.0,
                120.0,
                14.0,
                true,
                false,
                true);

        assertFalse(decide(
                healthy(),
                openTerrain(),
                unknown,
                EngagementDirective.SURVIVAL_ONLY).action()
                == Action.MELEE_ENGAGE);
        assertEquals(
                Action.SEEK_TRUSTED_SHELTER,
                decide(
                        healthy(),
                        openTerrain(),
                        boss,
                        EngagementDirective.SURVIVAL_ONLY).action());
    }

    @Test
    void securedHouseHoldsPositionWhenThreatCannotSeeInside() {
        TerrainState inside = new TerrainState(
                true, true, 2, 1, false, true,
                true, true, 0, true, true, true);
        CombatDecision decision = decide(healthy(), inside,
                threat(ThreatType.SPIDER, 2, 4.0, false, true, AttackPhase.APPROACHING),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.HOLD_SAFE_POSITION, decision.action());
        assertTrue(decision.reasons().contains(Reason.ALREADY_SECURE_IN_SHELTER));
    }

    @Test
    void poisonAndWeaknessHardVetoMelee() {
        SelfState impaired = new SelfState(
                new Vitals(18, 20, 0, 18, 10, 2),
                new Loadout(true, 7, RangedWeapon.BOW, 20, true, 3, 0),
                new StatusEffects(0, 0, 0, 0, 1, 0, 1, 0, 0));
        CombatDecision decision = decide(impaired, openTerrain(),
                threat(ThreatType.ZOMBIE, 1, 8.0, true, true, AttackPhase.APPROACHING),
                EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Action.RANGED_ENGAGE, decision.action());
        assertTrue(hasVeto(decision, VetoScope.MELEE,
                VetoCode.MELEE_IMPAIRING_EFFECT));
    }

    @Test
    void armorToughnessBuffsAndHealingResourcesImproveRiskAssessment() {
        SelfState prepared = new SelfState(
                new Vitals(14, 20, 2, 18, 16, 4),
                new Loadout(true, 8, RangedWeapon.BOW, 24, true, 4, 1),
                new StatusEffects(1, 2, 1, 1, 0, 0, 0, 0, 0));
        SelfState exposed = new SelfState(
                new Vitals(10, 20, 0, 5, 0, 0),
                new Loadout(true, 4, RangedWeapon.NONE, 0, false, 0, 0),
                new StatusEffects(0, 0, 0, 0, 1, 1, 1, 0, 0));
        ThreatGroup skeleton = threat(
                ThreatType.SKELETON, 1, 8.0, true, true, AttackPhase.APPROACHING);

        CombatDecision preparedDecision = decide(
                prepared, openTerrain(), skeleton, EngagementDirective.SURVIVAL_ONLY);
        CombatDecision exposedDecision = decide(
                exposed, openTerrain(), skeleton, EngagementDirective.SURVIVAL_ONLY);

        assertEquals(Risk.LOW, preparedDecision.risk());
        assertEquals(Risk.CRITICAL, exposedDecision.risk());
    }

    private static CombatDecision decide(
            SelfState self,
            TerrainState terrain,
            ThreatGroup threat,
            EngagementDirective directive) {
        return CombatDecisionEngine.decide(
                new CombatDecisionInput(self, List.of(threat), terrain, directive));
    }

    private static SelfState healthy() {
        return new SelfState(
                new Vitals(20, 20, 0, 20, 12, 2),
                new Loadout(true, 7, RangedWeapon.BOW, 32, true, 6, 1),
                noEffects());
    }

    private static StatusEffects noEffects() {
        return new StatusEffects(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static TerrainState openTerrain() {
        return new TerrainState(
                false, true, 4, 2, true, true,
                false, true, 8, true, true, false);
    }

    private static ThreatGroup threat(
            ThreatType type,
            int count,
            double distance,
            boolean lineOfSight,
            boolean targetingSelf,
            AttackPhase phase) {
        return new ThreatGroup(type, count, distance, lineOfSight,
                targetingSelf, phase, 1.0);
    }

    private static boolean hasVeto(
            CombatDecision decision,
            VetoScope scope,
            VetoCode code) {
        return decision.hardVetoes().stream()
                .anyMatch(veto -> veto.scope() == scope && veto.code() == code);
    }
}
