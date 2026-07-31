package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;

/**
 * Small state machine for the safety contract around {@code mine}.
 *
 * <p>The physical task owns navigation; this class owns the terminal truth:
 * mining success/failure is held until the body either gets back to its entry
 * anchor or produces an explicit {@link FailureType#TRAPPED} outcome. Keeping
 * that distinction outside model-facing prose prevents a successful gather
 * from being reported while the body is still stranded in its excavation.
 */
final class MineReturnGuard {

    enum Phase {
        WORKING,
        RETURNING,
        RETURNED,
        TRAPPED
    }

    record Completion(TaskState state, String message, FailureType failureType) {}

    private static final long MIN_RETURN_TICKS = 30L * 20L;
    private static final long RETURN_TICKS_PER_BLOCK = 20L;
    private static final long MAX_RETURN_TICKS = 5L * 60L * 20L;

    private final BlockPos entry;
    private Phase phase = Phase.WORKING;
    private TaskState requestedState;
    private String requestedMessage;
    private FailureType requestedFailure = FailureType.UNKNOWN;
    private boolean returnAttempted;
    private Completion completion;

    MineReturnGuard(BlockPos entry) {
        if (entry == null) {
            throw new IllegalArgumentException("mining entry anchor is required");
        }
        this.entry = entry.immutable();
    }

    BlockPos entry() {
        return entry;
    }

    Phase phase() {
        return phase;
    }

    boolean returning() {
        return phase == Phase.RETURNING;
    }

    Completion completion() {
        return completion;
    }

    /**
     * Hold one requested terminal until its return condition is resolved.
     * Repeated calls are ignored so a later scan/nav observation cannot replace
     * the original mining outcome.
     */
    void request(
            TaskState state,
            String message,
            FailureType failureType,
            boolean alreadyAtEntry) {
        if (phase != Phase.WORKING) {
            return;
        }
        if (state == null || !state.isTerminal() || state == TaskState.CANCELLED) {
            throw new IllegalArgumentException(
                    "mining return guard needs a non-cancelled terminal state");
        }
        requestedState = state;
        requestedMessage =
                message == null || message.isBlank() ? "mining ended" : message.trim();
        requestedFailure =
                failureType == null ? FailureType.UNKNOWN : failureType;
        returnAttempted = !alreadyAtEntry;
        if (alreadyAtEntry) {
            phase = Phase.RETURNED;
            completion = preservedCompletion(
                    requestedMessage + "; remained at the verified mining entry "
                            + shortPos(entry));
        } else {
            phase = Phase.RETURNING;
        }
    }

    void markReturned() {
        if (phase != Phase.RETURNING) {
            return;
        }
        phase = Phase.RETURNED;
        completion = preservedCompletion(
                requestedMessage + "; returned to mining entry " + shortPos(entry)
                        + " by a verified land route");
    }

    void markTrapped(BlockPos finalPosition, String reason) {
        if (phase != Phase.RETURNING) {
            return;
        }
        BlockPos end = finalPosition == null ? entry : finalPosition.immutable();
        String detail =
                reason == null || reason.isBlank() ? "return route failed" : reason.trim();
        phase = Phase.TRAPPED;
        completion = new Completion(
                TaskState.FAILED,
                "TRAPPED: no safe land route back to mining entry " + shortPos(entry)
                        + "; body remains at " + shortPos(end)
                        + ". Return navigation: " + detail
                        + ". Original mining outcome: " + requestedMessage,
                FailureType.TRAPPED);
    }

    String resultState() {
        return switch (phase) {
            case WORKING -> "working";
            case RETURNING -> "returning";
            case RETURNED -> returnAttempted ? "returned" : "not_needed";
            case TRAPPED -> "trapped";
        };
    }

    /**
     * Independent return allowance. The mining deadline is treated as the work
     * deadline; this bounded allowance is reserved after it so timeout cannot
     * finalize the record before the body has had a chance to walk back.
     */
    static long returnBudgetTicks(BlockPos from, BlockPos entry) {
        if (from == null || entry == null) {
            return MIN_RETURN_TICKS;
        }
        double distance = Math.sqrt(from.distSqr(entry));
        long scaled = MIN_RETURN_TICKS
                + (long) Math.ceil(distance * RETURN_TICKS_PER_BLOCK);
        return Math.min(MAX_RETURN_TICKS, Math.max(MIN_RETURN_TICKS, scaled));
    }

    private Completion preservedCompletion(String message) {
        return new Completion(requestedState, message, requestedFailure);
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
