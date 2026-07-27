package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
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

    private int navigationFailures;
    private long retryAtGameTime;
    private BlockPos targetAtLastFailure;
    private String endReason = "follow ended";

    public FollowPlayerCompanionTask(NumenPlayer player, FollowPlayerTaskRecord record) {
        super(player, record);
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
        InputDriver.halt(player);
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
