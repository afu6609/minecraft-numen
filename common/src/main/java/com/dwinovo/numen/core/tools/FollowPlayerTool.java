package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.dispatchAsync;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.task.FollowPlayerTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Consumer;

/** Start a durable, dynamically re-planned follow session with an online human. */
public final class FollowPlayerTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final double DEFAULT_DISTANCE = 3.0;
    private static final long MAX_SESSION_TICKS = 24L * 60L * 60L * 20L;

    private record Args(String player, Double distance) {}

    @Override
    public String name() {
        return FollowPlayerTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return """
                Continuously follow one ONLINE human player by exact name or UUID. The target is
                re-read while they move; this is not a one-time goto and does not replay their old
                footsteps. Remains active until task_stop, the player logs out/changes dimension,
                navigation repeatedly fails, or the 24-hour safety limit is reached. Background
                task: returns task_id immediately and later emits task_finished.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("player", "Exact online human player name or UUID from the chat event.")
                .nullableNumber("distance", "Following radius in blocks, 2-8; null defaults to 3.")
                .build();
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer companion,
            Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        ServerPlayer target = PlayerPerception.resolveHuman(companion, parsed.player());
        if (target == null) {
            throw new IllegalArgumentException(
                    "player must identify an online human by exact name or UUID");
        }
        double distance = parsed.distance() == null ? DEFAULT_DISTANCE : parsed.distance();
        if (!Double.isFinite(distance) || distance < 2.0 || distance > 8.0) {
            throw new IllegalArgumentException("distance must be between 2 and 8 blocks");
        }
        var context = ctx(toolCallId, companion);
        dispatchAsync(
                companion,
                new FollowPlayerTaskRecord(
                        context.toolCallId(),
                        context.deadline(MAX_SESSION_TICKS),
                        target.getUUID(),
                        target.getGameProfile().getName(),
                        distance),
                reply);
    }
}
