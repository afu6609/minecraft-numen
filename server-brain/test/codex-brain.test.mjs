import assert from "node:assert/strict";
import test from "node:test";

import {
  createCodexRuntimes,
  MomoBrain,
} from "../src/codex-brain.mjs";

test("Codex runtimes isolate the classifier and expose only Numen to the agent", () => {
  const constructed = [];
  class FakeCodex {
    constructor(options) {
      constructed.push(options);
    }

    startThread() {
      throw new Error("not used by this test");
    }
  }

  createCodexRuntimes(FakeCodex, {
    codexPath: "/usr/local/bin/codex",
    mcpUrl: "http://127.0.0.1:8765/mcp",
    mcpToken: "secret",
    agentToolTimeoutSeconds: 330,
    classifierModel: "small",
    agentModel: "main",
    classifierReasoning: "low",
    agentReasoning: "medium",
    workingDirectory: "/srv/momo",
    companion: "momo",
  });

  assert.equal(constructed.length, 2);
  assert.equal(constructed[0].config.features.shell_tool, false);
  assert.equal(constructed[0].config.mcp_servers, undefined);
  assert.equal(
    constructed[1].config.mcp_servers.numen.bearer_token_env_var,
    "NUMEN_MCP_TOKEN",
  );
  assert.equal(
    constructed[1].config.mcp_servers.numen.default_tools_approval_mode,
    "approve",
  );
  assert.deepEqual(
    constructed[1].config.mcp_servers.numen.disabled_tools,
    [
      "poll_server_events",
      "poll_companion_events",
      "create_companion",
      "delete_companion",
      "run_command",
    ],
  );
  assert.equal(
    constructed[1].config.mcp_servers.numen.disabled_tools.includes(
      "structure_patch",
    ),
    false,
  );
  assert.equal(
    constructed[1].config.mcp_servers.numen.disabled_tools.includes(
      "placement_feasibility",
    ),
    false,
  );
});

test("brain makes one corrective turn when the agent forgets visible chat", async () => {
  let turns = 0;
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt) {
        turns += 1;
        prompts.push(prompt);
        return {
          items:
            turns === 1
              ? []
              : [
                  {
                    type: "mcp_tool_call",
                    server: "numen",
                    tool: "send_chat",
                    status: "completed",
                  },
                ],
        };
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
  );

  await brain.handle(
    { id: 12, playerName: "Alex", message: "你好" },
    { id: 12, route: "reply", reason: "greeting" },
  );

  assert.equal(turns, 2);
  assert.match(prompts[0], /你是游戏玩家桃桃/);
  assert.match(prompts[0], /"playerName":"Alex"/);
  assert.match(prompts[0], /playerName and playerUuid/);
  assert.match(prompts[0], /observe_volume/);
  assert.match(prompts[0], /mine tool is resource gathering only/);
  assert.match(prompts[0], /structure_plan once/);
  assert.match(prompts[0], /material ledger/);
  assert.match(prompts[0], /structure_execute/);
  assert.match(prompts[0], /structure_patch/);
  assert.match(prompts[0], /placement_feasibility/);
  assert.match(prompts[0], /expected_revision/);
  assert.match(prompts[0], /never resend its whole blueprint/);
  assert.match(prompts[0], /saved coordinates/);
  assert.match(prompts[0], /submit the concrete next action before send_chat/);
  assert.match(prompts[0], /Only after an action tool has actually returned/);
});

test("task completion returns to the persistent brain for verification", async () => {
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt) {
        prompts.push(prompt);
        return {
          items: [{
            type: "mcp_tool_call",
            server: "numen",
            tool: "send_chat",
            status: "completed",
          }],
        };
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
  );

  await brain.handleTaskEvent({
    id: 13,
    type: "task_finished",
    taskId: "t7",
    taskName: "goto",
    status: "done",
    message: "arrived",
  });

  assert.equal(prompts.length, 1);
  assert.match(prompts[0], /Re-perceive the live world/);
  assert.match(prompts[0], /"taskId":"t7"/);
  assert.match(prompts[0], /"message":"arrived"/);
  assert.match(prompts[0], /Submit that next action before reporting a retry/);
  assert.match(prompts[0], /actually returns an accepted task_id/);
});

