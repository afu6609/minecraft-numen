import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  AgentModelSelectionStore,
  AGENT_MODEL_CATALOG,
  BrainConfigGateway,
  decodeBrainConfigRequest,
} from "../src/model-control.mjs";

async function temporaryState(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "momo-model-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  return path.join(directory, "runtime", "model-selection.json");
}

test("model selection is atomically persisted and restored", async (t) => {
  const file = await temporaryState(t);
  const store = await AgentModelSelectionStore.open(file, {
    model: "gpt-5.6-luna",
    reasoning: "high",
  });

  assert.deepEqual(store.snapshot(), {
    model: "gpt-5.6-luna",
    reasoning: "high",
    revision: "1",
  });
  const selected = await store.set({
    model: "gpt-5.3-codex-spark",
    reasoning: "xhigh",
  });
  assert.equal(selected.revision, "2");

  const disk = JSON.parse(await readFile(file, "utf8"));
  assert.equal(disk.model, "gpt-5.3-codex-spark");
  assert.equal(disk.reasoning, "xhigh");
  assert.equal(disk.revision, 2);

  const restored = await AgentModelSelectionStore.open(file, {
    model: "gpt-5.6-luna",
    reasoning: "low",
  });
  assert.deepEqual(restored.snapshot(), selected);
  await assert.rejects(
    restored.set({
      model: "gpt-5.3-codex-spark",
      reasoning: "minimal",
    }),
    /reasoning must be one of low, medium, high, xhigh/,
  );
  assert.deepEqual(restored.snapshot(), selected);
});

test("catalog exposes only verified phase-one model combinations", () => {
  assert.deepEqual(AGENT_MODEL_CATALOG, [
    {
      model: "gpt-5.6-luna",
      reasoning: ["low", "medium", "high", "xhigh"],
    },
    {
      model: "gpt-5.3-codex-spark",
      reasoning: ["low", "medium", "high", "xhigh"],
    },
  ]);
});

test("brain config request requires an expiring atomic pair", () => {
  const expiresAtEpochMillis = Date.now() + 30_000;
  const decoded = decodeBrainConfigRequest({
    type: "brain_config_request",
    data: {
      requestId: "request-1",
      action: "set",
      model: "gpt-5.3-codex-spark",
      reasoning: "HIGH",
      expiresAtEpochMillis,
      requester: {
        kind: "player",
        name: "Haa258",
        uuid: "player-uuid",
      },
    },
  });
  assert.equal(decoded.kind, "request");
  assert.deepEqual(decoded.request, {
    requestId: "request-1",
    action: "set",
    expiresAtEpochMillis,
    model: "gpt-5.3-codex-spark",
    reasoning: "high",
    requester: {
      kind: "player",
      name: "Haa258",
      uuid: "player-uuid",
    },
  });

  assert.equal(
    decodeBrainConfigRequest({
      type: "brain_config_request",
      data: {
        requestId: "request-2",
        action: "set",
        model: "gpt-5.6-luna",
        expiresAtEpochMillis,
      },
    }).kind,
    "invalid",
  );
});

test("gateway ACKs only persisted selections with snake-case request id", async (t) => {
  const file = await temporaryState(t);
  const store = await AgentModelSelectionStore.open(file, {
    model: "gpt-5.6-luna",
    reasoning: "high",
  });
  const reports = [];
  const client = {
    async reportBrainConfigState(report) {
      reports.push(report);
    },
  };
  const gateway = new BrainConfigGateway(client, store);

  const announced = await gateway.announce();
  assert.equal(announced.success, true);
  assert.equal(announced.applied, true);
  assert.equal(Object.hasOwn(announced, "request_id"), false);

  const applied = await gateway.handle({
    requestId: "request-3",
    action: "set",
    model: "gpt-5.3-codex-spark",
    reasoning: "high",
    expiresAtEpochMillis: Date.now() + 30_000,
    requester: { kind: "console", name: "Server" },
  });
  assert.equal(applied.request_id, "request-3");
  assert.equal(Object.hasOwn(applied, "requestId"), false);
  assert.equal(applied.success, true);
  assert.equal(applied.applied, true);
  assert.equal(applied.current.model, "gpt-5.3-codex-spark");
  assert.equal(applied.current.reasoning, "high");
  assert.equal(applied.current.revision, "2");
  assert.deepEqual(applied.catalog, AGENT_MODEL_CATALOG);
  assert.deepEqual(reports.at(-1), applied);

  const rejected = await gateway.handle({
    requestId: "request-4",
    action: "set",
    model: "gpt-5.3-codex-spark",
    reasoning: "minimal",
    expiresAtEpochMillis: Date.now() + 30_000,
  });
  assert.equal(rejected.success, false);
  assert.equal(rejected.applied, false);
  assert.deepEqual(rejected.current, applied.current);
});

test("an applied ACK is retried without reapplying or expiring", async (t) => {
  const file = await temporaryState(t);
  const store = await AgentModelSelectionStore.open(file, {
    model: "gpt-5.6-luna",
    reasoning: "high",
  });
  let calls = 0;
  const reports = [];
  const gateway = new BrainConfigGateway(
    {
      async reportBrainConfigState(report) {
        calls += 1;
        reports.push(report);
        if (calls === 1) throw new Error("temporary MCP failure");
      },
    },
    store,
  );
  const request = {
    requestId: "request-retry",
    action: "set",
    model: "gpt-5.3-codex-spark",
    reasoning: "medium",
    expiresAtEpochMillis: Date.now() + 30_000,
  };

  await assert.rejects(gateway.handle(request), /temporary MCP failure/);
  assert.equal(store.snapshot().revision, "2");
  request.expiresAtEpochMillis = 1;
  const retried = await gateway.handle(request);

  assert.equal(retried.success, true);
  assert.equal(retried.applied, true);
  assert.equal(retried.current.revision, "2");
  assert.deepEqual(reports[1], reports[0]);
});

test("expired requests receive a fresh rejected state report", async (t) => {
  const file = await temporaryState(t);
  const store = await AgentModelSelectionStore.open(file, {
    model: "gpt-5.6-luna",
    reasoning: "high",
  });
  const gateway = new BrainConfigGateway(
    {
      async reportBrainConfigState() {},
    },
    store,
  );

  const result = await gateway.handle({
    requestId: "expired",
    action: "list",
    expiresAtEpochMillis: 1,
  });
  assert.equal(result.success, false);
  assert.equal(result.applied, false);
  assert.match(result.error, /expired/);
  assert.equal(result.current.revision, "1");
  assert.deepEqual(result.catalog, AGENT_MODEL_CATALOG);
});
