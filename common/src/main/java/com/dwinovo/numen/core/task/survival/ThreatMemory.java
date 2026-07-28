package com.dwinovo.numen.core.task.survival;

import com.dwinovo.numen.core.event.CompanionEventBus;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the authoritative source of recent damage. Vanilla's
 * {@code getLastHurtByMob()} is useful but not sufficient for projectiles,
 * neutral mobs, players, or an attacker that has moved outside the old scan
 * radius by the next scheduler tick.
 */
public final class ThreatMemory {

    public static final long ACTIVE_TICKS = 10 * 20L;

    private static final Map<UUID, DamageSnapshot> LAST_DAMAGE = new ConcurrentHashMap<>();

    private ThreatMemory() {}

    public record DamageSnapshot(
            long gameTime,
            int sourceEntityId,
            UUID sourceEntityUuid,
            String sourceType,
            String damageType,
            float amount,
            float healthBefore,
            float expectedHealthAfter,
            BlockPos sourcePosition) {}

    public static void recordDamage(NumenPlayer companion, DamageSource source, float amount) {
        if (companion == null || amount <= 0.0f) {
            return;
        }
        Entity attacker = source == null ? null : source.getEntity();
        Entity direct = source == null ? null : source.getDirectEntity();
        Entity sourceEntity = attacker != null ? attacker : direct;
        String sourceType = sourceEntity == null
                ? "environment"
                : BuiltInRegistries.ENTITY_TYPE.getKey(sourceEntity.getType()).toString();
        BlockPos sourcePos = sourceEntity == null ? null : sourceEntity.blockPosition().immutable();
        float before = companion.getHealth();
        DamageSnapshot snapshot = new DamageSnapshot(
                companion.level().getGameTime(),
                sourceEntity == null ? -1 : sourceEntity.getId(),
                sourceEntity == null ? null : sourceEntity.getUUID(),
                sourceType,
                source == null ? "unknown" : source.getMsgId(),
                amount,
                before,
                Math.max(0.0f, before - amount),
                sourcePos);
        LAST_DAMAGE.put(companion.getUUID(), snapshot);

        Map<String, Object> data = new LinkedHashMap<>(CompanionEventBus.bodySnapshot(companion));
        data.put("damage", amount);
        data.put("health_before", before);
        data.put("health_after_expected", snapshot.expectedHealthAfter());
        data.put("damage_type", snapshot.damageType());
        data.put("source_type", snapshot.sourceType());
        data.put("source_entity_id", snapshot.sourceEntityId());
        if (snapshot.sourceEntityUuid() != null) {
            data.put("source_entity_uuid", snapshot.sourceEntityUuid().toString());
        }
        if (sourcePos != null) {
            data.put("source_position", List.of(
                    sourcePos.getX(), sourcePos.getY(), sourcePos.getZ()));
        }
        CompanionEventBus.publish(
                companion,
                "damage_received",
                CompanionEventBus.PRIORITY_URGENT,
                "The companion body took damage.",
                data);
    }

    public static DamageSnapshot recent(NumenPlayer companion) {
        DamageSnapshot snapshot = LAST_DAMAGE.get(companion.getUUID());
        if (snapshot == null) {
            return null;
        }
        long age = companion.level().getGameTime() - snapshot.gameTime();
        if (age < 0 || age > ACTIVE_TICKS) {
            LAST_DAMAGE.remove(companion.getUUID(), snapshot);
            return null;
        }
        return snapshot;
    }

    public static LivingEntity resolveRecentAttacker(NumenPlayer companion) {
        DamageSnapshot snapshot = recent(companion);
        if (snapshot == null || snapshot.sourceEntityId() < 0) {
            return null;
        }
        Entity entity = companion.level().getEntity(snapshot.sourceEntityId());
        if (!(entity instanceof LivingEntity living)
                || living.isRemoved()
                || living.isDeadOrDying()
                || snapshot.sourceEntityUuid() == null
                || !snapshot.sourceEntityUuid().equals(living.getUUID())) {
            return null;
        }
        return living;
    }

    public static void clear(UUID companionUuid) {
        LAST_DAMAGE.remove(companionUuid);
    }
}