test("trusted test instruction gets a fresh high-level context and normal survival tools", async () => {
  let starts = 0;
  const prompts = [];
  const brain = new MomoBrain(
    () => {
      starts += 1;
      return {
        async run(prompt) {
          prompts.push(prompt);
          return {
            items: [{
              type: "mcp_tool_call",
              server: "numen",
              tool: "send_chat",
              status: "completed",
            }],
          };
        },
      };
    },
    "momo",
    "你是游戏玩家桃桃。",
  );

  await brain.handle(
    { id: 70, playerName: "Alex", message: "你好" },
    { id: 70, route: "reply", reason: "greeting" },
  );
  await brain.handleTestInstruction({
    id: 71,
    type: "test_instruction",
    companionName: "momo",
    runId: "arena-71",
    message: "击杀前面的僵尸并保证存活",
    arenaAnchor: {
      dimension: "minecraft:overworld",
      x: 120,
      y: 72,
      z: -40,
    },
    freshThread: true,
  });

  assert.equal(starts, 2);
  assert.equal(prompts.length, 2);
  assert.match(prompts[1], /arena-71/);
  assert.match(prompts[1], /minecraft:overworld/);
  assert.match(prompts[1], /normal Numen perception and survival action tools/);
  assert.match(prompts[1], /no fixture, spawn, teleport, give, setblock, kill/);
  assert.match(prompts[1], /server safety supervisor/);
  assert.match(prompts[1], /Start at most one background task/);
});

test("test continuation can deliberately reuse the current context", async () => {
  let starts = 0;
  const prompts = [];
  const brain = new MomoBrain(
    () => {
      starts += 1;
      return {
        async run(prompt) {
          prompts.push(prompt);
          return {
            items: [{
              type: "mcp_tool_call",
              server: "numen",
              tool: "send_chat",
              status: "completed",
            }],
          };
        },
      };
    },
    "momo",
  );
  const base = {
    type: "test_instruction",
    companionName: "momo",
    runId: "arena-72",
    arenaAnchor: {
      dimension: "minecraft:overworld",
      x: 0,
      y: 64,
      z: 0,
    },
  };

  await brain.handleTestInstruction({
    ...base,
    id: 72,
    message: "观察测试目标",
    freshThread: true,
  });
  await brain.handleTestInstruction({
    ...base,
    id: 73,
    message: "继续刚才的方案",
    freshThread: false,
  });

  assert.equal(starts, 1);
  assert.equal(prompts.length, 2);
  assert.match(prompts[1], /继续刚才的方案/);
});

test("repeated state mismatch trips the placement retry fuse despite goto", async () => {
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt) {
        prompts.push(prompt);
        return {
          items: [{
            type: "mcp_tool_call",
            server: "numen",
            tool: "send_chat",
            status: "completed",
          }],
        };
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
  );
  const failure = {
    type: "task_finished",
    taskName: "build",
    status: "failed",
    message:
      "STATE_MISMATCH target=222,127,-418 requested=minecraft:white_bed[facing=east,occupied=false,part=foot]; message=reachable clicks create another state",
  };

  await brain.handleTaskEvent({ ...failure, id: 21, taskId: "t21" });
  await brain.handleTaskEvent({
    id: 22,
    type: "task_finished",
    taskId: "t22",
    taskName: "goto",
    status: "done",
    message: "arrived",
  });
  await brain.handleTaskEvent({ ...failure, id: 23, taskId: "t23" });

  assert.match(prompts[0], /"retry_allowed":true/);
  assert.match(prompts[0], /structure_status/);
  assert.match(prompts[0], /placement_feasibility/);
  assert.match(prompts[0], /structure_patch/);
  assert.match(prompts[0], /goto alone is not a changed approach/);
  assert.match(prompts[1], /"placement_failure":false/);
  assert.match(prompts[2], /"identical_failures":2/);
  assert.match(prompts[2], /"retry_allowed":false/);
  assert.match(prompts[2], /do not start another unchanged goto\/build\/structure_execute/);
});

test("a changed requested state creates a new placement failure signature", () => {
  const brain = new MomoBrain(() => {
    throw new Error("not used");
  }, "momo");
  const base = {
    type: "task_finished",
    taskName: "build",
    status: "failed",
  };

  const first = brain.noteTaskFailure({
    ...base,
    message:
      "STATE_MISMATCH target=224,127,-418 requested=minecraft:chest[facing=north,type=single,waterlogged=false]; message=reachable clicks create another state",
  }, 1_000);
  const changed = brain.noteTaskFailure({
    ...base,
    message:
      "STATE_MISMATCH target=224,127,-418 requested=minecraft:chest[facing=west,type=single,waterlogged=false]; message=reachable clicks create another state",
  }, 1_001);

  assert.equal(first.identical_failures, 1);
  assert.equal(changed.identical_failures, 1);
  assert.equal(changed.retry_allowed, true);
});

