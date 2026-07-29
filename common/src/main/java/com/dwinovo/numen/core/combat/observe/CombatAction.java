package com.dwinovo.numen.core.combat.observe;

/**
 * Loader-neutral combat vocabulary exposed to the model and consumed by a
 * future low-latency policy runtime.
 */
public enum CombatAction {
    IDLE,
    APPROACH,
    MELEE_WINDUP,
    RANGED_CHARGE,
    PROJECTILE_RELEASE,
    AOE_CHARGE,
    DASH,
    SUMMON,
    TELEPORT,
    GUARD,
    RECOVER,
    RETREAT,
    UNKNOWN
}
