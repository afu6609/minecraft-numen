import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { MomoBrain } from "../src/codex-brain.mjs";
import { ExperienceStore } from "../src/experience-store.mjs";
import { SkillRunner } from "../src/skill-runner.mjs";

function moveSkill() {
  return {
    id: "move_to_checkpoint",
    intent: "Move to a previously verified checkpoint.",
    description: "A parameterized native movement workflow.",
    parameters: { x: 8, y: 64, z: 4 },
    preconditions: ["Momo is healthy enough to walk."],
    precondition_verifiers: [{ type: "health_at_least", value: 8 }],
    steps: [
      {
        tool: "get_world_info",
        args: {},
        expect: "the world remains available",
        verifiers: [{ type: "tool_success" }],
      },
      {
        tool: "embodied_move_to",
        args: { x: "${x}", y: "${y}", z: "${z}" },
        expect: "native movement reaches the checkpoint",
        verifiers: [
          { type: "tool_success" },
          { type: "task_terminal_done" },
        ],
      },
    ],
    postconditions: ["Momo is within one block of the checkpoint."],
    postcondition_verifiers: [{
      type: "position_within",
      x: "${x}",
      y: "${y}",
      z: "${z}",
      max_distance: 1,
    }],
    recovery: ["stop and replan if native movement fails"],
    provenance: { source_task: "test" },
    validations: { successes: 0, failures: 0 },
  };
}

function moveClient(calls, {
  taskId = "nnav-inner-1",
  actionId = "mcp-inner-1",
  serverSessionId = "server-session-1",
  beforeMove = null,
  malformedWorld = false,
} = {}) {
  let statusReads = 0;
  return {
    async callToolJson(tool, args) {
      calls.push({ tool, args });
      if (tool === "get_self_status") {
        statusReads += 1;
        return {
          success: true,
          data: {
            hp: 20,
            position:
              statusReads === 1
                ? { x: 0, y: 64, z: 0 }
                : { x: 8, y: 64, z: 4 },
            inventory: { items: [] },
          },
        };
      }
      if (tool === "task_status") {
        return {
          success: true,
          data: {
            state: "idle",
            server_session_id: serverSessionId,
          },
        };
      }
      if (tool === "embodied_nav_status") {
        return { success: true, data: { state: "idle" } };
      }
      if (tool === "get_world_info") {
        return {
          ...(malformedWorld ? {} : { success: true }),
          data: { time: 6_000 },
        };
      }
      if (tool === "embodied_move_to") {
        beforeMove?.();
        return {
          success: true,
          data: {
            async: true,
            task_id: taskId,
            action_id: actionId,
            server_session_id: serverSessionId,
          },
        };
      }
      if (tool === "embodied_nav_stop") {
        return { success: true, data: { stopped: taskId } };
      }
      throw new Error(`unexpected ${tool}`);
    },
  };
}

function recipeLookupSkill() {
  return {
    id: "lookup_recipe_once",
    intent: "Look up one recipe without changing the world.",
    description: "A phase-safe crafting lookup with typed verification.",
    parameters: { item: "minecraft:torch" },
    preconditions: ["Momo is healthy enough to inspect recipes."],
    precondition_verifiers: [{ type: "health_at_least", value: 8 }],
    steps: [{
      tool: "lookup_recipe",
      args: { item: "${item}" },
      expect: "the recipe lookup succeeds",
      verifiers: [{ type: "tool_success" }],
    }],
    postconditions: ["the referenced workflow marker remains ready"],
    postcondition_verifiers: [{
      type: "structure_phase",
      workflow_id: "verification-only",
      phase: "ready",
    }],
    recovery: ["report that the recipe or verification state was unavailable"],
    provenance: { source_task: "test" },
    validations: { successes: 0, failures: 0 },
  };
}

function crossPhaseStructureSkill() {
  return {
    id: "cross_phase_structure",
    intent: "Attempt a structure workflow from a different capability phase.",
    description: "A security regression fixture that must be rejected.",
    parameters: { workflow_id: "structure-1" },
    preconditions: ["Momo is healthy enough to work."],
    precondition_verifiers: [{ type: "health_at_least", value: 8 }],
    steps: [{
      tool: "structure_execute",
      args: { workflow_id: "${workflow_id}" },
      expect: "the structure workflow completes",
      verifiers: [
        { type: "tool_success" },
        { type: "task_terminal_done" },
      ],
    }],
    postconditions: ["the structure workflow is complete"],
    postcondition_verifiers: [{ type: "body_idle" }],
    recovery: ["stop if the current capability phase does not permit building"],
    provenance: { source_task: "test" },
    validations: { successes: 0, failures: 0 },
  };
}

