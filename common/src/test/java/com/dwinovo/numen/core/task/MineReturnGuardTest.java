package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineReturnGuardTest {

    @Test
    void miningOutcomeIsHeldUntilReturnAndThenPreserved() {
        MineReturnGuard guard = new MineReturnGuard(new BlockPos(10, 64, -4));

        guard.request(
                TaskState.FAILED,
                "ore field exhausted",
                FailureType.MINED_OUT,
                false);

        assertTrue(guard.returning());
        assertEquals("returning", guard.resultState());
        assertEquals(null, guard.completion());

        guard.markReturned();
        MineReturnGuard.Completion completion = guard.completion();
        assertNotNull(completion);
        assertEquals(TaskState.FAILED, completion.state());
        assertEquals(FailureType.MINED_OUT, completion.failureType());
        assertEquals("returned", guard.resultState());
        assertTrue(completion.message().contains("verified land route"));
        assertTrue(completion.message().contains("10,64,-4"));
    }

    @Test
    void failedReturnOverridesSuccessfulGatherWithExplicitTrappedResult() {
        MineReturnGuard guard = new MineReturnGuard(new BlockPos(0, 70, 0));
        guard.request(
                TaskState.SUCCESS,
                "gathered 8/8 iron ore",
                FailureType.UNKNOWN,
                false);

        guard.markTrapped(new BlockPos(7, 42, 9), "no path survived");

        MineReturnGuard.Completion completion = guard.completion();
        assertEquals(TaskState.FAILED, completion.state());
        assertEquals(FailureType.TRAPPED, completion.failureType());
        assertEquals("trapped", guard.resultState());
        assertTrue(completion.message().startsWith("TRAPPED:"));
        assertTrue(completion.message().contains("0,70,0"));
        assertTrue(completion.message().contains("7,42,9"));
        assertTrue(completion.message().contains("gathered 8/8 iron ore"));
    }

    @Test
    void noReturnIsClaimedWhenAlreadyAtTheEntry() {
        MineReturnGuard guard = new MineReturnGuard(new BlockPos(2, 65, 3));
        guard.request(
                TaskState.SUCCESS,
                "gathered nearby logs",
                FailureType.UNKNOWN,
                true);

        assertFalse(guard.returning());
        assertEquals("not_needed", guard.resultState());
        assertEquals(TaskState.SUCCESS, guard.completion().state());
        assertTrue(guard.completion().message().contains("remained at"));
    }

    @Test
    void returnBudgetIsBoundedAndScalesWithDistance() {
        BlockPos entry = BlockPos.ZERO;
        long near = MineReturnGuard.returnBudgetTicks(entry, entry);
        long farther = MineReturnGuard.returnBudgetTicks(
                new BlockPos(20, 0, 0), entry);
        long capped = MineReturnGuard.returnBudgetTicks(
                new BlockPos(10_000, 0, 0), entry);

        assertEquals(30L * 20L, near);
        assertTrue(farther > near);
        assertEquals(5L * 60L * 20L, capped);
    }
}
