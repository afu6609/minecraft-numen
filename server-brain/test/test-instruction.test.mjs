import assert from "node:assert/strict";
import test from "node:test";

import {
  decodeTestInstructionEvent,
  dispatchFreshTestInstruction,
} from "../src/arena-event.mjs";

const valid = {
  id: 81,
  type: "test_instruction",
  companionUuid: "1bca6b55-2072-4ab3-8214-00b848e21c8b",
  companionName: "momo",
  runId: "arena-81",
  message: "defeat the zombie without leaving the arena",
  arenaAnchor: {
    dimension: "minecraft:overworld",
    x: 120,
    y: 72,
    z: -40,
  },
};

test("decodes a targeted test instruction and defaults to a fresh context", () => {
  const decoded = decodeTestInstructionEvent(valid, "momo");

  assert.equal(decoded.kind, "test_instruction");
  assert.equal(decoded.event.runId, "arena-81");
  assert.equal(decoded.event.freshThread, true);
  assert.deepEqual(decoded.event.arenaAnchor, valid.arenaAnchor);
});

test("explicit continuation is preserved and UUID targeting is accepted", () => {
  const decoded = decodeTestInstructionEvent(
    {
      ...valid,
      companionName: "someone-else",
      freshThread: false,
    },
    valid.companionUuid,
  );

  assert.equal(decoded.kind, "test_instruction");
  assert.equal(decoded.event.freshThread, false);
});

test("rejects missing anchors and instructions for another companion", () => {
  assert.deepEqual(
    decodeTestInstructionEvent(
      { ...valid, arenaAnchor: { dimension: "minecraft:overworld", x: 0 } },
      "momo",
    ),
    {
      kind: "invalid",
      reason: "test instruction has no explicit arena anchor",
    },
  );
  assert.deepEqual(decodeTestInstructionEvent(valid, "alex"), {
    kind: "invalid",
    reason: "test instruction targets a different companion",
  });
});

test("fresh instruction stays undispatched until the old server task stops", async () => {
  let stopAttempts = 0;
  let interrupts = 0;
  const calls = [];
  const client = {
    async stopTask() {
      stopAttempts += 1;
      if (stopAttempts === 1) throw new Error("temporary MCP failure");
    },
  };
  const brain = {
    interrupt() {
      interrupts += 1;
      return true;
    },
  };
  const inbox = {
    beginFreshTestRun(id) {
      calls.push(["begin", id]);
    },
    push(item) {
      calls.push(["push", item.event.runId]);
      return true;
    },
  };

  const blocked = await dispatchFreshTestInstruction({
    client,
    companion: "momo",
    event: valid,
    inbox,
    brain,
  });
  assert.equal(blocked.ok, false);
  assert.deepEqual(calls, [["begin", 81]]);

  const accepted = await dispatchFreshTestInstruction({
    client,
    companion: "momo",
    event: valid,
    inbox,
    brain,
  });
  assert.equal(accepted.ok, true);
  assert.equal(stopAttempts, 2);
  assert.equal(interrupts, 2);
  assert.deepEqual(calls, [
    ["begin", 81],
    ["begin", 81],
    ["push", "arena-81"],
  ]);
});

test("an authoritatively idle body satisfies the fresh test stop fence", async () => {
  const calls = [];
  const result = await dispatchFreshTestInstruction({
    client: {
      async stopTask() {
        throw new Error("没有进行中的后台任务,不需要叫停。");
      },
    },
    companion: "momo",
    event: valid,
    brain: {
      interrupt() {
        return false;
      },
    },
    inbox: {
      beginFreshTestRun() {
        calls.push("begin");
      },
      push() {
        calls.push("push");
        return true;
      },
    },
  });

  assert.equal(result.ok, true);
  assert.deepEqual(calls, ["begin", "push"]);
});
