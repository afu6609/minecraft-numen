package com.dwinovo.numen.core.combat.observe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Pure bounded memory for server-proven projectile releases.
 *
 * <p>A release is reported only when a young projectile with the observed
 * entity as its authoritative owner appears for the first time. Entity
 * scanning itself remains in the adapter; this class owns no world objects.</p>
 */
final class RangedObservationTracker {

    static final int MAX_SHOOTERS = 128;
    static final int MAX_PROJECTILES_PER_SAMPLE = 32;
    static final int MAX_NEW_PROJECTILE_AGE_TICKS = 2;
    static final int MAX_TRACKED_PROJECTILE_AGE_TICKS = 8;
    static final int RECOVERY_TICKS = 6;

    private static final int MAX_SEEN_PER_SHOOTER = 64;
    private static final int SEEN_TTL_TICKS = 80;

    private final LinkedHashMap<UUID, ShooterState> shooters =
            new LinkedHashMap<>(16, 0.75F, true);

    synchronized Window observe(
            UUID shooterUuid,
            long serverTick,
            List<ProjectileSample> samples) {
        if (shooterUuid == null) {
            throw new IllegalArgumentException("shooter uuid is required");
        }
        ShooterState state =
                shooters.computeIfAbsent(shooterUuid, ignored -> new ShooterState());
        while (shooters.size() > MAX_SHOOTERS) {
            shooters.remove(shooters.keySet().iterator().next());
        }

        boolean newlyReleased = false;
        int examined = 0;
        if (samples != null) {
            for (ProjectileSample sample : samples) {
                if (sample == null) {
                    continue;
                }
                if (examined >= MAX_PROJECTILES_PER_SAMPLE) {
                    break;
                }
                examined++;
                if (sample.projectileUuid() == null
                        || sample.ageTicks() < 0
                        || sample.ageTicks() > MAX_TRACKED_PROJECTILE_AGE_TICKS) {
                    continue;
                }
                boolean firstSeen =
                        !state.seenProjectiles.containsKey(sample.projectileUuid());
                state.seenProjectiles.put(sample.projectileUuid(), serverTick);
                if (firstSeen
                        && sample.ageTicks() <= MAX_NEW_PROJECTILE_AGE_TICKS) {
                    newlyReleased = true;
                }
            }
        }
        state.seenProjectiles.entrySet().removeIf(
                entry -> serverTick >= entry.getValue()
                        && serverTick - entry.getValue() > SEEN_TTL_TICKS);
        while (state.seenProjectiles.size() > MAX_SEEN_PER_SHOOTER) {
            state.seenProjectiles.remove(
                    state.seenProjectiles.keySet().iterator().next());
        }

        if (newlyReleased) {
            state.lastReleaseTick = serverTick;
        }
        long age = state.lastReleaseTick == Long.MIN_VALUE
                ? Long.MAX_VALUE
                : serverTick - state.lastReleaseTick;
        boolean release = age == 0;
        boolean recovering = age > 0 && age <= RECOVERY_TICKS;
        int recoveryRemaining = recovering
                ? (int) (RECOVERY_TICKS - age + 1)
                : 0;
        return new Window(
                release,
                recovering,
                recoveryRemaining,
                Math.min(examined, MAX_PROJECTILES_PER_SAMPLE));
    }

    record ProjectileSample(UUID projectileUuid, int ageTicks) {
    }

    record Window(
            boolean projectileRelease,
            boolean recovering,
            int recoveryTicksRemaining,
            int examinedProjectiles) {
    }

    private static final class ShooterState {
        final LinkedHashMap<UUID, Long> seenProjectiles =
                new LinkedHashMap<>(16, 0.75F, true);
        long lastReleaseTick = Long.MIN_VALUE;
    }
}
