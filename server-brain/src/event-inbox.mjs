const FRESH_TEST_STALE_TYPES = new Set([
  "player_chat",
  "console_chat",
  "task_finished",
  "test_instruction",
  "damage_received",
  "defense_started",
  "defense_finished",
  "death",
  "body_available",
]);
const MAX_COMPLETED_EVENT_KEYS = 256;

function normalizedSessionId(value) {
  return typeof value === "string" && value.trim() !== ""
    ? value.trim()
    : null;
}

function eventReceivedAt(event) {
  const value =
    event?.receivedAtEpochMillis ??
    event?.received_at_epoch_millis ??
    event?.receivedAt ??
    null;
  return ["string", "number"].includes(typeof value) ? value : null;
}

function eventDedupKey(event) {
  if (
    event == null ||
    typeof event !== "object" ||
    typeof event.type !== "string"
  ) {
    return null;
  }
  const serverSessionId = normalizedSessionId(
    event.serverSessionId ?? event.server_session_id,
  );
  const receivedAt = eventReceivedAt(event);
  if (event.id != null && ["string", "number"].includes(typeof event.id)) {
    if (serverSessionId != null) {
      return [
        event.type,
        "session",
        serverSessionId,
        "event",
        String(event.id),
      ].join(":");
    }
    if (["string", "number"].includes(typeof receivedAt)) {
      // Both Java event producers restart their numeric/string sequences with
      // the Minecraft JVM. Their original receive timestamp is stable across
      // a sidecar retry and prevents a new JVM's event 1 from colliding with
      // the previous JVM's event 1 when an older server omits session ids.
      return [
        event.type,
        "event",
        String(event.id),
        "received",
        String(receivedAt),
      ].join(":");
    }
    return `${event.type}:event:${String(event.id)}`;
  }
  const scopedIdentity = event.taskId ?? event.runId ?? null;
  if (
    scopedIdentity == null ||
    receivedAt == null ||
    !["string", "number"].includes(typeof scopedIdentity) ||
    !["string", "number"].includes(typeof receivedAt)
  ) {
    return null;
  }
  return [
    event.type,
    "scope",
    String(scopedIdentity),
    String(receivedAt),
  ].join(":");
}

function eventBehindFence(event, fence, acceptedTypes) {
  if (
    fence == null ||
    !Number.isFinite(event?.id) ||
    !acceptedTypes.has(event?.type)
  ) {
    return false;
  }
  const eventSessionId = normalizedSessionId(
    event?.serverSessionId ?? event?.server_session_id,
  );
  const receivedAt = eventReceivedAt(event);
  if (fence.serverSessionId != null && eventSessionId != null) {
    return (
      fence.serverSessionId === eventSessionId &&
      event.id <= fence.eventId
    );
  }
  if (fence.receivedAt != null && receivedAt != null) {
    return Number(receivedAt) <= Number(fence.receivedAt);
  }
  return event.id <= fence.eventId;
}

const CHAT_EVENT_TYPES = new Set(["player_chat", "console_chat"]);
const TEST_EVENT_TYPES = new Set(["test_instruction"]);

/**
 * A tiny async inbox that lets the MCP poller keep receiving urgent control
 * chat while a Codex turn is running. Normal events remain ordered and are
 * consumed by one brain worker.
 */
