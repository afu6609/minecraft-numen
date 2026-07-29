package com.dwinovo.numen.core.combat.observe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;

import java.util.List;
import java.util.Locale;

/** Stable JSON envelope used by both observation tools. */
public final class CombatObservationJson {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(CombatAction.class,
                    (JsonSerializer<CombatAction>) (value, type, context) ->
                            new JsonPrimitive(value.name().toLowerCase(Locale.ROOT)))
            .registerTypeAdapter(CombatPhase.class,
                    (JsonSerializer<CombatPhase>) (value, type, context) ->
                            new JsonPrimitive(value.name().toLowerCase(Locale.ROOT)))
            .create();

    private CombatObservationJson() {}

    public static String current(CombatObservation observation) {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("policy_schema", CombatObservationService.POLICY_SCHEMA);
        root.add("observation", GSON.toJsonTree(observation));
        root.addProperty("intent_is_prediction", true);
        return root.toString();
    }

    public static String trace(int entityId, List<CombatObservation> events, int capacity) {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("policy_schema", CombatObservationService.POLICY_SCHEMA);
        root.addProperty("entity_id", entityId);
        root.addProperty("event_count", events.size());
        root.addProperty("capacity", capacity);
        JsonArray array = GSON.toJsonTree(events).getAsJsonArray();
        root.add("events", array);
        root.addProperty("sampling_note",
                "Only significant changes and 100-tick heartbeats are retained.");
        return root.toString();
    }

    public static String error(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("success", false);
        root.addProperty("error", message);
        return root.toString();
    }
}
