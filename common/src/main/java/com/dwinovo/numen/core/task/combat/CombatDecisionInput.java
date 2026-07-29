package com.dwinovo.numen.core.task.combat;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, Minecraft-free snapshot consumed by {@link CombatDecisionEngine}.
 *
 * <p>The live integration is deliberately responsible only for translating
 * authoritative server state into this DTO. The decision core does not inspect
 * entities, inventories, paths, or blocks itself, which keeps it deterministic
 * and cheap enough to test without launching Minecraft.
 */
public record CombatDecisionInput(
        SelfState self,
        List<ThreatGroup> threats,
        TerrainState terrain,
        EngagementDirective directive) {

    public CombatDecisionInput {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(threats, "threats");
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(directive, "directive");
        threats = List.copyOf(threats);
        if (threats.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("threats cannot contain null");
        }
    }

    /**
     * Whether nearby but currently passive hostiles may be engaged.
     *
     * <p>Even {@link #EXPLICIT_COMBAT} never overrides hard survival vetoes or
     * the permanent protected-target rule for players. Iron golems remain a
     * special heavy target: never autonomous prey, but self-defence or an
     * explicit order may use a supervised ranged tactic.
     */
    public enum EngagementDirective {
        /** React only to an attacker, an entity targeting the body, or an imminent attack. */
        SURVIVAL_ONLY,
        /** Proactively clear ordinary hostile mobs near a guarded home or player. */
        GUARD_AREA,
        /** A model/user explicitly requested this combat encounter. */
        EXPLICIT_COMBAT
    }

    public record SelfState(
            Vitals vitals,
            Loadout loadout,
            StatusEffects effects) {

        public SelfState {
            Objects.requireNonNull(vitals, "vitals");
            Objects.requireNonNull(loadout, "loadout");
            Objects.requireNonNull(effects, "effects");
        }
    }

    /** Health, hunger, and equipped defensive attributes. */
    public record Vitals(
            double health,
            double maxHealth,
            double absorption,
            int hunger,
            double armor,
            double armorToughness) {

        public Vitals {
            requireFiniteNonNegative(health, "health");
            if (!Double.isFinite(maxHealth) || maxHealth <= 0.0) {
                throw new IllegalArgumentException("maxHealth must be finite and positive");
            }
            requireFiniteNonNegative(absorption, "absorption");
            requireFiniteNonNegative(armor, "armor");
            requireFiniteNonNegative(armorToughness, "armorToughness");
            if (hunger < 0 || hunger > 20) {
                throw new IllegalArgumentException("hunger must be in [0, 20]");
            }
        }

        public double effectiveHealthRatio() {
            return Math.min(1.5, (health + absorption) / maxHealth);
        }

        public boolean canSprint() {
            return hunger > 6;
        }
    }

    /** Capabilities available in equipped slots or the inventory. */
    public record Loadout(
            boolean hasMeleeWeapon,
            double meleeAttackDamage,
            RangedWeapon rangedWeapon,
            int ammunition,
            boolean hasShield,
            int foodItems,
            int healingItems) {

        public Loadout {
            requireFiniteNonNegative(meleeAttackDamage, "meleeAttackDamage");
            Objects.requireNonNull(rangedWeapon, "rangedWeapon");
            if (ammunition < 0 || foodItems < 0 || healingItems < 0) {
                throw new IllegalArgumentException(
                        "ammunition, foodItems, and healingItems must be non-negative");
            }
        }

        public boolean canUseRangedWeapon() {
            return rangedWeapon != RangedWeapon.NONE && ammunition > 0;
        }
    }

    public enum RangedWeapon {
        NONE,
        BOW,
        CROSSBOW,
        OTHER
    }

    /**
     * Key combat effects represented as vanilla-style levels: zero means absent,
     * one is the first level shown to players, and so on.
     */
    public record StatusEffects(
            int resistanceLevel,
            int strengthLevel,
            int speedLevel,
            int regenerationLevel,
            int weaknessLevel,
            int slownessLevel,
            int poisonLevel,
            int witherLevel,
            int blindnessLevel) {

        public StatusEffects {
            if (resistanceLevel < 0 || strengthLevel < 0 || speedLevel < 0
                    || regenerationLevel < 0 || weaknessLevel < 0
                    || slownessLevel < 0 || poisonLevel < 0
                    || witherLevel < 0 || blindnessLevel < 0) {
                throw new IllegalArgumentException("effect levels must be non-negative");
            }
        }

        public boolean hasDangerousDamageOverTime() {
            return poisonLevel > 0 || witherLevel > 0;
        }

        public boolean hasMeleeImpairment() {
            return weaknessLevel > 0 || slownessLevel >= 2
                    || blindnessLevel > 0 || hasDangerousDamageOverTime();
        }
    }

    /**
     * One homogeneous group of observed entities. Mixed encounters are expressed
     * as several groups so a charging creeper is not hidden by two nearby zombies.
     */
    public record ThreatGroup(
            ThreatType type,
            int count,
            double nearestDistance,
            boolean hasLineOfSight,
            boolean targetingSelf,
            AttackPhase phase,
            double primaryHealthRatio,
            double primaryMaxHealth,
            double primaryAttackDamage,
            boolean rangedCapable,
            boolean airborne,
            boolean bossLike) {

        public ThreatGroup {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(phase, "phase");
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
            requireFiniteNonNegative(nearestDistance, "nearestDistance");
            requireFiniteNonNegative(primaryHealthRatio, "primaryHealthRatio");
            requireFiniteNonNegative(primaryMaxHealth, "primaryMaxHealth");
            requireFiniteNonNegative(primaryAttackDamage, "primaryAttackDamage");
        }

        /** Compatibility constructor for pure callers that do not expose capability facts. */
        public ThreatGroup(
                ThreatType type,
                int count,
                double nearestDistance,
                boolean hasLineOfSight,
                boolean targetingSelf,
                AttackPhase phase,
                double primaryHealthRatio) {
            this(
                    type,
                    count,
                    nearestDistance,
                    hasLineOfSight,
                    targetingSelf,
                    phase,
                    primaryHealthRatio,
                    20.0,
                    0.0,
                    type == ThreatType.SKELETON || type == ThreatType.PHANTOM,
                    type == ThreatType.PHANTOM,
                    false);
        }

        public boolean isActivelyDangerous() {
            return targetingSelf || phase.isAttackCommitment();
        }
    }

    public enum ThreatType {
        CREEPER,
        SKELETON,
        SPIDER,
        PHANTOM,
        ZOMBIE,
        PLAYER,
        IRON_GOLEM,
        OTHER_HOSTILE
    }

    /** A normalized action phase supplied by vanilla/mod-specific observers. */
    public enum AttackPhase {
        IDLE,
        APPROACHING,
        MELEE_WINDUP,
        RANGED_CHARGE,
        PROJECTILE_RELEASED,
        EXPLOSION_CHARGE,
        DIVING,
        RECOVERING,
        UNKNOWN;

        public boolean isAttackCommitment() {
            return this == MELEE_WINDUP
                    || this == RANGED_CHARGE
                    || this == PROJECTILE_RELEASED
                    || this == EXPLOSION_CHARGE
                    || this == DIVING;
        }
    }

    /**
     * Server-derived terrain facts. "Trusted shelter" means a known structure,
     * but it is usable only when both enclosure and spawn-safety checks pass.
     * This prevents a remembered blueprint with a missing wall, open door, or
     * unsafe interior light level from being treated as safety.
     */
    public record TerrainState(
            boolean overheadCoverHere,
            boolean hardCoverReachable,
            double hardCoverDistance,
            int safeEscapeDirections,
            boolean kitingSpace,
            boolean chokePointAvailable,
            boolean insideTrustedShelter,
            boolean trustedShelterReachable,
            double trustedShelterDistance,
            boolean trustedShelterEnclosed,
            boolean trustedShelterSpawnSafe,
            boolean shelterEntranceSecured) {

        public TerrainState {
            requireFiniteNonNegative(hardCoverDistance, "hardCoverDistance");
            requireFiniteNonNegative(trustedShelterDistance, "trustedShelterDistance");
            if (safeEscapeDirections < 0) {
                throw new IllegalArgumentException("safeEscapeDirections must be non-negative");
            }
        }

        public boolean hasEscapeRoute() {
            return safeEscapeDirections > 0;
        }

        public boolean hasVerifiedShelter() {
            return trustedShelterEnclosed && trustedShelterSpawnSafe;
        }

        public boolean canReachVerifiedShelter() {
            return trustedShelterReachable && hasVerifiedShelter();
        }

        public boolean isSecureInsideShelter() {
            return insideTrustedShelter && hasVerifiedShelter() && shelterEntranceSecured;
        }
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
