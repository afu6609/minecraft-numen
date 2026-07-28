package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.event.CompanionEventBus;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Internal telemetry drain used by the dedicated-server brain harness. */
public final class PollCompanionEventsTool implements NumenTool {

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "poll_companion_events";
    }

    @Override
    public String description() {
        return "Internal server-brain transport. Drain queued authoritative body events such as "
                + "damage, local defense, death, and body availability for this companion. "
                + "Gameplay agents should not call this tool.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("limit", "Maximum events to return, from 1 to 64.", 1, 64)
                .build();
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer companion,
            Consumer<String> reply) {
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 16;
        reply.accept(GSON.toJson(CompanionEventBus.poll(companion.getUUID(), limit)));
    }
}
