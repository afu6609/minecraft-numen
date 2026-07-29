package com.dwinovo.numen.core.combat.observe;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import com.google.gson.JsonObject;

final class EntityObservationAccess {

    static final double MAX_DISTANCE = 64.0;
    private static final double MAX_DISTANCE_SQR = MAX_DISTANCE * MAX_DISTANCE;

    private EntityObservationAccess() {}

    static int requiredEntityId(JsonObject args) {
        if (args == null || !args.has("entity_id") || args.get("entity_id").isJsonNull()) {
            throw new IllegalArgumentException("entity_id is required");
        }
        return args.get("entity_id").getAsInt();
    }

    static LivingEntity resolve(NumenPlayer observer, int entityId) {
        if (!(observer.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("combat observation requires a server level");
        }
        Entity entity = level.getEntity(entityId);
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            throw new IllegalArgumentException(
                    "entity_id must identify a living entity in the current dimension");
        }
        if (living == observer) {
            throw new IllegalArgumentException("observe another living entity, not yourself");
        }
        if (living.level() != observer.level()) {
            throw new IllegalArgumentException("entity must be in the current dimension");
        }
        if (observer.distanceToSqr(living) > MAX_DISTANCE_SQR) {
            throw new IllegalArgumentException("entity must be within 64 blocks");
        }
        return living;
    }
}
