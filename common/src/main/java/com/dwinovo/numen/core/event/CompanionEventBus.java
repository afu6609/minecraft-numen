package com.dwinovo.numen.core.event;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small in-memory telemetry channel from the server-side body to the dedicated
 * server brain. Events are partitioned by companion so polling one body can
 * never consume another body's observations.
 *
 * <p>This is deliberately not a task/result transport. Survival reflexes act
 * locally on the same tick; the external brain receives compact facts afterward
 * so it can repair stale context and re-ground a longer-running goal.
 */
public final class CompanionEventBus {

    public static final int PRIORITY_NORMAL = 50;
    public static final int PRIORITY_URGENT = 100;

    private static final int MAX_QUEUED_PER_COMPANION = 256;
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<UUID, ArrayDeque<Event>> QUEUES = new ConcurrentHashMap<>();

    private CompanionEventBus() {}

    public record Event(
            String id,
            String type,
            int priority,
            String companionUuid,
            String companionName,
            String message,
            Map<String, Object> data,
            long gameTime,
            long receivedAtEpochMillis) {}

    public static void publish(
            NumenPlayer companion,
            String type,
            int priority,
            String message,
            Map<String, Object> data) {
        if (companion == null || type == null || type.isBlank()) {
            return;
        }
        UUID uuid = companion.getUUID();
        Event event = new Event(
                "body-" + NEXT_ID.incrementAndGet(),
                type,
                priority,
                uuid.toString(),
                companion.getGameProfile().getName(),
                message == null ? "" : message,
                data == null ? Map.of() : Map.copyOf(data),
                companion.level().getGameTime(),
                System.currentTimeMillis());
        ArrayDeque<Event> queue = QUEUES.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (queue.size() >= MAX_QUEUED_PER_COMPANION) {
                queue.removeFirst();
            }
            queue.addLast(event);
        }
    }

    public static List<Event> poll(UUID companionUuid, int limit) {
        int bounded = Math.max(1, Math.min(64, limit));
        ArrayDeque<Event> queue = QUEUES.get(companionUuid);
        if (queue == null) {
            return List.of();
        }
        List<Event> events = new ArrayList<>(bounded);
        synchronized (queue) {
            while (events.size() < bounded && !queue.isEmpty()) {
                events.add(queue.removeFirst());
            }
        }
        return events;
    }

    public static Map<String, Object> bodySnapshot(NumenPlayer companion) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("position", List.of(companion.getX(), companion.getY(), companion.getZ()));
        data.put("dimension", companion.level().dimension().location().toString());
        data.put("health", companion.getHealth());
        data.put("max_health", companion.getMaxHealth());
        data.put("food", companion.getFoodData().getFoodLevel());
        return data;
    }
}
