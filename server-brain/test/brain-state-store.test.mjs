import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { BrainStateStore } from "../src/brain-state-store.mjs";

test("brain checkpoints are atomic and collapse to the newest snapshot", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-brain-state-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const file = path.join(root, "brain.json");
  const store = await BrainStateStore.open(file);

  store.schedule({ active_goal: { goal_id: "first" }, awaiting_tasks: [] });
  store.schedule({
    active_goal: { goal_id: "latest" },
    awaiting_tasks: [{ task_id: "task-1" }],
  });
  await store.flush();

  const restored = await BrainStateStore.open(file);
  assert.deepEqual(restored.restoredState(), {
    active_goal: { goal_id: "latest" },
    awaiting_tasks: [{ task_id: "task-1" }],
  });
  store.scheduleInbox({ items: [{ id: "queued-1" }] });
  await store.flush();
  const reopened = await BrainStateStore.open(file);
  assert.deepEqual(reopened.restoredInbox(), {
    items: [{ id: "queued-1" }],
  });
  store.scheduleTransport({
    server_events: [{ id: 7, type: "test_instruction" }],
  });
  await store.flush();
  const withTransport = await BrainStateStore.open(file);
  assert.deepEqual(withTransport.restoredTransport(), {
    server_events: [{ id: 7, type: "test_instruction" }],
  });

  const document = JSON.parse(await readFile(file, "utf8"));
  assert.equal(document.schema_version, 1);
  assert.equal(typeof document.saved_at, "string");
});

test("unknown brain-state schemas fail closed", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-brain-state-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const file = path.join(root, "brain.json");
  await import("node:fs/promises").then(({ writeFile }) =>
    writeFile(file, '{"schema_version":99,"brain":{}}\n', "utf8"),
  );

  await assert.rejects(BrainStateStore.open(file), /unsupported brain state schema/);
});

test("interleaved brain and inbox checkpoints preserve both newest parts", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-brain-state-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const file = path.join(root, "brain.json");
  const store = await BrainStateStore.open(file);

  // The first large write yields in mkdir/writeFile, so all following updates
  // exercise the merge path while a previous checkpoint is still in flight.
  store.scheduleBrain({
    revision: 0,
    padding: "x".repeat(256 * 1024),
  });
  for (let revision = 1; revision <= 40; revision += 1) {
    store.scheduleInbox({
      revision,
      items: [{ queueId: revision }],
    });
    store.scheduleBrain({
      revision,
      awaiting_tasks: [{ task_id: `task-${revision}` }],
    });
    store.scheduleTransport({
      revision,
      server_events: [{ id: revision, type: "brain_config_request" }],
    });
  }
  await store.flush();

  const restored = await BrainStateStore.open(file);
  assert.deepEqual(restored.restoredBrain(), {
    revision: 40,
    awaiting_tasks: [{ task_id: "task-40" }],
  });
  assert.deepEqual(restored.restoredInbox(), {
    revision: 40,
    items: [{ queueId: 40 }],
  });
  assert.deepEqual(restored.restoredTransport(), {
    revision: 40,
    server_events: [{ id: 40, type: "brain_config_request" }],
  });
});
