package com.dwinovo.numen.core.combat.policy;

/**
 * The complete executable vocabulary of a model-authored combat policy.
 *
 * <p>Policies deliberately cannot invoke tools, commands, scripts, block
 * mutation, or arbitrary server operations. A runtime may only map these
 * bounded verbs to its own trusted implementations.</p>
 */
public enum CombatPolicyAction {
    APPROACH,
    MELEE_STRIKE,
    RETREAT,
    GUARD,
    HOLD,
    SEEK_COVER,
    SEEK_SHELTER,
    RANGED_SHOT
}
