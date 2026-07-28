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
  }

  push(item) {
    if (this.closed || this.isCancelled(item.event)) return false;
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

  isCancelled(event) {
    return (
      event?.type === "player_chat" &&
      Number.isFinite(event.id) &&
      event.id <= this.cancelPlayerChatThroughId
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
