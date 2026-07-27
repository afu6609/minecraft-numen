package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Consumer;

/** Read the complete carried state of any online human player. */
public final class GetPlayerStatusTool implements NumenTool {

    @Override
    public String name() {
        return "get_player_status";
    }

    @Override
    public String description() {
        return "Read any ONLINE human player's complete server-side status by name or UUID: game mode, "
                + "HP, armor, hunger, XP, effects, position, dimension, biome, equipment, and full carried "
                + "inventory with slot sections. Use the playerName from a server chat event when responding "
                + "to that speaker. This works even when their vanilla/mobile client has no Numen mod.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("player", "Online human player's exact name or UUID.")
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
        reply.accept(PlayerPerception.status(player).toString());
    }
}