export class EventInbox {
  constructor({
    restored = null,
    onChange = null,
    serverSessionId = null,
  } = {}) {
    this.items = [];
    this.inFlight = [];
    this.waiters = [];
    this.closed = false;
    this.serverSessionId =
      normalizedSessionId(restored?.serverSessionId) ??
      normalizedSessionId(serverSessionId);
    this.cancelChatThroughId = Number.isFinite(restored?.cancelChatThroughId)
      ? restored.cancelChatThroughId
      : Number.NEGATIVE_INFINITY;
    this.cancelChatFence =
      restored?.cancelChatFence != null &&
      typeof restored.cancelChatFence === "object" &&
      Number.isFinite(restored.cancelChatFence.eventId)
        ? {
            eventId: restored.cancelChatFence.eventId,
            serverSessionId:
              normalizedSessionId(
                restored.cancelChatFence.serverSessionId,
              ) ?? this.serverSessionId,
            receivedAt:
              ["string", "number"].includes(
                typeof restored.cancelChatFence.receivedAt,
              )
                ? restored.cancelChatFence.receivedAt
                : null,
          }
        : Number.isFinite(this.cancelChatThroughId)
          ? {
              eventId: this.cancelChatThroughId,
              serverSessionId: this.serverSessionId,
              receivedAt: null,
            }
          : null;
    this.cancelTestInstructionFence =
      restored?.cancelTestInstructionFence != null &&
      typeof restored.cancelTestInstructionFence === "object" &&
      Number.isFinite(restored.cancelTestInstructionFence.eventId)
        ? {
            eventId: restored.cancelTestInstructionFence.eventId,
            serverSessionId:
              normalizedSessionId(
                restored.cancelTestInstructionFence.serverSessionId,
              ) ?? this.serverSessionId,
            receivedAt:
              ["string", "number"].includes(
                typeof restored.cancelTestInstructionFence.receivedAt,
              )
                ? restored.cancelTestInstructionFence.receivedAt
                : null,
          }
        : null;
    this.testRunGeneration = Number.isInteger(restored?.testRunGeneration)
      ? Math.max(0, restored.testRunGeneration)
      : 0;
    this.lastFreshTestBoundaryKey =
      typeof restored?.lastFreshTestBoundaryKey === "string"
        ? restored.lastFreshTestBoundaryKey
        : null;
    this.nextQueueId = Number.isInteger(restored?.nextQueueId)
      ? Math.max(1, restored.nextQueueId)
      : 1;
    this.completedEventKeys = new Map();
    for (const entry of Array.isArray(restored?.completedEventKeys)
      ? restored.completedEventKeys.slice(-MAX_COMPLETED_EVENT_KEYS)
      : []) {
      if (
        typeof entry?.key === "string" &&
        Number.isFinite(entry.completedAt)
      ) {
        this.completedEventKeys.set(entry.key, entry.completedAt);
      }
    }
    this.onChange = typeof onChange === "function" ? onChange : null;
    this.eventGenerations = new WeakMap();
    this.itemQueueIds = new WeakMap();
    const recovered = [
      ...(Array.isArray(restored?.inFlight) ? restored.inFlight : []),
      ...(Array.isArray(restored?.items) ? restored.items : []),
    ];
    const seen = new Set();
    const seenEventKeys = new Set(this.completedEventKeys.keys());
    for (const entry of recovered) {
      if (
        !Number.isInteger(entry?.queueId) ||
        entry.queueId < 1 ||
        entry.item == null ||
        typeof entry.item !== "object" ||
        seen.has(entry.queueId)
      ) {
        continue;
      }
      const eventKey = eventDedupKey(entry.item.event);
      if (eventKey != null && seenEventKeys.has(eventKey)) continue;
      if (eventKey != null) seenEventKeys.add(eventKey);
      seen.add(entry.queueId);
      const wrapped = {
        queueId: entry.queueId,
        generation: Number.isInteger(entry.generation)
          ? entry.generation
          : this.testRunGeneration,
        item: structuredClone(entry.item),
      };
      this.#register(wrapped);
      if (!this.isCancelled(wrapped.item.event)) this.items.push(wrapped);
      this.nextQueueId = Math.max(this.nextQueueId, entry.queueId + 1);
    }
  }

  currentGeneration() {
    return this.testRunGeneration;
  }

  push(item, { generation = this.testRunGeneration } = {}) {
    if (this.closed) return false;
    if (!Number.isInteger(generation) || generation < 0) {
      throw new TypeError("event generation must be a non-negative integer");
    }
    const eventKey = eventDedupKey(item?.event);
    if (
      eventKey != null &&
      (
        this.completedEventKeys.has(eventKey) ||
        this.items.some(
          (entry) => eventDedupKey(entry.item?.event) === eventKey,
        ) ||
        this.inFlight.some(
          (entry) => eventDedupKey(entry.item?.event) === eventKey,
        )
      )
    ) {
      return false;
    }
    const wrapped = {
      queueId: this.nextQueueId++,
      generation,
      item,
    };
    this.#register(wrapped);
    if (this.isCancelled(item.event)) return false;
    this.items.push(wrapped);
    this.#checkpoint();
    this.#wake();
    return true;
  }