test("same requested state at different coordinates has separate retry budgets", () => {
  const brain = new MomoBrain(() => {
    throw new Error("not used");
  }, "momo");
  const first = brain.noteTaskFailure({
    taskName: "build",
    status: "timeout",
    message:
      "STATE_MISMATCH target=222,127,-418 requested=minecraft:white_bed[facing=east,occupied=false,part=foot]; message=timed out",
  }, 1_000);
  const second = brain.noteTaskFailure({
    taskName: "build",
    status: "failed",
    message:
      "STATE_MISMATCH target=224,127,-418 requested=minecraft:white_bed[facing=east,occupied=false,part=foot]; message=failed",
  }, 1_001);

  assert.equal(first.identical_failures, 1);
  assert.equal(second.identical_failures, 1);
  assert.equal(second.position, "224,127,-418");
});

test("structured live placement reasons are normalized for recovery", () => {
  const brain = new MomoBrain(() => {
    throw new Error("not used");
  }, "momo");
  const occluded = brain.noteTaskFailure({
    taskName: "build",
    status: "failed",
    message:
      "NO_LINE_OF_SIGHT target=224,127,-418 requested=minecraft:chest[facing=west,type=single,waterlogged=false]; message=cannot stay under the crosshair",
  }, 1_000);
  const blocked = brain.noteTaskFailure({
    taskName: "build",
    status: "failed",
    message:
      "FOOTPRINT_BLOCKED target=223,127,-417 requested=minecraft:white_bed[facing=east,occupied=false,part=foot]; message=footprint is occupied",
  }, 1_001);

  assert.equal(occluded.reason, "OCCLUDED");
  assert.equal(blocked.reason, "FOOTPRINT_BLOCKED");
});

test("a completed structure patch refreshes the placement retry budget", async () => {
  const brain = new MomoBrain(
    () => ({
      async run() {
        return {
          items: [
            {
              type: "mcp_tool_call",
              server: "numen",
              tool: "structure_patch",
              status: "completed",
            },
            {
              type: "mcp_tool_call",
              server: "numen",
              tool: "send_chat",
              status: "completed",
            },
          ],
        };
      },
    }),
    "momo",
  );
  const failure = {
    id: 24,
    taskName: "build",
    status: "failed",
    message:
      "STATE_MISMATCH target=222,127,-418 requested=minecraft:white_bed[facing=east,occupied=false,part=foot]; message=failed",
  };

  await brain.handleTaskEvent(failure);
  const afterPatch = brain.noteTaskFailure({ ...failure, id: 25 }, 2_000);

  assert.equal(afterPatch.identical_failures, 1);
  assert.equal(afterPatch.retry_allowed, true);
});

test("body telemetry is coalesced and re-grounded without forced chat", async () => {
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt) {
        prompts.push(prompt);
        return { items: [] };
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
  );

  brain.noteBodyEvent({
    id: "body-1",
    type: "damage_received",
    data: { source_type: "minecraft:skeleton" },
  });
  brain.noteBodyEvent({
    id: "body-2",
    type: "defense_started",
    data: { engaged_threats: 2 },
  });
  const result = await brain.handleBodyEvent({
    id: "body-3",
    type: "defense_finished",
    data: { displaced_distance: 8.5 },
  });

  assert.deepEqual(result, { interrupted: false });
  assert.equal(prompts.length, 1);
  assert.match(prompts[0], /get_self_status and task_status/);
  assert.match(prompts[0], /structure_status/);
  assert.match(prompts[0], /"body-1"/);
  assert.match(prompts[0], /"body-3"/);
});

test("transient body turn failures stay queued for an in-process retry", async () => {
  let turns = 0;
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt) {
        turns += 1;
        prompts.push(prompt);
        if (turns === 1) {
          throw new Error("Selected model is at capacity");
        }
        return { items: [] };
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
  );

  await assert.rejects(
    brain.handleBodyEvent({
      id: "body-capacity",
      type: "body_available",
    }),
    /at capacity/,
  );
  assert.deepEqual(await brain.retryBodyContext(), { interrupted: false });
  assert.equal(turns, 2);
  assert.match(prompts[1], /"body-capacity"/);
});