function partiallyAllowedCrossPhaseSkill() {
  const skill = crossPhaseStructureSkill();
  return {
    ...skill,
    id: "partial_cross_phase_structure",
    steps: [
      {
        tool: "lookup_recipe",
        args: { item: "minecraft:crafting_table" },
        expect: "the allowed first step would succeed",
        verifiers: [{ type: "tool_success" }],
      },
      ...skill.steps,
    ],
  };
}

test("skill runner pauses on one exact async task and emits one macro terminal", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(moveSkill());
  const calls = [];
  const client = moveClient(calls);
  const terminals = [];
  const runner = new SkillRunner(store, client, "momo", {
    emitTerminal: (event) => terminals.push(event),
  });
  runner.setServerSessionId("server-session-1");
  runner.setInvocationContext({
    goalId: "player_chat:7",
    traceId: "trace-7",
    profile: "orient",
  });

  const accepted = await runner.run("move_to_checkpoint", {
    x: 8,
    y: 64,
    z: 4,
  });
  assert.equal(accepted.data.async, true);
  assert.match(accepted.data.task_id, /^skill-/);
  assert.equal(runner.snapshot().waiting_task_id, "nnav-inner-1");
  assert.deepEqual(
    await runner.handleTaskEvent({
      id: 8,
      type: "task_finished",
      taskId: "unrelated",
      status: "done",
    }),
    { claimed: false },
  );

  const result = await runner.handleTaskEvent({
    id: 9,
    type: "task_finished",
    taskId: "nnav-inner-1",
    status: "done",
  });
  assert.deepEqual(result, { claimed: true, state: "completed" });
  assert.equal(terminals.length, 1);
  assert.equal(terminals[0].taskId, accepted.data.task_id);
  assert.equal(terminals[0].status, "done");
  assert.equal(runner.snapshot(), null);
  assert.deepEqual(
    calls.find((entry) => entry.tool === "embodied_move_to").args,
    { x: 8, y: 64, z: 4, companion: "momo" },
  );
  assert.equal((await store.get("move_to_checkpoint")).validations.successes, 1);
});

test("authoritative idle finalizes a cancelled waiting skill without its terminal event", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(moveSkill());
  const terminals = [];
  const runner = new SkillRunner(store, moveClient([]), "momo", {
    emitTerminal: (event) => terminals.push(event),
  });
  runner.setServerSessionId("server-session-1");

  const accepted = await runner.run("move_to_checkpoint", {
    x: 8,
    y: 64,
    z: 4,
  });
  assert.equal(runner.cancelActive("fresh test run"), true);
  const reconciled = await runner.reconcileRuntimeState(
    {
      serverSessionId: "server-session-1",
      activeTaskIds: [],
    },
    { reason: "test_authoritative_stop" },
  );

  assert.equal(reconciled.finalizedCancellation, true);
  assert.equal(runner.snapshot(), null);
  assert.equal(terminals.length, 1);
  assert.equal(terminals[0].taskId, accepted.data.task_id);
  assert.equal(terminals[0].status, "stopped");
});

