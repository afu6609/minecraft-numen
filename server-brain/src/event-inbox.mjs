const FRESH_TEST_STALE_TYPES = new Set([
  "player_chat",
  "task_finished",
  "test_instruction",
  "damage_received",
  "defense_started",
  "defense_finished",
  "death",
  "body_available",
]);

/**
 * A tiny async inbox that lets the MCP poller keep receiving urgent control
 * chat while a Codex turn is running. Normal events remain ordered and are
 * consumed by one brain worker.
 */
export class EventInbox {
  constructor() {
    this.items = [];
    this.waiters = [];
    this.closed = false;
    this.cancelPlayerChatThroughId = Number.NEGATIVE_INFINITY;
    this.testRunGeneration = 0;
    this.eventGenerations = new WeakMap();
  }

  push(item) {
    if (this.closed) return false;
    if (item?.event != null && typeof item.event === "object") {
      this.eventGenerations.set(item.event, this.testRunGeneration);
    }
    if (this.isCancelled(item.event)) return false;
    this.items.push(item);
    this.#wake();
    return true;
  }

  cancelPlayerChatsThrough(eventId) {
    if (Number.isFinite(eventId)) {
      this.cancelPlayerChatThroughId = Math.max(
        this.cancelPlayerChatThroughId,
        eventId,
      );
      this.items = this.items.filter((item) => !this.isCancelled(item.event));
    }
  }

  /**
   * Establish an isolation boundary for a fresh arena run.
   *
   * Events already accepted by the inbox (including a batch currently held by
   * the worker) belong to the previous generation and are cancelled. Events
   * pushed after this call belong to the new run even when their ids come from
   * a different producer sequence, as companion telemetry ids do.
   */
  beginFreshTestRun(eventId) {
    this.testRunGeneration += 1;
    if (Number.isFinite(eventId)) {
      this.cancelPlayerChatThroughId = Math.max(
        this.cancelPlayerChatThroughId,
        eventId,
      );
    }
    this.items = this.items.filter((item) => !this.isCancelled(item.event));
  }

  isCancelled(event) {
    const stalePlayerChat =
      event?.type === "player_chat" &&
      Number.isFinite(event.id) &&
      event.id <= this.cancelPlayerChatThroughId;
    const stoppedTask =
      event?.type === "task_finished" &&
      String(event.status ?? "").toLowerCase() === "stopped";
    const generation = event != null && typeof event === "object"
      ? this.eventGenerations.get(event)
      : undefined;
    const staleTestGeneration =
      generation != null &&
      generation < this.testRunGeneration &&
      FRESH_TEST_STALE_TYPES.has(event?.type);
    return (
      stalePlayerChat ||
      stoppedTask ||
      staleTestGeneration
    );
  }

  async takeAll() {
    while (this.items.length === 0 && !this.closed) {
      await new Promise((resolve) => this.waiters.push(resolve));
    }
    return this.items.splice(0);
  }

  close() {
    this.closed = true;
    this.#wake();
  }

  #wake() {
    for (const resolve of this.waiters.splice(0)) resolve();
  }
}
