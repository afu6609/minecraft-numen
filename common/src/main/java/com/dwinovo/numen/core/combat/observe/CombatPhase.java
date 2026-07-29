package com.dwinovo.numen.core.combat.observe;

/** A coarse phase shared by vanilla and modded combat adapters. */
public enum CombatPhase {
    READY,
    AIMING,
    WINDUP,
    EXECUTING,
    RECOVERY,
    OBSERVED,
    UNKNOWN
}
