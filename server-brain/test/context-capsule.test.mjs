import assert from "node:assert/strict";
import test from "node:test";

import { ContextCapsule } from "../src/context-capsule.mjs";

function toolTurn(data) {
  return {
    items: [{
      type: "mcp_tool_call",
      server: "numen",
      tool: "get_self_status",
      arguments: { companion: "momo" },
      status: "completed",
      result: {
        content: [{
          type: "text",
          text: JSON.stringify({
            success: true,
            message: "status",
            data,
          }),
        }],
      },
    }],
  };
}

function surveyTurn(scene) {
  return {
    items: [{
      type: "mcp_tool_call",
      server: "numen",
      tool: "embodied_survey_scene",
      arguments: { companion: "momo", anchor_mode: "self" },
      status: "completed",
      result: {
        content: [{
          type: "text",
          text: JSON.stringify({
            success: true,
            message: "Semantic scene captured.",
            data: {
              scene,
              intent: "inspect the work site",
            },
          }),
        }],
      },
    }],
  };
}

function sceneObject(
  object_id,
  {
    kind = "tree",
    label = "oak tree",
    min_x = 1,
    protection = "editable_natural",
  } = {},
) {
  return {
    object_id,
    kind,
    label,
    confidence: 0.94,
    bounds: {
      min_x,
      min_y: 64,
      min_z: 1,
      max_x: min_x + 2,
      max_y: 70,
      max_z: 3,
    },
    provenance: "natural",
    provenance_confidence: 0.9,
    protection,
    features: { trunk_height: 5 },
    selections: [{
      role: "harvest_logs",
      action: "break",
      cell_count: 5,
      purpose: "harvest this tree",
    }],
  };
}

test("context capsule emits only changed tool-result deltas", () => {
  const capsule = new ContextCapsule();
  assert.equal(capsule.noteTurn(toolTurn({ hp: 20, hunger: 18 })).length, 1);
  const first = capsule.promptView();
  assert.equal(first.observations.length, 1);
  assert.equal(first.observations[0].summary.data.hp, 20);

  assert.equal(capsule.noteTurn(toolTurn({ hp: 20, hunger: 18 })).length, 0);
  assert.deepEqual(capsule.promptView().observations, []);

  assert.equal(capsule.noteTurn(toolTurn({ hp: 17, hunger: 18 })).length, 1);
  const changed = capsule.promptView();
  assert.equal(changed.observations[0].summary.data.hp, 17);
  assert.notEqual(changed.observations[0].previous_hash, null);
});

test("context capsule survives a journal round trip", () => {
  const capsule = new ContextCapsule();
  capsule.noteTurn(toolTurn({ hp: 20 }));
  const restored = new ContextCapsule(capsule.snapshot());
  const full = restored.promptView({ full: true });

  assert.equal(full.mode, "full_compact_snapshot");
  assert.equal(full.revision, 1);
  assert.equal(full.observations[0].summary.data.hp, 20);
});

test("volume capsules retain only bounds, palette summary, and a cell hash", () => {
  const capsule = new ContextCapsule();
  capsule.noteTurn({
    items: [{
      type: "mcp_tool_call",
      server: "numen",
      tool: "observe_volume",
      arguments: { min_x: 0, min_y: 64, min_z: 0 },
      status: "completed",
      result: {
        content: [{
          type: "text",
          text: JSON.stringify({
            bounds: { min: { x: 0, y: 64, z: 0 } },
            volume: 2,
            palette: [{ block: "minecraft:stone" }],
            cells: [[0, 64, 0, 0], [1, 64, 0, 0]],
            returned_cells: 2,
          }),
        }],
      },
    }],
  });
  const [observation] = capsule.promptView().observations;
  assert.equal(observation.summary.data.returned_cells, 2);
  assert.equal(typeof observation.summary.data.cells_hash, "string");
  assert.equal(Object.hasOwn(observation.summary.data, "cells"), false);
});

test("semantic scene capsules retain real object cards and exact object deltas", () => {
  const capsule = new ContextCapsule();
  const baseScene = {
    scene_id: "scene-momo-1",
    revision: 1,
    dimension: "minecraft:overworld",
    anchor_source: "self",
    anchor: { x: 0, y: 64, z: 0 },
    bounds: {
      min_x: -16,
      min_y: 52,
      min_z: -16,
      max_x: 16,
      max_y: 76,
      max_z: 16,
    },
    sampled_cells: 20_000,
    unloaded_columns: 0,
    complete: true,
    object_counts: { tree: 1, constructed_structure: 1 },
    objects: [
      sceneObject("tree-1"),
      sceneObject("house-1", {
        kind: "constructed_structure",
        label: "small wooden house",
        min_x: 8,
        protection: "preserve_by_default",
      }),
    ],
    next_step: "Inspect one object.",
  };

  const [first] = capsule.noteTurn(surveyTurn(baseScene));
  assert.equal(first.summary.data.revision, 1);
  assert.equal(first.summary.data.scene.object_count, 2);
  assert.deepEqual(
    first.summary.data.scene.objects.map((object) => object.object_id),
    ["tree-1", "house-1"],
  );
  assert.equal(
    first.summary.data.scene.objects[1].protection,
    "preserve_by_default",
  );
  assert.equal(first.scene_delta, null);

  const nextScene = {
    ...baseScene,
    scene_id: "scene-momo-2",
    revision: 2,
    object_counts: { tree: 1, pit: 1 },
    objects: [
      sceneObject("tree-1", { min_x: 2 }),
      sceneObject("pit-1", {
        kind: "pit",
        label: "shallow pit",
        min_x: -5,
        protection: "editable_terrain",
      }),
    ],
  };
  const [second] = capsule.noteTurn(surveyTurn(nextScene));

  assert.deepEqual(second.scene_delta, {
    from_revision: 1,
    to_revision: 2,
    added: ["pit-1"],
    removed: ["house-1"],
    changed: ["tree-1"],
  });
  assert.equal(
    JSON.stringify(second.summary).includes("sampled_cells"),
    false,
  );
  assert.equal(
    JSON.stringify(second.summary).includes("identityCells"),
    false,
  );
});