  cancelPlayerChatsThrough(eventId) {
    this.cancelChatsThrough(eventId);
  }

  cancelChatsThrough(eventId, boundaryEvent = null) {
    if (
      Number.isFinite(eventId) &&
      this.#advanceChatFence(eventId, boundaryEvent)
    ) {
      this.items = this.items.filter(
        (entry) => !this.isCancelled(entry.item.event),
      );
      this.#checkpoint();
      return true;
    }
    return false;
  }

  cancelWorkThrough(eventId, boundaryEvent = null) {
    if (
      !Number.isFinite(eventId) ||
      !this.#advanceChatFence(eventId, boundaryEvent)
    ) {
      return false;
    }
    this.cancelTestInstructionFence = structuredClone(this.cancelChatFence);
    this.items = this.items.filter(
      (entry) => !this.isCancelled(entry.item.event),
    );
    this.#checkpoint();
    return true;
  }

  /**
   * Establish an isolation boundary for a fresh arena run.
   *
   * Events already accepted by the inbox (including a batch currently held by
   * the worker) belong to the previous generation and are cancelled. Events
   * pushed after this call belong to the new run even when their ids come from
   * a different producer sequence, as companion telemetry ids do.
   */
  beginFreshTestRun(eventId, boundaryEvent = null) {
    const boundaryKey = eventDedupKey(boundaryEvent);
    if (
      boundaryKey != null &&
      boundaryKey === this.lastFreshTestBoundaryKey
    ) {
      return true;
    }
    if (
      Number.isFinite(eventId) &&
      !this.#advanceChatFence(eventId, boundaryEvent)
    ) {
      return false;
    }
    this.testRunGeneration += 1;
    this.lastFreshTestBoundaryKey = boundaryKey;
    this.items = this.items.filter(
      (entry) => !this.isCancelled(entry.item.event),
    );
    this.#checkpoint();
    return true;
  }

  isCancelled(event) {
    const eventSessionId = normalizedSessionId(
      event?.serverSessionId ?? event?.server_session_id,
    );
    const staleChat = eventBehindFence(
      event,
      this.cancelChatFence,
      CHAT_EVENT_TYPES,
    );
    const staleTestInstruction = eventBehindFence(
      event,
      this.cancelTestInstructionFence,
      TEST_EVENT_TYPES,
    );
    const generation = event != null && typeof event === "object"
      ? this.eventGenerations.get(event)
      : undefined;
    const staleTestGeneration =
      generation != null &&
      generation < this.testRunGeneration &&
      FRESH_TEST_STALE_TYPES.has(event?.type);
    const staleServerSession =
      eventSessionId != null &&
      this.serverSessionId != null &&
      eventSessionId !== this.serverSessionId;
    return (
      staleChat ||
      staleTestInstruction ||
      staleTestGeneration ||
      staleServerSession
    );
  }

  async takeAll() {
    while (this.items.length === 0 && !this.closed) {
      await new Promise((resolve) => this.waiters.push(resolve));
    }
    const claimed = this.items.splice(0);
    this.inFlight.push(...claimed);
    this.#checkpoint();
    return claimed.map((entry) => entry.item);
  }

  complete(item) {
    const queueId = this.itemQueueIds.get(item);
    if (!Number.isInteger(queueId)) return false;
    const previousLength = this.inFlight.length;
    this.inFlight = this.inFlight.filter(
      (entry) => entry.queueId !== queueId,
    );
    const removed = previousLength !== this.inFlight.length;
    if (removed) {
      const key = eventDedupKey(item?.event);
      if (key != null) {
        this.completedEventKeys.delete(key);
        this.completedEventKeys.set(key, Date.now());
        while (this.completedEventKeys.size > MAX_COMPLETED_EVENT_KEYS) {
          this.completedEventKeys.delete(
            this.completedEventKeys.keys().next().value,
          );
        }
      }
      this.#checkpoint();
    }
    return removed;
  }

