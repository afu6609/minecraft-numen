import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";

const SCHEMA_VERSION = 1;

function normalizeDocument(value) {
  if (value == null || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError("brain state must be a JSON object");
  }
  if (value.schema_version !== SCHEMA_VERSION) {
    throw new TypeError(
      `unsupported brain state schema ${JSON.stringify(value.schema_version)}`,
    );
  }
  for (const [field, snapshot] of [
    ["brain", value.brain],
    ["inbox", value.inbox],
    ["transport", value.transport],
  ]) {
    if (
      snapshot != null &&
      (typeof snapshot !== "object" || Array.isArray(snapshot))
    ) {
      throw new TypeError(`${field} state must be a JSON object or null`);
    }
  }
  return {
    brain: value.brain == null ? null : structuredClone(value.brain),
    inbox: value.inbox == null ? null : structuredClone(value.inbox),
    transport:
      value.transport == null ? null : structuredClone(value.transport),
  };
}

/**
 * Atomic, ordered persistence for the small Harness control-plane snapshot.
 *
 * Callers may schedule checkpoints from synchronous state transitions. Writes
 * are serialized and collapsed to the latest snapshot while one is in flight.
 */
export class BrainStateStore {
  constructor(file, state = null, { onError = null } = {}) {
    this.file = path.resolve(file);
    this.state = state ?? { brain: null, inbox: null, transport: null };
    this.desired = structuredClone(this.state);
    this.onError = typeof onError === "function" ? onError : null;
    this.pending = null;
    this.writing = false;
    this.flushPromise = Promise.resolve();
    this.lastError = null;
  }

  static async open(file, options = {}) {
    const resolved = path.resolve(file);
    let state = null;
    try {
      const document = JSON.parse(await readFile(resolved, "utf8"));
      state = normalizeDocument(document);
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    return new BrainStateStore(resolved, state, options);
  }

  restoredState() {
    return this.restoredBrain();
  }

  restoredBrain() {
    return this.state.brain == null ? null : structuredClone(this.state.brain);
  }

  restoredInbox() {
    return this.state.inbox == null ? null : structuredClone(this.state.inbox);
  }

  restoredTransport() {
    return this.state.transport == null
      ? null
      : structuredClone(this.state.transport);
  }

  schedule(snapshot) {
    this.scheduleBrain(snapshot);
  }

  scheduleBrain(snapshot) {
    this.#schedulePart("brain", snapshot);
  }

  scheduleInbox(snapshot) {
    this.#schedulePart("inbox", snapshot);
  }

  scheduleTransport(snapshot) {
    this.#schedulePart("transport", snapshot);
  }

  #schedulePart(part, snapshot) {
    if (
      snapshot == null ||
      typeof snapshot !== "object" ||
      Array.isArray(snapshot)
    ) {
      throw new TypeError("brain snapshot must be an object");
    }
    this.desired = {
      ...this.desired,
      [part]: structuredClone(snapshot),
    };
    this.pending = structuredClone(this.desired);
    this.lastError = null;
    if (!this.writing) this.#drain();
  }

  async flush() {
    while (
      (this.writing || this.pending != null) &&
      this.lastError == null
    ) {
      await this.flushPromise;
    }
    if (this.lastError != null) throw this.lastError;
  }

  #drain() {
    this.writing = true;
    this.flushPromise = (async () => {
      while (this.pending != null) {
        const snapshot = this.pending;
        this.pending = null;
        await this.#write(snapshot);
        this.state = snapshot;
      }
    })()
      .catch((error) => {
        this.lastError = error;
        this.pending = structuredClone(this.desired);
        this.onError?.(error);
      })
      .finally(() => {
        this.writing = false;
        if (this.pending != null && this.lastError == null) this.#drain();
      });
  }

  async #write(snapshot) {
    await mkdir(path.dirname(this.file), { recursive: true });
    const temporary = `${this.file}.${process.pid}.tmp`;
    const document = {
      schema_version: SCHEMA_VERSION,
      saved_at: new Date().toISOString(),
      brain: snapshot.brain,
      inbox: snapshot.inbox,
      transport: snapshot.transport,
    };
    await writeFile(
      temporary,
      `${JSON.stringify(document, null, 2)}\n`,
      { encoding: "utf8", mode: 0o600 },
    );
    await rename(temporary, this.file);
  }
}
