package com.dwinovo.numen.core.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildCompanionTaskTest {

    @Test
    void deterministicBlueprintFailuresReturnToThePlanner() {
        assertEquals(
                FailureType.TARGET_LOST,
                BuildCompanionTask.failureTypeFor("STATE_MISMATCH"));
        assertEquals(
                FailureType.TARGET_LOST,
                BuildCompanionTask.failureTypeFor("FOOTPRINT_BLOCKED"));
    }

    @Test
    void positionalAndTransientFailuresKeepTheirStructuredTypes() {
        assertEquals(
                FailureType.BOXED_IN,
                BuildCompanionTask.failureTypeFor("BLOCKED_BY_SELF"));
        assertEquals(
                FailureType.OUT_OF_REACH,
                BuildCompanionTask.failureTypeFor("OUT_OF_REACH"));
        assertEquals(
                FailureType.ENTITY_BLOCKED,
                BuildCompanionTask.failureTypeFor("BLOCKED_BY_ENTITY"));
    }
}