test("direct control interrupts an active Codex turn without a corrective retry", async () => {
  let turns = 0;
  let started;
  const entered = new Promise((resolve) => {
    started = resolve;
  });
  const brain = new MomoBrain(
    () => ({
      async run(_prompt, options) {
        turns += 1;
        started();
        return await new Promise((resolve, reject) => {
          options.signal.addEventListener(
            "abort",
            () => reject(new Error("aborted")),
            { once: true },
          );
        });
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
  );

  const handling = brain.handle(
    { id: 14, playerName: "Alex", message: "跟着我" },
    { id: 14, route: "act", reason: "follow request" },
  );
  await entered;
  assert.equal(brain.interrupt(), true);
  assert.deepEqual(await handling, { interrupted: true });
  assert.equal(turns, 1);
});

test("an interrupted task failure is carried into body recovery", async () => {
  let turns = 0;
  let started;
  const entered = new Promise((resolve) => {
    started = resolve;
  });
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt, options) {
        turns += 1;
        prompts.push(prompt);
        if (turns === 1) {
          started();
          return await new Promise((resolve, reject) => {
            options.signal.addEventListener(
              "abort",
              () => reject(new Error("aborted")),
              { once: true },
            );
          });
        }
        return { items: [] };
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
  );

  const handling = brain.handleTaskEvent({
    id: 31,
    type: "task_finished",
    taskId: "t31",
    taskName: "build",
    status: "failed",
    message:
      "STATE_MISMATCH target=222,127,-418 requested=minecraft:white_bed[facing=east,occupied=false,part=foot]; message=reachable clicks create another state",
  });
  await entered;
  brain.interrupt({ preserveTaskRecovery: true });
  assert.deepEqual(await handling, { interrupted: true });

  await brain.handleBodyEvent({
    id: "body-31",
    type: "defense_finished",
  });

  assert.equal(turns, 2);
  assert.match(prompts[1], /Interrupted task events/);
  assert.match(prompts[1], /"taskId":"t31"/);
  assert.match(prompts[1], /placement_feasibility/);
  assert.match(prompts[1], /structure_patch/);
});

test("a direct stop does not revive an interrupted task later", async () => {
  let turns = 0;
  let started;
  const entered = new Promise((resolve) => {
    started = resolve;
  });
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt, options) {
        turns += 1;
        prompts.push(prompt);
        if (turns === 1) {
          started();
          return await new Promise((resolve, reject) => {
            options.signal.addEventListener(
              "abort",
              () => reject(new Error("aborted")),
              { once: true },
            );
          });
        }
        return { items: [] };
      },
    }),
    "momo",
  );

  const handling = brain.handleTaskEvent({
    id: 41,
    taskId: "must-not-revive",
    taskName: "build",
    status: "failed",
    message:
      "STATE_MISMATCH target=222,127,-418 requested=minecraft:white_bed[facing=east,occupied=false,part=foot]; message=failed",
  });
  await entered;
  brain.interrupt({ preserveTaskRecovery: false });
  assert.deepEqual(await handling, { interrupted: true });

  await brain.handleBodyEvent({ id: "body-41", type: "body_available" });

  assert.equal(turns, 2);
  assert.doesNotMatch(prompts[1], /must-not-revive/);
});

test("interrupting the chat-only follow-up does not replay a handled task", async () => {
  let turns = 0;
  let secondStarted;
  const enteredSecond = new Promise((resolve) => {
    secondStarted = resolve;
  });
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt, options) {
        turns += 1;
        prompts.push(prompt);
        if (turns === 1) {
          return { items: [] };
        }
        if (turns === 2) {
          secondStarted();
          return await new Promise((resolve, reject) => {
            options.signal.addEventListener(
              "abort",
              () => reject(new Error("aborted")),
              { once: true },
            );
          });
        }
        return { items: [] };
      },
    }),
    "momo",
  );

  const handling = brain.handleTaskEvent({
    id: 42,
    taskId: "already-handled",
    taskName: "build",
    status: "failed",
    message:
      "STATE_MISMATCH target=222,127,-418 requested=minecraft:white_bed[facing=east,occupied=false,part=foot]; message=failed",
  });
  await enteredSecond;
  brain.interrupt({ preserveTaskRecovery: true });
  assert.deepEqual(await handling, { interrupted: true });

  await brain.handleBodyEvent({ id: "body-42", type: "body_available" });

  assert.equal(turns, 3);
  assert.doesNotMatch(prompts[2], /already-handled/);
});