test("bounded waiting lease stops a live inner task then finalizes at idle", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(moveSkill());
  const calls = [];
  const terminals = [];
  const runner = new SkillRunner(store, moveClient(calls), "momo", {
    emitTerminal: (event) => terminals.push(event),
    maxWaitMs: 1_000,
  });
  runner.setServerSessionId("server-session-1");

  await runner.run("move_to_checkpoint", { x: 8, y: 64, z: 4 });
  const waitingSince = runner.snapshot().waiting_since;
  const expired = await runner.reconcileRuntimeState(
    {
      serverSessionId: "server-session-1",
      activeTaskIds: ["nnav-inner-1"],
    },
    { now: waitingSince + 1_001, reason: "test_lease_expired" },
  );
  assert.equal(expired.stopRequested, true);
  assert.equal(runner.snapshot().cancel_requested, true);
  assert.equal(
    calls.some((entry) => entry.tool === "embodied_nav_stop"),
    true,
  );
  const retried = await runner.reconcileRuntimeState(
    {
      serverSessionId: "server-session-1",
      activeTaskIds: ["nnav-inner-1"],
    },
    { now: waitingSince + 1_002, reason: "test_stop_retry" },
  );
  assert.equal(retried.cancellationPending, true);
  assert.equal(
    calls.filter((entry) => entry.tool === "embodied_nav_stop").length,
    2,
  );

  const finalized = await runner.reconcileRuntimeState(
    {
      serverSessionId: "server-session-1",
      activeTaskIds: [],
    },
    { now: waitingSince + 1_003, reason: "test_lease_idle" },
  );
  assert.equal(finalized.finalizedCancellation, true);
  assert.equal(runner.snapshot(), null);
  assert.equal(terminals.at(-1).status, "stopped");
});

test("restored skill state never replays a missing inner action", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(moveSkill());
  const terminals = [];
  const runner = new SkillRunner(store, {
    async callToolJson() {
      throw new Error("recovery must not replay a tool");
    },
  }, "momo", {
    restored: {
      macro_task_id: "skill-restored",
      skill_id: "move_to_checkpoint",
      skill_version: 1,
      parameters: { x: 8, y: 64, z: 4 },
      step_index: 1,
      waiting_task_id: "nnav-missing",
      waiting_tool: "embodied_move_to",
      waiting_action_id: "mcp-missing",
      started_at: 1,
      tool_elapsed_ms: 10,
      started_async: true,
      server_session_id: "server-session-1",
    },
    emitTerminal: (event) => terminals.push(event),
  });

  const recovery = await runner.reconcileLiveState({
    serverSessionId: "server-session-1",
    activeTaskIds: ["some-other-task"],
  });
  assert.deepEqual(recovery, { active: false, recoveredFailure: true });
  assert.equal(terminals.length, 1);
  assert.equal(terminals[0].status, "unknown_after_restart");
});

test("runtime server session change releases an active skill without replay", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(moveSkill());
  const terminals = [];
  const runner = new SkillRunner(store, {
    async callToolJson() {
      throw new Error("session invalidation must not replay a tool");
    },
  }, "momo", {
    restored: {
      macro_task_id: "skill-old-session",
      skill_id: "move_to_checkpoint",
      skill_version: 1,
      parameters: { x: 8, y: 64, z: 4 },
      step_index: 1,
      waiting_task_id: "nnav-colliding",
      waiting_tool: "embodied_move_to",
      waiting_action_id: "mcp-old-session",
      started_at: 1,
      tool_elapsed_ms: 10,
      started_async: true,
      server_session_id: "server-session-old",
    },
    emitTerminal: (event) => terminals.push(event),
  });

  const result = await runner.synchronizeServerSession(
    "server-session-new",
    { reason: "test_runtime_restart" },
  );

  assert.deepEqual(result, {
    changed: true,
    invalidatedMacroTaskId: "skill-old-session",
  });
  assert.equal(runner.snapshot(), null);
  assert.equal(terminals.length, 1);
  assert.equal(terminals[0].taskId, "skill-old-session");
  assert.equal(terminals[0].status, "unknown_after_restart");
  assert.equal(terminals[0].serverSessionId, "server-session-new");
});

test("concurrent run_skill invocations dispatch only one body action", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const backingStore = new ExperienceStore(root);
  await backingStore.saveCandidate(moveSkill());
  let releaseGet;
  const getGate = new Promise((resolve) => {
    releaseGet = resolve;
  });
  const store = {
    async get(id) {
      await getGate;
      return backingStore.get(id);
    },
    list: (...args) => backingStore.list(...args),
    recordValidation: (...args) => backingStore.recordValidation(...args),
  };
  const calls = [];
  const runner = new SkillRunner(store, moveClient(calls), "momo");
  runner.setServerSessionId("server-session-1");

  const first = runner.run("move_to_checkpoint", { x: 8, y: 64, z: 4 });
  const second = runner.run("move_to_checkpoint", { x: 8, y: 64, z: 4 });
  await assert.rejects(second, /already being prepared/);
  releaseGet();
  const accepted = await first;

  assert.equal(accepted.data.async, true);
  assert.equal(
    calls.filter((entry) => entry.tool === "embodied_move_to").length,
    1,
  );
});

