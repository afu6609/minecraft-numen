package com.dwinovo.numen.core.combat.observe;

import com.dwinovo.numen.core.combat.observe.CombatObservationAdapter.Context;
import com.dwinovo.numen.core.combat.observe.CombatObservationAdapter.Result;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Captures current server facts and retains a small number of bounded traces.
 * All entry points are safe to call on the server thread; no entity is retained.
 */
public final class CombatObservationService {

    /** Exact policy-binding schema for the normalized action/fact vocabulary. */
    public static final String POLICY_SCHEMA = "combat_observation_v1";

    private static final int MAX_OBSERVERS = 32;
    private static final int MAX_TARGETS_PER_OBSERVER = 64;
    private static final CombatObservationService INSTANCE =
            new CombatObservationService(CombatObservationAdapters.defaults());

    private final CombatObservationAdapters adapters;
    private final LinkedHashMap<UUID, ObserverState> observers =
            new LinkedHashMap<>(16, 0.75F, true);

    public CombatObservationService(CombatObservationAdapters adapters) {
        this.adapters = adapters;
    }

    public static CombatObservationService instance() {
        return INSTANCE;
    }

    public CombatObservation observe(NumenPlayer observer, LivingEntity entity) {
        if (observer == null || entity == null) {
            throw new IllegalArgumentException("observer and entity are required");
        }
        if (observer.level() != entity.level()) {
            throw new IllegalArgumentException("entity must be in the observer's dimension");
        }

        CombatFacts base = captureFacts(observer, entity);
        CombatObservationAdapter adapter = adapters.select(entity);
        Result result = adapter.observe(new Context(observer, entity, base));
        CombatObservation observation =
                new CombatObservation(
                        base.withState(result.state()),
                        result.intent(),
                        adapter.id(),
                        adapter.schemaId());
        traceFor(observer.getUUID(), entity.getId(), entity.getUUID()).append(observation);
        return observation;
    }

    public List<CombatObservation> trace(UUID observerUuid, UUID entityUuid) {
        synchronized (observers) {
            ObserverState state = observers.get(observerUuid);
            if (state == null) return List.of();
            CombatTrace trace = state.traces.get(entityUuid);
            return trace == null ? List.of() : trace.events();
        }
    }

    /** Historical lookup used after an observed target died or despawned. */
    public List<CombatObservation> traceByEntityId(UUID observerUuid, int entityId) {
        synchronized (observers) {
            ObserverState state = observers.get(observerUuid);
            if (state == null) return List.of();
            UUID entityUuid = state.entityIds.get(entityId);
            if (entityUuid == null) return List.of();
            CombatTrace trace = state.traces.get(entityUuid);
            return trace == null ? List.of() : trace.events();
        }
    }

    public int traceCapacity() {
        return CombatTrace.DEFAULT_CAPACITY;
    }

    private CombatTrace traceFor(UUID observerUuid, int entityId, UUID entityUuid) {
        synchronized (observers) {
            ObserverState state = observers.computeIfAbsent(observerUuid, ignored -> new ObserverState());
            while (observers.size() > MAX_OBSERVERS) {
                observers.remove(observers.keySet().iterator().next());
            }
            state.entityIds.put(entityId, entityUuid);
            return state.traceFor(entityUuid);
        }
    }

    private static CombatFacts captureFacts(NumenPlayer observer, LivingEntity entity) {
        LivingEntity target = entity instanceof Mob mob ? mob.getTarget() : null;
        ItemStack used = entity.getUseItem();
        Vec3 position = entity.position();
        Vec3 velocity = entity.getDeltaMovement();
        return new CombatFacts(
                entity.level().getGameTime(),
                entity.getId(),
                entity.getUUID().toString(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                entity.getName().getString(),
                new CombatFacts.Vector(position.x, position.y, position.z),
                new CombatFacts.Vector(velocity.x, velocity.y, velocity.z),
                entity.getHealth(),
                entity.getMaxHealth(),
                observer.distanceTo(entity),
                entity.hasLineOfSight(observer),
                entity instanceof Mob mob && mob.isAggressive(),
                entity.isUsingItem(),
                used.isEmpty() ? null
                        : BuiltInRegistries.ITEM.getKey(used.getItem()).toString(),
                target == null ? null : target.getId(),
                target == null ? null : target.getName().getString(),
                target == observer,
                entity.onGround(),
                entity.isInWater(),
                entity.isOnFire(),
                Map.of());
    }

    private static final class ObserverState {
        private final LinkedHashMap<UUID, CombatTrace> traces =
                new LinkedHashMap<>(16, 0.75F, true);
        private final LinkedHashMap<Integer, UUID> entityIds =
                new LinkedHashMap<>(16, 0.75F, true);

        private CombatTrace traceFor(UUID entityUuid) {
            CombatTrace trace = traces.computeIfAbsent(entityUuid, ignored -> new CombatTrace());
            while (traces.size() > MAX_TARGETS_PER_OBSERVER) {
                UUID removed = traces.keySet().iterator().next();
                traces.remove(removed);
                entityIds.entrySet().removeIf(entry -> entry.getValue().equals(removed));
            }
            return trace;
        }
    }
}
