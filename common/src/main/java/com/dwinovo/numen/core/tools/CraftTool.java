package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.control.BodyControlClass;
import com.dwinovo.numen.task.control.BodyControlPolicies;
import com.dwinovo.numen.task.control.BodyControlPolicy;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): craft an item start-to-finish in one call. */
public final class CraftTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final AtomicLong SESSION_SOURCE = new AtomicLong();
    private static final String CONTROL_ACTOR = "numen-direct:craft";
    private final CraftTools impl = new CraftTools();

    private record Args(String item_id, Integer count) {}

    @Override
    public String name() {
        return "craft";
    }

    @Override
    public String description() {
        return "Craft an item from materials in your inventory — one call does the whole flow: finds "
                + "the recipe, lays the ingredients into a real crafting grid, and takes the result. "
                + "2x2 recipes work anywhere; a 3x3 recipe needs a crafting table within reach "
                + "(~4 blocks) — the result tells you where the nearest one is, or that you should "
                + "place one (a crafting_table is 4 planks, 2x2). Missing materials are reported with "
                + "exact shortfalls — collect or craft those first, then call again. Crafts up to "
                + "`count` of the item and stops early (reported) if materials run out or the "
                + "inventory fills. Only for [crafting] recipes — smelting/stonecutter/smithing still "
                + "go through interact_at + transfer on their station.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to craft, e.g. minecraft:iron_pickaxe.")
                .optionalInteger("count", "How many of the item you want (default 1).", 1, 256)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        String sessionId = "craft-"
                + Long.toUnsignedString(SESSION_SOURCE.incrementAndGet(), 36)
                + (toolCallId == null || toolCallId.isBlank()
                        ? ""
                        : ":" + toolCallId);
        BodyControlPolicy.Decision control = BodyControlPolicies.acquire(
                self,
                CONTROL_ACTOR,
                sessionId,
                BodyControlClass.DIRECTED_ACTION,
                BodyControlClass.DIRECTED_ACTION.defaultPriority());
        if (!control.granted()) {
            reply.accept(TaskResult.fail(control.reason()).toJson());
            return;
        }
        try {
            InputDriver.neutralize(self);
            reply.accept(impl.craft(a.item_id(), a.count(), self));
        } finally {
            try {
                InputDriver.neutralize(self);
            } finally {
                BodyControlPolicies.release(
                        self,
                        CONTROL_ACTOR,
                        sessionId,
                        BodyControlClass.DIRECTED_ACTION,
                        BodyControlClass.DIRECTED_ACTION.defaultPriority());
            }
        }
    }
}