test("catalog refresh failure cannot suppress a committed macro terminal", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const backingStore = new ExperienceStore(root);
  await backingStore.saveCandidate(moveSkill());
  const store = {
    get: (...args) => backingStore.get(...args),
    recordValidation: (...args) => backingStore.recordValidation(...args),
    async list() {
      throw new Error("catalog storage temporarily unavailable");
    },
  };
  const calls = [];
  const terminals = [];
  const runner = new SkillRunner(store, moveClient(calls), "momo", {
    emitTerminal: (event) => terminals.push(event),
  });
  runner.setServerSessionId("server-session-1");

  const accepted = await runner.run("move_to_checkpoint", {
    x: 8,
    y: 64,
    z: 4,
  });
  const result = await runner.handleTaskEvent({
    id: 19,
    type: "task_finished",
    companionName: "momo",
    taskId: "nnav-inner-1",
    actionId: "mcp-inner-1",
    taskName: "embodied_move_to",
    status: "done",
  });

  assert.deepEqual(result, { claimed: true, state: "completed" });
  assert.equal(terminals.length, 1);
  assert.equal(terminals[0].taskId, accepted.data.task_id);
  assert.equal(terminals[0].status, "done");
  assert.equal(runner.snapshot(), null);
});

test("body_idle durably adopts the authoritative server session before dispatch", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(moveSkill());
  const calls = [];
  let pendingSnapshot = null;
  const durableSnapshots = [];
  const sessionChanges = [];
  const runner = new SkillRunner(
    store,
    moveClient(calls, {
      serverSessionId: "server-session-new",
      beforeMove() {
        assert.equal(
          durableSnapshots.at(-1)?.server_session_id,
          "server-session-new",
        );
      },
    }),
    "momo",
    {
      onChange(snapshot) {
        pendingSnapshot = structuredClone(snapshot);
      },
      async durableCheckpoint() {
        durableSnapshots.push(structuredClone(pendingSnapshot));
      },
      onServerSessionChange(serverSessionId, previousServerSessionId) {
        sessionChanges.push({
          serverSessionId,
          previousServerSessionId,
        });
      },
    },
  );
  runner.setServerSessionId("server-session-old");

  const accepted = await runner.run("move_to_checkpoint", {
    x: 8,
    y: 64,
    z: 4,
  });

  assert.equal(accepted.data.async, true);
  assert.equal(runner.snapshot().server_session_id, "server-session-new");
  assert.deepEqual(sessionChanges, [
    {
      serverSessionId: "server-session-old",
      previousServerSessionId: null,
    },
    {
      serverSessionId: "server-session-new",
      previousServerSessionId: "server-session-old",
    },
  ]);
});

test("restart recovery publishes an already committed validation result", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(moveSkill());
  await store.recordValidation("move_to_checkpoint", {
    success: true,
    evidence: "postconditions committed before the process stopped",
    executionId: "skill-committed",
    expectedVersion: 1,
  });
  const terminals = [];
  const runner = new SkillRunner(store, {
    async callToolJson() {
      throw new Error("committed recovery must not inspect or replay the body");
    },
  }, "momo", {
    restored: {
      macro_task_id: "skill-committed",
      skill_id: "move_to_checkpoint",
      skill_version: 1,
      parameters: { x: 8, y: 64, z: 4 },
      step_index: 2,
      waiting_task_id: null,
      waiting_tool: null,
      started_at: 1,
      tool_elapsed_ms: 10,
      started_async: true,
      server_session_id: "server-session-1",
    },
    emitTerminal: (event) => terminals.push(event),
  });

  const recovery = await runner.reconcileLiveState({
    serverSessionId: "server-session-1",
    activeTaskIds: [],
  });

  assert.deepEqual(recovery, {
    active: false,
    terminalReplayed: true,
    committed: true,
  });
  assert.equal(terminals.length, 1);
  assert.equal(terminals[0].taskId, "skill-committed");
  assert.equal(terminals[0].status, "done");
  assert.equal(runner.snapshot(), null);
});

