package com.dwinovo.numen.core.combat.observe;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Returns the bounded, significant-change trace collected for one nearby entity. */
public final class GetCombatTraceTool implements NumenTool {

    @Override
    public String name() {
        return "get_combat_trace";
    }

    @Override
    public String description() {
        return "Read the bounded significant-change combat trace for an entity id previously "
                + "observed by this body. A live target is revalidated in the same dimension and "
                + "within 64 blocks; a dead/despawned target may still be read from the body's "
                + "bounded historical trace. The server combat sampler collects active threats.";
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
            CombatObservationService service = CombatObservationService.instance();
            java.util.List<CombatObservation> events;
            try {
                var entity = EntityObservationAccess.resolve(self, entityId);
                events = service.trace(self.getUUID(), entity.getUUID());
            } catch (IllegalArgumentException unavailable) {
                events = service.traceByEntityId(self.getUUID(), entityId);
                if (events.isEmpty()) throw unavailable;
            }
            reply.accept(CombatObservationJson.trace(
                    entityId,
                    events,
                    service.traceCapacity()));
        } catch (RuntimeException error) {
            reply.accept(CombatObservationJson.error(error.getMessage()));
        }
    }
}
