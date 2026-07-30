import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { GoalBudget, HarnessTrace } from "../src/harness-trace.mjs";

test("goal budget blocks only future turns after its exact limit", () => {
  const budget = new GoalBudget({
    maxTurns: 2,
    maxToolCalls: 3,
    maxRecoveryTurns: 1,
    maxElapsedMs: 60_000,
  });
  budget.begin("goal-1", "trace-1", 1_000);
  budget.noteTurn({ toolCalls: 1, elapsedMs: 10 });
  assert.equal(budget.allowance({ now: 1_100 }).allowed, true);
  budget.noteTurn({ toolCalls: 1, elapsedMs: 10, recovery: true });
  assert.deepEqual(budget.allowance({ now: 1_200 }), {
    allowed: false,
    reason: "model_turn_budget",
  });
  budget.renew(2_000);
  assert.equal(budget.allowance({ now: 2_001 }).allowed, true);
});

test("goal budget counts active Harness time rather than idle wall time", () => {
  const budget = new GoalBudget({
    maxTurns: 4,
    maxToolCalls: 4,
    maxRecoveryTurns: 2,
    maxElapsedMs: 1_000,
  });
  budget.begin("goal-idle", "trace-idle", 1_000);
  budget.noteTurn({ toolCalls: 1, elapsedMs: 100 });

  assert.deepEqual(budget.allowance({ now: 86_401_000 }), {
    allowed: true,
    reason: null,
  });
  assert.equal(budget.promptView(86_401_000).remaining_elapsed_ms, 900);
});

test("trace links ids without persisting prompt or tool arguments", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-trace-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const file = path.join(root, "trace.ndjson");
  const trace = new HarnessTrace(file);
  trace.record("tool.accepted", {
    goal_id: "goal-1",
    trace_id: "trace-1",
    turn_id: "turn-1",
    tool: "mine",
    task_id: "t1",
  });
  await trace.flush();

  const entry = JSON.parse((await readFile(file, "utf8")).trim());
  assert.equal(entry.task_id, "t1");
  assert.equal(Object.hasOwn(entry, "prompt"), false);
  assert.equal(Object.hasOwn(entry, "arguments"), false);
});

test("trace rotates at its configured bound", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-trace-rotate-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const file = path.join(root, "trace.ndjson");
  const trace = new HarnessTrace(file, { maxBytes: 1_024 });

  for (let index = 0; index < 24; index += 1) {
    trace.record("turn.completed", {
      goal_id: `goal-${index}`,
      trace_id: `trace-${index}`,
      turn_id: `turn-${index}`,
      profile: "regional_edit",
      tool_calls: 12,
      elapsed_ms: 250,
    });
  }
  await trace.flush();

  assert.ok((await stat(file)).size <= 1_024);
  assert.ok((await stat(`${file}.1`)).size <= 1_024);
});
