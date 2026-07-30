import { createHash } from "node:crypto";

const MAX_ENTRIES = 16;
const MAX_PENDING_DELTAS = 12;
const MAX_ARRAY_ITEMS = 8;
const MAX_OBJECT_KEYS = 28;
const MAX_STRING_LENGTH = 240;
const MAX_SCENE_OBJECTS = 12;

function parsedPayloads(result) {
  const payloads = [];
  const add = (value) => {
    if (value != null && typeof value === "object" && !Array.isArray(value)) {
      payloads.push(value);
    }
  };
  const parseText = (text) => {
    if (typeof text !== "string") return;
    try {
      add(JSON.parse(text));
    } catch {
      // Plain-text legacy results are represented by a bounded message below.
    }
  };
  if (typeof result === "string") {
    parseText(result);
    return payloads;
  }
  if (result == null || typeof result !== "object") return payloads;
  if (typeof result.success === "boolean") add(result);
  add(result.structured_content);
  add(result.structuredContent);
  for (const block of Array.isArray(result.content) ? result.content : []) {
    if (block?.type === "text") parseText(block.text);
  }
  return payloads;
}

function bounded(value, depth = 0) {
  if (value == null || typeof value === "boolean" || typeof value === "number") {
    return value;
  }
  if (typeof value === "string") {
    return value.length <= MAX_STRING_LENGTH
      ? value
      : `${value.slice(0, MAX_STRING_LENGTH - 1)}…`;
  }
  if (depth >= 4) {
    if (Array.isArray(value)) return `[${value.length} items]`;
    return "{…}";
  }
  if (Array.isArray(value)) {
    const items = value
      .slice(0, MAX_ARRAY_ITEMS)
      .map((item) => bounded(item, depth + 1));
    if (value.length > MAX_ARRAY_ITEMS) {
      items.push({ omitted_items: value.length - MAX_ARRAY_ITEMS });
    }
    return items;
  }
  if (typeof value === "object") {
    const output = {};
    const entries = Object.entries(value).sort(([left], [right]) =>
      left.localeCompare(right),
    );
    for (const [key, item] of entries.slice(0, MAX_OBJECT_KEYS)) {
      output[key] = bounded(item, depth + 1);
    }
    if (entries.length > MAX_OBJECT_KEYS) {
      output.omitted_keys = entries.length - MAX_OBJECT_KEYS;
    }
    return output;
  }
  return String(value);
}

function digest(value) {
  return createHash("sha256")
    .update(JSON.stringify(value))
    .digest("hex")
    .slice(0, 16);
}

function sceneObjectCard(object) {
  return {
    object_id: object?.object_id ?? null,
    kind: object?.kind ?? object?.type ?? null,
    label: object?.label ?? object?.name ?? null,
    confidence: object?.confidence ?? null,
    bounds: bounded(object?.bounds),
    centroid: bounded(object?.centroid ?? object?.center),
    protection: bounded(
      object?.protection ?? {
        preserve_by_default: object?.preserve_by_default,
        editable: object?.editable,
        provenance: object?.provenance,
      },
    ),
    selections: bounded(
      object?.selections ?? object?.selection_handles ?? object?.parts,
    ),
  };
}

function surveySceneSummary(payload, data) {
  const scene =
    data?.scene != null && typeof data.scene === "object"
      ? data.scene
      : data;
  const objects = Array.isArray(scene?.objects) ? scene.objects : [];
  return {
    success: payload.success !== false,
    message: bounded(payload.message),
    data: {
      revision: data?.revision ?? scene?.revision ?? null,
      anchor: bounded(data?.anchor ?? scene?.anchor),
      bounds: bounded(data?.bounds ?? scene?.bounds),
      scene: {
        summary: bounded(scene?.summary),
        object_count: objects.length,
        objects: objects
          .slice(0, MAX_SCENE_OBJECTS)
          .map(sceneObjectCard),
        omitted_objects: Math.max(0, objects.length - MAX_SCENE_OBJECTS),
      },
    },
  };
}

function resultSummary(item) {
  const payload = parsedPayloads(item.result)[0];
  if (payload != null) {
    const data =
      payload.data != null && typeof payload.data === "object"
        ? payload.data
        : payload;
    if (item.tool === "embodied_survey_scene") {
      return surveySceneSummary(payload, data);
    }
    if (item.tool === "observe_volume") {
      return bounded({
        success: payload.success !== false,
        message: payload.message,
        data: {
          bounds: data.bounds,
          volume: data.volume,
          palette: data.palette,
          matching_cells: data.matching_cells,
          returned_cells: data.returned_cells,
          truncated: data.truncated,
          unloaded_cells: data.unloaded_cells,
          cells_hash: Array.isArray(data.cells) ? digest(data.cells) : null,
        },
      });
    }
    if (
      ["get_self_status", "get_owner_status", "get_player_status"].includes(
        item.tool,
      )
    ) {
      const inventory = {};
      for (const stack of Array.isArray(data?.inventory?.items)
        ? data.inventory.items
        : []) {
        if (typeof stack?.item !== "string") continue;
        inventory[stack.item] =
          (inventory[stack.item] ?? 0) + Number(stack.count ?? 1);
      }
      return bounded({
        success: payload.success !== false,
        message: payload.message,
        data: {
          name: data.name,
          hp: data.hp,
          max_hp: data.max_hp,
          hunger: data.hunger,
          armor: data.armor,
          position: data.position,
          dimension: data.dimension,
          biome: data.biome,
          equipment: data.equipment,
          effects: data.effects,
          inventory,
          on_ground: data.on_ground,
          in_water: data.in_water,
          in_lava: data.in_lava,
        },
      });
    }
    return bounded({
      success: payload.success !== false,
      message: payload.message,
      data,
    });
  }
  const text = item.result?.content?.find(
    (entry) => entry?.type === "text" && typeof entry.text === "string",
  )?.text;
  return bounded({
    success: item.result?.isError !== true,
    message: text ?? "completed without structured content",
  });
}

