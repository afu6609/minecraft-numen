package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Consumer;

/** Dense near-field terrain perception centred on an online human player. */
public final class LookAroundPlayerTool implements NumenTool {

    private static final int DEFAULT_RADIUS = 8;
    private static final int MIN_RADIUS = 4;
    private static final int MAX_RADIUS = 16;

    @Override
    public String name() {
        return "look_around_player";
    }

    @Override
    public String description() {
        return "Read a dense top-down terrain grid centred on any ONLINE human player, even when they "
                + "are far away or in another dimension. The output uses the same movement/hazard legend "
                + "as look_around and marks the target player with @. Use this with get_player_status to "
                + "understand the speaker and the blocks immediately around them. Optional radius 4-16.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("player", "Online human player's exact name or UUID.")
                .optionalInteger(
                        "radius",
                        "Half-width of the square terrain view (4-16, default 8).",
                        MIN_RADIUS,
                        MAX_RADIUS)
                .build();
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer self,
            Consumer<String> reply) {
        String selector = args.has("player") ? args.get("player").getAsString() : "";
        ServerPlayer player = PlayerPerception.resolveHuman(self, selector);
        if (player == null) {
            reply.accept(TaskResult.fail(
                    "online human player not found: " + selector).toJson());
            return;
        }
        int radius = args.has("radius")
                ? net.minecraft.util.Mth.clamp(
                        args.get("radius").getAsInt(), MIN_RADIUS, MAX_RADIUS)
                : DEFAULT_RADIUS;
        reply.accept(LookAroundTool.render(player, radius));
    }
}