test("run_skill is confined to its active capability profile", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(recipeLookupSkill());
  await store.saveCandidate(crossPhaseStructureSkill());
  await store.saveCandidate(partiallyAllowedCrossPhaseSkill());
  const goalId = "player_chat:phase-boundary";
  const brain = new MomoBrain(
    () => {
      throw new Error("model thread is not used by this test");
    },
    "momo",
    "",
    "supervised",
    () => ({ revision: "static" }),
    null,
    {
      restoredState: {
        snapshot_version: 1,
        companion: "momo",
        active_goal: {
          goal_id: goalId,
          trace_id: "trace-phase-boundary",
        },
        active_profile: "craft",
        thread_profile: "craft",
        goal_budget: {
          goal_id: goalId,
          trace_id: "trace-phase-boundary",
          turns: 0,
          tool_calls: 0,
          recovery_turns: 0,
          model_elapsed_ms: 0,
        },
      },
    },
  );
  const calls = [];
  const client = {
    async callToolJson(tool, args) {
      calls.push({ tool, args });
      if (tool === "get_self_status") {
        return {
          success: true,
          data: {
            hp: 20,
            position: { x: 0, y: 64, z: 0 },
            inventory: { items: [] },
          },
        };
      }
      if (tool === "task_status") {
        return {
          success: true,
          data: {
            state: "idle",
            server_session_id: "server-session-1",
          },
        };
      }
      if (tool === "embodied_nav_status") {
        return { success: true, data: { state: "idle" } };
      }
      if (tool === "lookup_recipe") {
        return {
          success: true,
          data: { item: args.item, recipe: ["minecraft:coal", "minecraft:stick"] },
        };
      }
      if (tool === "structure_status") {
        return { success: true, data: { phase: "ready" } };
      }
      if (tool === "structure_execute") {
        throw new Error("cross-phase mutating tool reached the MCP client");
      }
      throw new Error(`unexpected ${tool}`);
    },
  };
  const runner = new SkillRunner(store, client, "momo", {
    authorizeToolCall: ({
      goalId: requestedGoal,
      profile,
      tool,
      reserve,
    }) =>
      brain.reserveSkillToolCall(requestedGoal, {
        profile,
        tool,
        reserve,
      }),
  });
  await runner.refreshCatalog();
  runner.setServerSessionId("server-session-1");
  runner.setInvocationContext({
    goalId,
    traceId: "trace-phase-boundary",
    profile: "craft",
  });

  const legal = await runner.run("lookup_recipe_once", {
    item: "minecraft:torch",
  });
  assert.equal(legal.success, true);
  assert.equal(
    calls.some((entry) => entry.tool === "embodied_nav_status"),
    true,
  );
  assert.equal(
    calls.some((entry) => entry.tool === "structure_status"),
    true,
  );

  await assert.rejects(
    runner.run("cross_phase_structure", {
      workflow_id: "structure-1",
    }),
    /skill_tool_not_allowed_for_profile:craft/,
  );
  assert.equal(
    calls.some((entry) => entry.tool === "structure_execute"),
    false,
  );
  const callsBeforePartial = calls.length;
  await assert.rejects(
    runner.run("partial_cross_phase_structure", {
      workflow_id: "structure-1",
    }),
    /skill definition rejected before execution.*structure_execute/,
  );
  assert.equal(
    calls.length,
    callsBeforePartial,
    "preflight must reject the whole workflow before its allowed first step",
  );
  assert.deepEqual(
    runner.catalog({ profile: "craft", goalId }).map((entry) => entry.id),
    ["lookup_recipe_once"],
  );
  assert.equal(
    brain.reserveSkillToolCall(goalId, {
      profile: "structure",
      tool: "structure_execute",
    }).reason,
    "skill_profile_is_not_active",
  );
});

test("typed tool_success rejects envelopes without explicit success", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-skill-runner-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(moveSkill());
  const calls = [];
  const runner = new SkillRunner(
    store,
    moveClient(calls, { malformedWorld: true }),
    "momo",
  );
  runner.setServerSessionId("server-session-1");

  await assert.rejects(
    runner.run("move_to_checkpoint", { x: 8, y: 64, z: 4 }),
    /skill tool get_world_info failed/,
  );
  assert.equal(
    calls.some((entry) => entry.tool === "embodied_move_to"),
    false,
  );
});
