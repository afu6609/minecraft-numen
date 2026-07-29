package com.dwinovo.numen.core.combat.observe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangedObservationTrackerTest {

    @Test
    void reportsOnlyNewYoungOwnedProjectileThenShortRecovery() {
        RangedObservationTracker tracker = new RangedObservationTracker();
        UUID shooter = UUID.randomUUID();
        UUID oldProjectile = UUID.randomUUID();
        UUID newProjectile = UUID.randomUUID();

        var baseline = tracker.observe(
                shooter,
                100,
                List.of(new RangedObservationTracker.ProjectileSample(
                        oldProjectile, 5)));
        assertFalse(baseline.projectileRelease());
        assertFalse(baseline.recovering());

        var release = tracker.observe(
                shooter,
                101,
                List.of(
                        new RangedObservationTracker.ProjectileSample(
                                oldProjectile, 6),
                        new RangedObservationTracker.ProjectileSample(
                                newProjectile, 1)));
        assertTrue(release.projectileRelease());
        assertFalse(release.recovering());

        var duplicateSameTick = tracker.observe(
                shooter,
                101,
                List.of(new RangedObservationTracker.ProjectileSample(
                        newProjectile, 1)));
        assertTrue(duplicateSameTick.projectileRelease());

        var recovery = tracker.observe(shooter, 102, List.of());
        assertFalse(recovery.projectileRelease());
        assertTrue(recovery.recovering());
        assertEquals(6, recovery.recoveryTicksRemaining());

        assertTrue(tracker.observe(shooter, 107, List.of()).recovering());
        assertFalse(tracker.observe(shooter, 108, List.of()).recovering());
    }

    @Test
    void oldOrRepeatedProjectileDoesNotCreateFalseRelease() {
        RangedObservationTracker tracker = new RangedObservationTracker();
        UUID shooter = UUID.randomUUID();
        UUID projectile = UUID.randomUUID();

        assertFalse(tracker.observe(
                shooter,
                10,
                List.of(new RangedObservationTracker.ProjectileSample(
                        projectile, 3))).projectileRelease());
        assertFalse(tracker.observe(
                shooter,
                11,
                List.of(new RangedObservationTracker.ProjectileSample(
                        projectile, 1))).projectileRelease());
    }

    @Test
    void examinesAtMostTheConfiguredSampleBound() {
        RangedObservationTracker tracker = new RangedObservationTracker();
        UUID shooter = UUID.randomUUID();
        List<RangedObservationTracker.ProjectileSample> samples =
                new ArrayList<>();
        for (int index = 0;
             index < RangedObservationTracker.MAX_PROJECTILES_PER_SAMPLE;
             index++) {
            samples.add(new RangedObservationTracker.ProjectileSample(
                    UUID.randomUUID(), 8));
        }
        UUID beyondBound = UUID.randomUUID();
        samples.add(new RangedObservationTracker.ProjectileSample(
                beyondBound, 0));

        var bounded = tracker.observe(shooter, 20, samples);
        assertEquals(
                RangedObservationTracker.MAX_PROJECTILES_PER_SAMPLE,
                bounded.examinedProjectiles());
        assertFalse(bounded.projectileRelease());

        var next = tracker.observe(
                shooter,
                21,
                List.of(new RangedObservationTracker.ProjectileSample(
                        beyondBound, 1)));
        assertTrue(next.projectileRelease());
    }
}
