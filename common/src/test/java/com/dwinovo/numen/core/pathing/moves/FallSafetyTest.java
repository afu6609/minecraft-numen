package com.dwinovo.numen.core.pathing.moves;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FallSafetyTest {

    @Test
    void healthyCompanionMayLeaveASixBlockRoof() {
        assertEquals(6, CalculationContext.healthAwareFallHeight(3, 6, 20.0f, 12.0f));
    }

    @Test
    void lowHealthCompanionKeepsTheNoDamageBaseline() {
        assertEquals(3, CalculationContext.healthAwareFallHeight(3, 6, 12.0f, 12.0f));
    }

    @Test
    void intermediateHealthOnlySpendsItsActualReserve() {
        assertEquals(5, CalculationContext.healthAwareFallHeight(3, 6, 14.0f, 12.0f));
    }
}
