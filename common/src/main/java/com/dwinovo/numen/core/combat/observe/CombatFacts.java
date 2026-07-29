package com.dwinovo.numen.core.combat.observe;

import java.util.Map;

/** Server-authoritative snapshot used as evidence for an inferred intent. */
public record CombatFacts(
        long serverTick,
        int entityId,
        String entityUuid,
        String entityType,
        String name,
        Vector position,
        Vector velocity,
        float health,
        float maxHealth,
        double distance,
        boolean lineOfSightToObserver,
        boolean aggressive,
        boolean usingItem,
        String usedItem,
        Integer targetEntityId,
        String targetName,
        boolean targetIsObserver,
        boolean onGround,
        boolean inWater,
        boolean onFire,
        Map<String, Object> state) {

    public CombatFacts {
        state = state == null ? Map.of() : Map.copyOf(state);
    }

    public CombatFacts withState(Map<String, Object> additionalState) {
        return new CombatFacts(
                serverTick, entityId, entityUuid, entityType, name, position, velocity,
                health, maxHealth, distance, lineOfSightToObserver, aggressive, usingItem,
                usedItem, targetEntityId, targetName, targetIsObserver, onGround, inWater,
                onFire, additionalState);
    }

    public record Vector(double x, double y, double z) {}
}
