package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.control.BodyControlClass;
import com.dwinovo.numen.task.control.BodyControlPolicies;
import com.dwinovo.numen.task.navigation.BodyNavigationPort;
import com.dwinovo.numen.task.navigation.BodyNavigationPorts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A long-lived follow task. Unlike {@code goto}, arrival is not terminal:
 * the navigator stays attached to the live player's position and resumes as
 * soon as that player moves outside the requested radius.
 */
public final class FollowPlayerCompanionTask
        extends AbstractCompanionTask<FollowPlayerTaskRecord> {

    private static final double FOLLOW_SPEED = 1.2;
    private static final int RETRY_DELAY_TICKS = 20;
    private static final int MAX_STATIONARY_FAILURES = 3;
    private static final double RESET_FAILURES_AFTER_TARGET_MOVES_SQR = 16.0;
    private static final String LLM_CONTROL_ACTOR = "numen-chain:llm";

    /**
     * Backend choice is permanent for this task. Only an initial UNSUPPORTED
     * response is allowed to select legacy PlayerNav; an accepted provider
     * operation can never fall through to the second body writer.
     */
    private enum NavigationBackend {
        UNDECIDED,
        PORT,
        LEGACY
    }

    private int navigationFailures;
    private long retryAtGameTime;
    private BlockPos targetAtLastFailure;
    private String endReason = "follow ended";
    private NavigationBackend backend = NavigationBackend.UNDECIDED;
    private BodyNavigationPort.Operation portOperation;

    public FollowPlayerCompanionTask(NumenPlayer player, FollowPlayerTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        ServerPlayer live = target();
        if (live == null
                || !live.isAlive()
                || !live.level().dimension().equals(player.level().dimension())) {
            return;
        }
        selectInitialBackend();
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;

        ServerPlayer target = target();
        if (target == null || !target.isAlive()) {
            endReason = r.targetName + " went offline; stopped following";
            return TaskState.SUCCESS;
        }
        if (!target.level().dimension().equals(player.level().dimension())) {
            endReason = r.targetName + " changed dimension; stopped following";
            return TaskState.SUCCESS;
        }

        if (backend == NavigationBackend.UNDECIDED
                && !selectInitialBackend()) {
            return TaskState.FAILED;
        }
        return backend == NavigationBackend.PORT
                ? tickPort(target)
                : tickLegacy(target);
    }

    /**
     * Make the sole backend decision. Rejected means a real task failure;
     * UNSUPPORTED is the only result that is safe to route to PlayerNav.
     */
    private boolean selectInitialBackend() {
        BodyNavigationPort.StartResult started = startPortAttempt();
        return switch (started.disposition()) {
            case ACCEPTED -> {
                backend = NavigationBackend.PORT;
                portOperation = started.operation();
                yield true;
            }
            case UNSUPPORTED -> {
                backend = NavigationBackend.LEGACY;
                yield true;
            }
            case REJECTED -> {
                backend = NavigationBackend.PORT;
                fail(portStartFailure(started), FailureType.UNKNOWN);
                yield false;
            }
        };
    }

    /** Existing PlayerNav behavior, kept byte-for-byte equivalent in its branch. */
    private TaskState tickLegacy(ServerPlayer target) {
        if (withinDistance(target)) {
            navigationFailures = 0;
            InputDriver.halt(player);
            InputDriver.lookAt(player, target.getEyePosition());
            return TaskState.RUNNING;
        }
        if (player.level().getGameTime() < retryAtGameTime) {
            InputDriver.halt(player);
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = PlayerNav.followEntity(
                    player,
                    this::target,
                    r.distance,
                    FOLLOW_SPEED,
                    () -> {
                        ServerPlayer live = target();
                        return live != null
                                && live.level().dimension().equals(player.level().dimension())
                                && withinDistance(live);
                    });
        }

        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                navigationFailures = 0;
                InputDriver.halt(player);
                InputDriver.lookAt(player, target.getEyePosition());
                yield TaskState.RUNNING;
            }
            case FAILED -> {
                String reason = nav.failReason();
                stopNav();
                noteFailure(target.blockPosition());
                if (navigationFailures >= MAX_STATIONARY_FAILURES) {
                    fail("could not keep following " + r.targetName + ": " + reason,
                            FailureType.NO_PATH);
                    yield TaskState.FAILED;
                }
                retryAtGameTime = player.level().getGameTime() + RETRY_DELAY_TICKS;
                yield TaskState.RUNNING;
            }
        };
    }

    /**
     * Poll the provider-owned follow operation. Once this branch is selected it
     * never emits InputDriver halt/look writes; HOLDING is an active provider
     * state, not a cue for the task to drive the body itself.
     */
    private TaskState tickPort(ServerPlayer target) {
        long now = player.level().getGameTime();
        if (portOperation == null) {
            if (now < retryAtGameTime) return TaskState.RUNNING;

            BodyNavigationPort.StartResult restarted = startPortAttempt();
            if (restarted.disposition()
                    != BodyNavigationPort.StartDisposition.ACCEPTED) {
                fail(
                        "could not restart delegated follow for "
                                + r.targetName + ": "
                                + startMessage(restarted),
                        FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            portOperation = restarted.operation();
        }

        BodyNavigationPort.Snapshot snapshot;
        try {
            snapshot = portOperation.snapshot();
        } catch (RuntimeException failure) {
            return handlePortFailure(
                    target,
                    "navigation provider status failed: "
                            + safeMessage(failure),
                    FailureType.UNKNOWN,
                    false);
        }

        return switch (snapshot.state()) {
            case PLANNING, MOVING -> TaskState.RUNNING;
            case HOLDING -> {
                navigationFailures = 0;
                yield TaskState.RUNNING;
            }
            case ARRIVED -> {
                // Follow providers should report HOLDING rather than terminal
                // ARRIVED. Tolerate it while the target remains inside radius;
                // if the target moves away, restart the sticky PORT backend.
                if (withinDistance(target)) {
                    navigationFailures = 0;
                    yield TaskState.RUNNING;
                }
                yield handlePortFailure(
                        target,
                        "follow provider ended after arrival instead of "
                                + "remaining attached to the moving target",
                        FailureType.UNKNOWN,
                        true);
            }
            case FAILED -> {
                BodyNavigationPort.FailureKind failure = snapshot.failure();
                yield handlePortFailure(
                        target,
                        snapshotMessage(
                                snapshot,
                                "delegated navigation failed"),
                        mapFailure(failure),
                        retryablePortFailure(failure));
            }
            case CANCELLED -> {
                cancelPortOperation("provider cancelled follow");
                yield TaskState.CANCELLED;
            }
        };
    }

    /**
     * Preserve the old 20-tick retry and three stationary failures policy for
     * recoverable geometry failures. Prerequisite, ownership, target, and
     * provider-internal failures return to the model immediately. A retry
     * remains on the PORT backend; it can never instantiate PlayerNav.
     */
    private TaskState handlePortFailure(
            ServerPlayer target,
            String reason,
            FailureType type,
            boolean retryable) {
        if (!cancelPortOperation("delegated follow attempt failed")) {
            fail(
                    "could not safely stop the failed delegated follow for "
                            + r.targetName
                            + "; refusing to start a second operation: "
                            + reason,
                    FailureType.INTERRUPTED);
            return TaskState.FAILED;
        }
        if (!retryable) {
            fail(
                    "could not keep following " + r.targetName + ": " + reason,
                    type);
            return TaskState.FAILED;
        }
        noteFailure(target.blockPosition());
        if (navigationFailures >= MAX_STATIONARY_FAILURES) {
            fail(
                    "could not keep following " + r.targetName + ": " + reason,
                    type);
            return TaskState.FAILED;
        }
        retryAtGameTime =
                player.level().getGameTime() + RETRY_DELAY_TICKS;
        return TaskState.RUNNING;
    }

    private static boolean retryablePortFailure(
            BodyNavigationPort.FailureKind failure) {
        return switch (failure) {
            case NO_PATH, BOXED_IN, OUT_OF_RANGE, HAZARD -> true;
            case NONE, TARGET_LOST, CONTROL_LOST, NO_MATERIAL,
                    CAPABILITY_MISMATCH, INTERNAL -> false;
        };
    }

    /**
     * Bind the provider to the exact LLM task session already acquired by
     * CompanionBrain. The provider is a motor beneath this lease, never a
     * second body-control actor.
     */
    private BodyNavigationPort.StartResult startPortAttempt() {
        var control = BodyControlPolicies.requestFor(
                player,
                LLM_CONTROL_ACTOR,
                r.publicId(),
                BodyControlClass.DIRECTED_ACTION,
                BodyControlClass.DIRECTED_ACTION.defaultPriority());
        if (!BodyControlPolicies.owns(control)) {
            return BodyNavigationPort.StartResult.rejected(
                    "the exact Numen task session does not own body control");
        }

        try {
            return BodyNavigationPorts.startFollow(
                    new BodyNavigationPort.FollowRequest(
                            new BodyNavigationPort.ControlBinding(control),
                            r.targetUuid,
                            r.distance));
        } catch (RuntimeException failure) {
            return BodyNavigationPort.StartResult.rejected(
                    "navigation provider start failed: "
                            + safeMessage(failure));
        }
    }

    private String portStartFailure(
            BodyNavigationPort.StartResult result) {
        return "could not start delegated follow for "
                + r.targetName + ": " + startMessage(result);
    }

    private static String startMessage(
            BodyNavigationPort.StartResult result) {
        if (result == null) return "provider returned no result";
        return result.message() == null || result.message().isBlank()
                ? result.disposition().name().toLowerCase()
                : result.message();
    }

    private static String snapshotMessage(
            BodyNavigationPort.Snapshot snapshot,
            String fallback) {
        return snapshot.message() == null || snapshot.message().isBlank()
                ? fallback
                : snapshot.message();
    }

    private static FailureType mapFailure(
            BodyNavigationPort.FailureKind failure) {
        return switch (failure) {
            case NO_PATH -> FailureType.NO_PATH;
            case BOXED_IN -> FailureType.BOXED_IN;
            case TARGET_LOST -> FailureType.TARGET_LOST;
            case OUT_OF_RANGE -> FailureType.OUT_OF_REACH;
            case CONTROL_LOST -> FailureType.INTERRUPTED;
            case NO_MATERIAL -> FailureType.NO_MATERIAL;
            case HAZARD -> FailureType.HAZARD;
            case CAPABILITY_MISMATCH -> FailureType.UNSUPPORTED;
            case NONE, INTERNAL -> FailureType.UNKNOWN;
        };
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /**
     * Clear the handle only after the provider confirms cancellation. Keeping
     * it after an exception prevents the retry loop from starting a second
     * writer under the same exact control session and lets terminal cleanup
     * retry the idempotent operation.
     */
    private boolean cancelPortOperation(String reason) {
        BodyNavigationPort.Operation operation = portOperation;
        if (operation == null) return true;
        try {
            operation.cancel(reason);
            portOperation = null;
            return true;
        } catch (RuntimeException failure) {
            com.dwinovo.numen.Constants.LOG.warn(
                    "[numen-task] delegated follow cancel failed for {}: {}",
                    r.targetName,
                    safeMessage(failure));
            return false;
        }
    }

    private ServerPlayer target() {
        MinecraftServer server = player.level().getServer();
        if (server == null) return null;
        ServerPlayer target = server.getPlayerList().getPlayer(r.targetUuid);
        return target instanceof NumenPlayer ? null : target;
    }

    private boolean withinDistance(ServerPlayer target) {
        return player.distanceToSqr(target) <= r.distance * r.distance;
    }

    private void noteFailure(BlockPos currentTarget) {
        if (targetAtLastFailure == null
                || currentTarget.distSqr(targetAtLastFailure)
                        > RESET_FAILURES_AFTER_TARGET_MOVES_SQR) {
            navigationFailures = 1;
        } else {
            navigationFailures++;
        }
        targetAtLastFailure = currentTarget.immutable();
    }

    @Override
    protected void cleanup() {
        if (backend == NavigationBackend.PORT) {
            cancelPortOperation("follow task finalized");
        } else {
            // Preserve the legacy cleanup exactly. Accepted PORT operations
            // own neutralization and must not be raced by raw task writes.
            InputDriver.halt(player);
        }
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("target_uuid", r.targetUuid.toString());
        data.put("target_name", r.targetName);
        data.put("follow_distance", r.distance);
        data.put("final_x", player.getX());
        data.put("final_y", player.getY());
        data.put("final_z", player.getZ());
        return data;
    }

    @Override
    protected String successMessage() {
        return endReason;
    }

    @Override
    protected String timeoutMessage() {
        return "follow session reached its 24-hour safety limit";
    }

    @Override
    protected String cancelledMessage() {
        return "stopped following " + r.targetName;
    }
}