  retry(item) {
    const queueId = this.itemQueueIds.get(item);
    if (!Number.isInteger(queueId)) return false;
    const index = this.inFlight.findIndex(
      (entry) => entry.queueId === queueId,
    );
    if (index < 0) return false;
    const [entry] = this.inFlight.splice(index, 1);
    if (!this.isCancelled(entry.item.event)) this.items.unshift(entry);
    this.#checkpoint();
    this.#wake();
    return true;
  }

  touch() {
    this.#checkpoint();
  }

  synchronizeServerSession(serverSessionId) {
    const normalized = normalizedSessionId(serverSessionId);
    if (normalized == null) {
      throw new TypeError("serverSessionId must be a non-empty string");
    }
    const previous = this.serverSessionId;
    if (previous === normalized) {
      return { changed: false, clearedChatFence: false };
    }
    this.serverSessionId = normalized;
    const clearedChatFence =
      previous != null && this.cancelChatFence != null;
    if (previous != null) {
      this.cancelChatFence = null;
      this.cancelTestInstructionFence = null;
      this.cancelChatThroughId = Number.NEGATIVE_INFINITY;
      this.lastFreshTestBoundaryKey = null;
      // Body telemetry has no server-session field. Advancing the generation
      // invalidates any no-session body envelope captured before the live JVM
      // change while keeping Harness-only recovery events intact.
      this.testRunGeneration += 1;
    }
    this.items = this.items.filter(
      (entry) => !this.isCancelled(entry.item.event),
    );
    this.#checkpoint();
    return { changed: true, clearedChatFence };
  }

  snapshot() {
    const serialize = (entry) => ({
      queueId: entry.queueId,
      generation: entry.generation,
      item: structuredClone(entry.item),
    });
    return {
      serverSessionId: this.serverSessionId,
      cancelChatThroughId: Number.isFinite(this.cancelChatThroughId)
        ? this.cancelChatThroughId
        : null,
      cancelChatFence:
        this.cancelChatFence == null
          ? null
            : structuredClone(this.cancelChatFence),
      cancelTestInstructionFence:
        this.cancelTestInstructionFence == null
          ? null
          : structuredClone(this.cancelTestInstructionFence),
      testRunGeneration: this.testRunGeneration,
      lastFreshTestBoundaryKey: this.lastFreshTestBoundaryKey,
      nextQueueId: this.nextQueueId,
      completedEventKeys: [...this.completedEventKeys.entries()].map(
        ([key, completedAt]) => ({ key, completedAt }),
      ),
      items: this.items.map(serialize),
      inFlight: this.inFlight.map(serialize),
    };
  }

  close() {
    this.closed = true;
    this.#checkpoint();
    this.#wake();
  }

  #register(entry) {
    if (entry.item?.event != null && typeof entry.item.event === "object") {
      this.eventGenerations.set(entry.item.event, entry.generation);
    }
    this.itemQueueIds.set(entry.item, entry.queueId);
  }

  #advanceChatFence(eventId, boundaryEvent) {
    const boundarySessionId = normalizedSessionId(
      boundaryEvent?.serverSessionId ??
      boundaryEvent?.server_session_id,
    );
    if (
      boundarySessionId != null &&
      this.serverSessionId != null &&
      boundarySessionId !== this.serverSessionId
    ) {
      return false;
    }
    const serverSessionId = boundarySessionId ?? this.serverSessionId;
    const receivedAt = eventReceivedAt(boundaryEvent);
    const previous = this.cancelChatFence;
    const sameKnownSession =
      previous?.serverSessionId != null &&
      serverSessionId != null &&
      previous.serverSessionId === serverSessionId;
    const sameLegacyScope =
      previous != null &&
      previous.serverSessionId == null &&
      serverSessionId == null &&
      previous.receivedAt == null &&
      receivedAt == null;
    const eventIdToStore =
      sameKnownSession || sameLegacyScope
        ? Math.max(previous.eventId, eventId)
        : eventId;
    this.cancelChatFence = {
      eventId: eventIdToStore,
      serverSessionId,
      receivedAt,
    };
    this.cancelChatThroughId = eventIdToStore;
    return true;
  }

  #checkpoint() {
    this.onChange?.(this.snapshot());
  }

  #wake() {
    for (const resolve of this.waiters.splice(0)) resolve();
  }
}
