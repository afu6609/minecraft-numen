package com.dwinovo.numen.core.combat.observe;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Bounded, change-oriented trace. Repeated per-tick snapshots are discarded,
 * while a periodic heartbeat preserves evidence that a state persisted.
 */
public final class CombatTrace {

    public static final int DEFAULT_CAPACITY = 128;
    private static final long HEARTBEAT_TICKS = 100;

    private final int capacity;
    private final ArrayDeque<CombatObservation> events;

    public CombatTrace() {
        this(DEFAULT_CAPACITY);
    }

    public CombatTrace(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.events = new ArrayDeque<>(capacity);
    }

    public synchronized boolean append(CombatObservation next) {
        if (next == null) throw new IllegalArgumentException("observation is required");
        CombatObservation previous = events.peekLast();
        if (previous != null && !significantlyChanged(previous, next)) {
            return false;
        }
        if (events.size() == capacity) {
            events.removeFirst();
        }
        events.addLast(next);
        return true;
    }

    public synchronized List<CombatObservation> events() {
        return List.copyOf(events);
    }

    public int capacity() {
        return capacity;
    }

    static boolean significantlyChanged(CombatObservation previous, CombatObservation next) {
        CombatFacts a = previous.facts();
        CombatFacts b = next.facts();
        if (b.serverTick() - a.serverTick() >= HEARTBEAT_TICKS) return true;
        if (previous.intent().action() != next.intent().action()) return true;
        if (previous.intent().phase() != next.intent().phase()) return true;
        if (!java.util.Objects.equals(a.targetEntityId(), b.targetEntityId())) return true;
        if (a.lineOfSightToObserver() != b.lineOfSightToObserver()) return true;
        if (a.usingItem() != b.usingItem()) return true;
        if (a.aggressive() != b.aggressive()) return true;
        if (Math.abs(a.health() - b.health()) >= 0.5F) return true;
        return distanceBand(a.distance()) != distanceBand(b.distance());
    }

    private static int distanceBand(double distance) {
        return (int) Math.floor(Math.max(0.0, distance) / 2.0);
    }
}