function semanticSceneDelta(previous, current) {
  const oldData = previous?.summary?.data?.scene;
  const nextData = current?.data?.scene;
  if (
    !Array.isArray(oldData?.objects) ||
    !Array.isArray(nextData?.objects)
  ) {
    return null;
  }
  const oldObjects = new Map(
    oldData.objects
      .filter((object) => typeof object?.object_id === "string")
      .map((object) => [object.object_id, digest(object)]),
  );
  const nextObjects = new Map(
    nextData.objects
      .filter((object) => typeof object?.object_id === "string")
      .map((object) => [object.object_id, digest(object)]),
  );
  return {
    from_revision: previous?.summary?.data?.revision ?? null,
    to_revision: current?.data?.revision ?? null,
    added: [...nextObjects.keys()].filter((id) => !oldObjects.has(id)),
    removed: [...oldObjects.keys()].filter((id) => !nextObjects.has(id)),
    changed: [...nextObjects.keys()].filter(
      (id) => oldObjects.has(id) && oldObjects.get(id) !== nextObjects.get(id),
    ),
  };
}

function observationKey(item, summary) {
  const data = summary?.data;
  const identity =
    data?.object_id ??
    data?.workflow_id ??
    data?.plan_id ??
    data?.job_id ??
    data?.task_id ??
    data?.entity_id ??
    item.arguments?.object_id ??
    item.arguments?.workflow_id ??
    item.arguments?.task_id ??
    item.arguments?.entity_id ??
    item.arguments?.anchor_mode ??
    item.arguments?.player ??
    item.arguments?.companion ??
    "latest";
  return `${item.server ?? "mcp"}.${item.tool}:${String(identity)}`;
}

export class ContextCapsule {
  constructor(restored = null) {
    this.revision = Number.isInteger(restored?.revision)
      ? Math.max(0, restored.revision)
      : 0;
    this.entries = new Map();
    this.pending = [];
    for (const entry of Array.isArray(restored?.entries)
      ? restored.entries.slice(-MAX_ENTRIES)
      : []) {
      if (
        typeof entry?.key !== "string" ||
        typeof entry?.hash !== "string" ||
        entry.summary == null
      ) {
        continue;
      }
      const tool = String(entry.tool ?? "unknown");
      this.entries.set(entry.key, {
        key: entry.key,
        tool,
        hash: entry.hash,
        revision: Number.isInteger(entry.revision) ? entry.revision : 0,
        captured_at:
          typeof entry.captured_at === "string" ? entry.captured_at : null,
        summary:
          tool === "embodied_survey_scene"
            ? surveySceneSummary(entry.summary, entry.summary?.data)
            : bounded(entry.summary),
      });
    }
  }

  noteTurn(turn) {
    const deltas = [];
    for (const item of Array.isArray(turn?.items) ? turn.items : []) {
      if (
        item?.type !== "mcp_tool_call" ||
        item.status !== "completed" ||
        item.tool === "send_chat"
      ) {
        continue;
      }
      const summary = resultSummary(item);
      const hash = digest(summary);
      const key = observationKey(item, summary);
      const previous = this.entries.get(key);
      if (previous?.hash === hash) continue;
      this.revision += 1;
      const entry = {
        key,
        tool: String(item.tool ?? "unknown"),
        hash,
        revision: this.revision,
        captured_at: new Date().toISOString(),
        summary,
      };
      this.entries.delete(key);
      this.entries.set(key, entry);
      deltas.push({
        key,
        tool: entry.tool,
        revision: entry.revision,
        previous_hash: previous?.hash ?? null,
        hash,
        summary,
        ...(item.tool === "embodied_survey_scene"
          ? { scene_delta: semanticSceneDelta(previous, summary) }
          : {}),
      });
    }
    while (this.entries.size > MAX_ENTRIES) {
      this.entries.delete(this.entries.keys().next().value);
    }
    this.pending.push(...deltas);
    if (this.pending.length > MAX_PENDING_DELTAS) {
      this.pending.splice(0, this.pending.length - MAX_PENDING_DELTAS);
    }
    return deltas;
  }

  promptView({ full = false } = {}) {
    const view = full
      ? {
          mode: "full_compact_snapshot",
          revision: this.revision,
          observations: [...this.entries.values()].map(
            ({ hash: _hash, ...entry }) => entry,
          ),
        }
      : {
          mode: "delta_since_previous_turn",
          revision: this.revision,
          observations: this.pending.map(({ hash: _hash, ...entry }) => entry),
        };
    this.pending = [];
    return view;
  }

  snapshot() {
    return {
      revision: this.revision,
      entries: [...this.entries.values()].map((entry) =>
        structuredClone(entry),
      ),
    };
  }
}
