package com.dwinovo.numen.core.combat.observe;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Read-only query for one entity's current authoritative facts and predicted intent. */
public final class ObserveEntityIntentTool implements NumenTool {

    @Override
    public String name() {
        return "observe_entity_intent";
    }

    @Override
    public String description() {
        return "Observe one nearby living entity using server-authoritative combat state. "
                + "Returns current facts plus a normalized predicted action, phase, confidence, "
                + "effect window, and evidence. The intent is a prediction; facts are authoritative. "
                + "Use an entity_id from scan_nearby_entities. The target must be alive, in your "
                + "dimension, and within 64 blocks.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("entity_id", "Runtime entity id from scan_nearby_entities.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args,
                             NumenPlayer self, Consumer<String> reply) {
        try {
            int entityId = EntityObservationAccess.requiredEntityId(args);
            var entity = EntityObservationAccess.resolve(self, entityId);
            reply.accept(CombatObservationJson.current(
                    CombatObservationService.instance().observe(self, entity)));
        } catch (RuntimeException error) {
            reply.accept(CombatObservationJson.error(error.getMessage()));
        }
    }
}
