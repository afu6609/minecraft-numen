import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { LandmarkStore } from "../src/landmark-store.mjs";

function structureTurn(tool, payload) {
  return {
    items: [{
      type: "mcp_tool_call",
      server: "numen",
      tool,
      status: "completed",
      result: {
        content: [{ type: "text", text: JSON.stringify(payload) }],
      },
    }],
  };
}

function workflowPayload(overrides = {}) {
  return {
    workflow_id: "structure-house-1",
    revision: 1,
    name: "momo_oak_cottage",
    goal: "建造一座带床和门的木屋",
    dimension: "minecraft:overworld",
    operation: "build",
    phase: "needs_materials",
    bounds: {
      min: { x: 10, y: 64, z: 20 },
      max: { x: 17, y: 70, z: 27 },
    },
    ...overrides,
  };
}

async function temporaryStore(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-landmarks-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  return {
    file: path.join(root, "landmarks.json"),
    store: await LandmarkStore.open(path.join(root, "landmarks.json"), {
      worldKey: "private-world",
      companion: "momo",
    }),
  };
}

test("structure workflow evidence persists as a semantic landmark", async (t) => {
  const { file, store } = await temporaryStore(t);
  await store.noteTurn(
    structureTurn("structure_plan", workflowPayload()),
    {
      serverSessionId: "session-a",
      sourceEventKey: "player_chat:7",
      objective: "给自己建一个木屋",
    },
  );

  const [planned] = store.entries();
  assert.equal(planned.state, "planned");
  assert.equal(planned.dimension, "minecraft:overworld");
  assert.equal(planned.aliases.includes("房子"), true);
  assert.equal(planned.aliases.includes("木屋"), true);

  const reopened = await LandmarkStore.open(file, {
    worldKey: "private-world",
    companion: "momo",
  });
  assert.deepEqual(reopened.entries(), store.entries());
});

test("only exact structure status completion verifies a landmark", async (t) => {
  const { store } = await temporaryStore(t);
  await store.noteTurn(structureTurn("structure_plan", workflowPayload()));
  await store.noteTurn(
    structureTurn("structure_status", workflowPayload({ phase: "complete" })),
    { serverSessionId: "session-a" },
  );

  assert.equal(store.entries()[0].state, "verified");
  assert.equal(store.promptView({
    query: "带我去你的房子",
    serverSessionId: "session-a",
  }).landmarks[0].state, "verified");
  assert.equal(store.promptView({
    query: "带我去你的房子",
    serverSessionId: "session-b",
  }).landmarks[0].state, "stale");
  assert.equal(store.promptView({
    query: "带我去你的房子",
  }).landmarks[0].state, "stale");

  await store.noteTurn(
    structureTurn("structure_patch", workflowPayload({ revision: 2 })),
  );
  assert.equal(store.entries()[0].state, "planned");
});

test("completed demolition removes the old landmark from prompts", async (t) => {
  const { store } = await temporaryStore(t);
  await store.noteTurn(structureTurn("structure_plan", workflowPayload()));
  await store.noteTurn(
    structureTurn(
      "structure_status",
      workflowPayload({ operation: "demolish", phase: "complete" }),
    ),
  );

  assert.equal(store.entries()[0].state, "removed");
  assert.deepEqual(store.promptView({ query: "房子" }).landmarks, []);
});
