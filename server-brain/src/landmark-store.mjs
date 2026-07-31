import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";

const SCHEMA_VERSION = 1;
const MAX_LANDMARKS = 64;
const MAX_PROMPT_LANDMARKS = 8;
const STRUCTURE_TOOLS = new Set([
  "structure_plan",
  "structure_patch",
  "structure_status",
]);

function cleanString(value, maxLength = 240) {
  if (typeof value !== "string") return null;
  const clean = value.trim();
  if (clean === "") return null;
  return clean.slice(0, maxLength);
}

function finiteCoordinate(value) {
  return Number.isFinite(value) ? value : null;
}

function normalizedPosition(value) {
  if (value == null || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const x = finiteCoordinate(value.x);
  const y = finiteCoordinate(value.y);
  const z = finiteCoordinate(value.z);
  return x == null || y == null || z == null ? null : { x, y, z };
}

function normalizedBounds(value) {
  if (value == null || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const min = normalizedPosition(value.min);
  const max = normalizedPosition(value.max);
  if (min == null || max == null) return null;
  if (min.x > max.x || min.y > max.y || min.z > max.z) return null;
  return { min, max };
}

function parsedToolPayload(item) {
  if (item?.result?.isError === true) return null;
  const candidates = [
    item?.result?.structured_content,
    item?.result?.structuredContent,
  ];
  for (const block of Array.isArray(item?.result?.content)
    ? item.result.content
    : []) {
    if (block?.type !== "text" || typeof block.text !== "string") continue;
    try {
      candidates.push(JSON.parse(block.text));
    } catch {
      // Structure workflow tools return JSON; unrelated plain text is ignored.
    }
  }
  for (const candidate of candidates) {
    if (
      candidate == null ||
      typeof candidate !== "object" ||
      Array.isArray(candidate) ||
      candidate.success === false
    ) {
      continue;
    }
    const data =
      candidate.data != null &&
      typeof candidate.data === "object" &&
      !Array.isArray(candidate.data)
        ? candidate.data
        : candidate;
    if (cleanString(data.workflow_id, 160) != null) return data;
  }
  return null;
}

function semanticAliases(name, goal) {
  const aliases = new Set();
  if (name != null) aliases.add(name);
  const text = `${name ?? ""} ${goal ?? ""}`.toLocaleLowerCase();
  if (/(?:木屋|小屋|房子|房屋|屋子|住宅|住所|house|cottage|cabin)/u.test(text)) {
    aliases.add("房子");
    aliases.add("房屋");
  }
  if (/(?:木屋|cottage|cabin)/u.test(text)) aliases.add("木屋");
  if (/(?:\bhome\b|家)/u.test(text)) aliases.add("家");
  if (/(?:基地|\bbase\b)/u.test(text)) aliases.add("基地");
  return [...aliases]
    .map((value) => cleanString(value, 96))
    .filter(Boolean)
    .slice(0, 12);
}

function normalizeStoredLandmark(value) {
  if (value == null || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError("landmark must be an object");
  }
  const workflowId = cleanString(value?.source?.workflow_id, 160);
  const id = cleanString(value.landmark_id, 200);
  const label = cleanString(value.label, 96);
  const state = cleanString(value.state, 24);
  if (
    id == null ||
    workflowId == null ||
    label == null ||
    !["planned", "verified", "removed"].includes(state)
  ) {
    throw new TypeError("landmark has invalid identity or state");
  }
  const bounds = normalizedBounds(value?.geometry?.bounds);
  if (bounds == null) throw new TypeError("landmark needs valid bounds");
  return {
    landmark_id: id,
    kind: "structure",
    label,
    aliases: Array.isArray(value.aliases)
      ? [...new Set(value.aliases.map((item) => cleanString(item, 96)).filter(Boolean))]
          .slice(0, 12)
      : [],
    dimension: cleanString(value.dimension, 160),
    geometry: { bounds },
    source: {
      type: "structure_workflow",
      workflow_id: workflowId,
      workflow_revision: Number.isInteger(value?.source?.workflow_revision)
        ? Math.max(1, value.source.workflow_revision)
        : null,
    },
    state,
    provenance: {
      source_event_key: cleanString(value?.provenance?.source_event_key, 200),
      objective: cleanString(value?.provenance?.objective, 240),
    },
    last_verified_at: cleanString(value.last_verified_at, 64),
    last_verified_server_session_id: cleanString(
      value.last_verified_server_session_id,
      160,
    ),
    created_at: cleanString(value.created_at, 64) ?? new Date(0).toISOString(),
    updated_at: cleanString(value.updated_at, 64) ?? new Date(0).toISOString(),
  };
}

function normalizeDocument(document, { worldKey, companion }) {
  if (
    document == null ||
    typeof document !== "object" ||
    Array.isArray(document) ||
    document.schema_version !== SCHEMA_VERSION
  ) {
    throw new TypeError("unsupported landmark store schema");
  }
  if (document.world_key !== worldKey || document.companion !== companion) {
    throw new TypeError("landmark store belongs to a different world or companion");
  }
  if (!Array.isArray(document.landmarks)) {
    throw new TypeError("landmark store needs a landmarks array");
  }
  return {
    revision: Number.isInteger(document.revision)
      ? Math.max(0, document.revision)
      : 0,
    landmarks: document.landmarks
      .slice(-MAX_LANDMARKS)
      .map(normalizeStoredLandmark),
  };
}

function observationState(tool, payload) {
  if (tool === "structure_plan" || tool === "structure_patch") {
    return "planned";
  }
  const operation = cleanString(payload.operation, 24) ?? "build";
  if (operation === "demolish" && payload.phase === "complete") {
    return "removed";
  }
  return operation === "build" && payload.phase === "complete"
    ? "verified"
    : "planned";
}

export class LandmarkStore {
  constructor(
    file,
    {
      worldKey,
      companion,
      restored = null,
      onError = null,
    },
  ) {
    this.file = path.resolve(file);
    this.worldKey = worldKey;
    this.companion = companion;
    this.revision = restored?.revision ?? 0;
    this.landmarks = new Map(
      (restored?.landmarks ?? []).map((entry) => [
        entry.source.workflow_id,
        entry,
      ]),
    );
    this.onError = typeof onError === "function" ? onError : null;
    this.writeTail = Promise.resolve();
  }

  static async open(file, { worldKey, companion, onError = null } = {}) {
    const normalizedWorldKey = cleanString(worldKey, 160);
    const normalizedCompanion = cleanString(companion, 64);
    if (normalizedWorldKey == null || normalizedCompanion == null) {
      throw new TypeError("landmark store needs worldKey and companion");
    }
    const resolved = path.resolve(file);
    let restored = null;
    try {
      restored = normalizeDocument(
        JSON.parse(await readFile(resolved, "utf8")),
        {
          worldKey: normalizedWorldKey,
          companion: normalizedCompanion,
        },
      );
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    return new LandmarkStore(resolved, {
      worldKey: normalizedWorldKey,
      companion: normalizedCompanion,
      restored,
      onError,
    });
  }

  async noteTurn(
    turn,
    {
      serverSessionId = null,
      sourceEventKey = null,
      objective = null,
    } = {},
  ) {
    const changes = [];
    for (const item of Array.isArray(turn?.items) ? turn.items : []) {
      if (
        item?.type !== "mcp_tool_call" ||
        item.server !== "numen" ||
        item.status !== "completed" ||
        !STRUCTURE_TOOLS.has(item.tool)
      ) {
        continue;
      }
      const payload = parsedToolPayload(item);
      if (payload == null) continue;
      const workflowId = cleanString(payload.workflow_id, 160);
      const bounds = normalizedBounds(payload.bounds);
      if (workflowId == null || bounds == null) continue;
      const previous = this.landmarks.get(workflowId);
      const now = new Date().toISOString();
      const name =
        cleanString(payload.name, 96) ??
        previous?.label ??
        `structure ${workflowId.slice(0, 16)}`;
      const goal = cleanString(payload.goal, 240);
      const state = observationState(item.tool, payload);
      const entry = normalizeStoredLandmark({
        landmark_id: `structure:${workflowId}`,
        kind: "structure",
        label: name,
        aliases: [
          ...(previous?.aliases ?? []),
          ...semanticAliases(name, goal),
        ],
        dimension:
          cleanString(payload.dimension, 160) ?? previous?.dimension ?? null,
        geometry: { bounds },
        source: {
          type: "structure_workflow",
          workflow_id: workflowId,
          workflow_revision: Number.isInteger(payload.revision)
            ? payload.revision
            : previous?.source?.workflow_revision ?? null,
        },
        state,
        provenance: {
          source_event_key:
            cleanString(sourceEventKey, 200) ??
            previous?.provenance?.source_event_key ??
            null,
          objective:
            cleanString(objective, 240) ??
            goal ??
            previous?.provenance?.objective ??
            null,
        },
        last_verified_at:
          state === "verified" ? now : previous?.last_verified_at ?? null,
        last_verified_server_session_id:
          state === "verified"
            ? cleanString(serverSessionId, 160)
            : previous?.last_verified_server_session_id ?? null,
        created_at: previous?.created_at ?? now,
        updated_at: now,
      });
      this.landmarks.set(workflowId, entry);
      this.revision += 1;
      changes.push(structuredClone(entry));
    }
    if (changes.length === 0) return [];
    this.#prune();
    try {
      await this.#persist();
    } catch (error) {
      this.onError?.(error);
    }
    return changes;
  }

  promptView({ query = "", serverSessionId = null } = {}) {
    const normalizedQuery = String(query ?? "").toLocaleLowerCase();
    const currentSession = cleanString(serverSessionId, 160);
    const scored = [...this.landmarks.values()]
      .filter((entry) => entry.state !== "removed")
      .map((entry) => {
        const terms = [entry.label, ...entry.aliases]
          .join(" ")
          .toLocaleLowerCase();
        const score =
          normalizedQuery !== "" &&
          entry.aliases.some((alias) =>
            normalizedQuery.includes(alias.toLocaleLowerCase()),
          )
            ? 2
            : normalizedQuery !== "" &&
                [...normalizedQuery].some((character) => terms.includes(character))
              ? 1
              : 0;
        return { entry, score };
      })
      .sort(
        (left, right) =>
          right.score - left.score ||
          right.entry.updated_at.localeCompare(left.entry.updated_at),
      )
      .slice(0, MAX_PROMPT_LANDMARKS)
      .map(({ entry }) => ({
        landmark_id: entry.landmark_id,
        kind: entry.kind,
        label: entry.label,
        aliases: entry.aliases,
        dimension: entry.dimension,
        bounds: entry.geometry.bounds,
        source: entry.source,
        state:
          entry.state === "verified" &&
          (
            currentSession == null ||
            entry.last_verified_server_session_id == null ||
            entry.last_verified_server_session_id !== currentSession
          )
            ? "stale"
            : entry.state,
        last_verified_at: entry.last_verified_at,
        updated_at: entry.updated_at,
      }));
    return {
      revision: this.revision,
      landmarks: scored,
    };
  }

  entries() {
    return [...this.landmarks.values()].map((entry) =>
      structuredClone(entry),
    );
  }

  #prune() {
    if (this.landmarks.size <= MAX_LANDMARKS) return;
    const ordered = [...this.landmarks.entries()].sort(
      ([, left], [, right]) =>
        (left.state === "removed" ? 0 : 1) -
          (right.state === "removed" ? 0 : 1) ||
        left.updated_at.localeCompare(right.updated_at),
    );
    while (this.landmarks.size > MAX_LANDMARKS) {
      this.landmarks.delete(ordered.shift()[0]);
    }
  }

  async #persist() {
    const document = {
      schema_version: SCHEMA_VERSION,
      world_key: this.worldKey,
      companion: this.companion,
      revision: this.revision,
      saved_at: new Date().toISOString(),
      landmarks: [...this.landmarks.values()],
    };
    this.writeTail = this.writeTail.catch(() => {}).then(async () => {
      await mkdir(path.dirname(this.file), { recursive: true });
      const temporary = `${this.file}.${process.pid}.tmp`;
      await writeFile(
        temporary,
        `${JSON.stringify(document, null, 2)}\n`,
        { encoding: "utf8", mode: 0o600 },
      );
      await rename(temporary, this.file);
    });
    return this.writeTail;
  }
}
