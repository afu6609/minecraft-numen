import assert from "node:assert/strict";
import test from "node:test";

import {
  createCodexRuntimes,
  MomoBrain,
} from "../src/codex-brain.mjs";

function completedChatTurn() {
  return {
    items: [{
      type: "mcp_tool_call",
      server: "numen",
      tool: "send_chat",
      status: "completed",
    }],
  };
}

function completedAsyncTaskTurn(
  taskId,
  tool = "craft",
  { structured = false } = {},
) {
  const payload = {
    success: true,
    message: "accepted",
    data: {
      async: true,
      task_id: taskId,
      task: tool,
    },
  };
  return {
    items: [
      {
        id: `call-${taskId}`,
        type: "mcp_tool_call",
        server: "numen",
        tool,
        arguments: { companion: "momo" },
        status: "completed",
        result: structured
          ? { structured_content: payload, content: [] }
          : {
              content: [{ type: "text", text: JSON.stringify(payload) }],
              structured_content: null,
            },
      },
      ...completedChatTurn().items,
    ],
  };
}

function acceptedTaskWithoutChatTurn(taskId, tool = "craft") {
  return {
    items: [{
      id: `call-${taskId}`,
      type: "mcp_tool_call",
      server: "numen",
      tool,
      arguments: { companion: "momo" },
      status: "completed",
      result: {
        content: [{
          type: "text",
          text: JSON.stringify({
            success: true,
            message: "accepted",
            data: {
              async: true,
              task_id: taskId,
              action_id: "mcp-server-42",
              task: tool,
            },
          }),
        }],
      },
    }],
  };
}

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

  createCodexRuntimes(
    FakeCodex,
    {
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
    },
    "你是游戏玩家桃桃。",
  );

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
      "list_companions",
      "poll_server_events",
      "poll_companion_events",
      "create_companion",
      "delete_companion",
      "run_command",
      "report_brain_config_state",
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
  assert.equal(
    constructed[1].config.mcp_servers.numen.enabled_tools.includes(
      "embodied_survey_scene",
    ),
    true,
  );
  assert.equal(
    constructed[1].config.mcp_servers.numen.enabled_tools.includes(
      "survey_scene",
    ),
    false,
  );
  assert.match(
    constructed[1].config.developer_instructions,
    /你是游戏玩家桃桃/,
  );
  assert.match(
    constructed[1].config.developer_instructions,
    /embodied_plan_object/,
  );
});

test("model revision waits for the next logical event and resumes context", async () => {
  let selection = {
    model: "gpt-5.6-luna",
    reasoning: "high",
    revision: "1",
  };
  let releaseFirstTurn;
  let firstTurnStarted;
  const firstStarted = new Promise((resolve) => {
    firstTurnStarted = resolve;
  });
  const starts = [];
  const resumes = [];
  let oldRuns = 0;

  class FakeCodex {
    constructor() {
      this.agent = starts.length === 0 && FakeCodex.instances++ === 1;
    }

    startThread(options) {
      if (!this.agent) throw new Error("classifier is unused");
      starts.push(options);
      return {
        id: "minecraft-context",
        async run() {
          oldRuns += 1;
          if (oldRuns === 1) {
            firstTurnStarted();
            await new Promise((resolve) => {
              releaseFirstTurn = resolve;
            });
            return { items: [] };
          }
          return completedChatTurn();
        },
      };
    }

    resumeThread(id, options) {
      if (!this.agent) throw new Error("classifier is unused");
      resumes.push({ id, options });
      return {
        id,
        async run() {
          return completedChatTurn();
        },
      };
    }

    static instances = 0;
  }

  const { brain } = createCodexRuntimes(
    FakeCodex,
    {
      mcpUrl: "http://127.0.0.1:8765/mcp",
      mcpToken: "",
      agentToolTimeoutSeconds: 330,
      classifierModel: "gpt-5.4-mini",
      classifierReasoning: "low",
      agentModel: "gpt-5.6-luna",
      agentReasoning: "high",
      workingDirectory: "/srv/momo",
      companion: "momo",
      activityMode: "supervised",
    },
    "",
    {
      snapshot() {
        return selection;
      },
    },
  );

  const inFlight = brain.handle(
    { id: 1, playerName: "Alex", message: "你好" },
    { id: 1, route: "reply", reason: "greeting" },
  );
  await firstStarted;
  selection = {
    model: "gpt-5.3-codex-spark",
    reasoning: "high",
    revision: "2",
  };
  assert.equal(resumes.length, 0);
  releaseFirstTurn();
  await inFlight;

  // The corrective SDK turn belongs to the same logical event and therefore
  // remains on the already-running Luna thread.
  assert.equal(oldRuns, 2);
  assert.equal(resumes.length, 0);

  await brain.handle(
    { id: 2, playerName: "Alex", message: "再说一次" },
    { id: 2, route: "reply", reason: "follow-up" },
  );
  assert.equal(starts.length, 1);
  assert.equal(resumes.length, 1);
  assert.equal(resumes[0].id, "minecraft-context");
  assert.equal(resumes[0].options.model, "gpt-5.3-codex-spark");
  assert.equal(resumes[0].options.modelReasoningEffort, "high");
});

test("missing resumed rollout retries the same prompt once on a new thread", async () => {
  let selection = {
    model: "gpt-5.6-luna",
    reasoning: "high",
    revision: "1",
  };
  const starts = [];
  const resumes = [];
  const resumedPrompts = [];
  const fallbackPrompts = [];

  class FakeCodex {
    constructor() {
      this.agent = FakeCodex.instances++ === 1;
    }

    startThread(options) {
      if (!this.agent) throw new Error("classifier is unused");
      starts.push({ options });
      const first = starts.length === 1;
      return {
        id: first ? "missing-context" : "replacement-context",
        async run(prompt) {
          if (!first) fallbackPrompts.push(prompt);
          return completedChatTurn();
        },
      };
    }

    resumeThread(id, options) {
      resumes.push({ id, options });
      return {
        id,
        async run(prompt) {
          resumedPrompts.push(prompt);
          throw new Error(`No rollout found for thread ${id}`);
        },
      };
    }

    static instances = 0;
  }

  const { brain } = createCodexRuntimes(
    FakeCodex,
    {
      mcpUrl: "http://127.0.0.1:8765/mcp",
      mcpToken: "",
      agentToolTimeoutSeconds: 330,
      classifierModel: "gpt-5.4-mini",
      classifierReasoning: "low",
      agentModel: "gpt-5.6-luna",
      agentReasoning: "high",
      workingDirectory: "/srv/momo",
      companion: "momo",
      activityMode: "supervised",
    },
    "",
    {
      snapshot() {
        return selection;
      },
    },
  );

  await brain.handle(
    { id: 1, playerName: "Alex", message: "你好" },
    { id: 1, route: "reply", reason: "greeting" },
  );
  selection = {
    model: "gpt-5.3-codex-spark",
    reasoning: "medium",
    revision: "2",
  };
  await brain.handle(
    { id: 2, playerName: "Alex", message: "继续" },
    { id: 2, route: "reply", reason: "follow-up" },
  );

  assert.equal(resumes.length, 1);
  assert.equal(resumes[0].id, "missing-context");
  assert.equal(starts.length, 2);
  assert.equal(starts[1].options, resumes[0].options);
  assert.equal(starts[1].options.model, "gpt-5.3-codex-spark");
  assert.deepEqual(fallbackPrompts, resumedPrompts);
});

test("ordinary first resumed-turn failures are never retried", async () => {
  let selection = {
    model: "gpt-5.6-luna",
    reasoning: "high",
    revision: "1",
  };
  let starts = 0;
  let resumes = 0;

  class FakeCodex {
    constructor() {
      this.agent = FakeCodex.instances++ === 1;
    }

    startThread() {
      if (!this.agent) throw new Error("classifier is unused");
      starts += 1;
      return {
        id: "healthy-context",
        async run() {
          return completedChatTurn();
        },
      };
    }

    resumeThread(id) {
      resumes += 1;
      return {
        id,
        async run() {
          throw new Error("model capacity temporarily unavailable");
        },
      };
    }

    static instances = 0;
  }

  const { brain } = createCodexRuntimes(
    FakeCodex,
    {
      mcpUrl: "http://127.0.0.1:8765/mcp",
      mcpToken: "",
      agentToolTimeoutSeconds: 330,
      classifierModel: "gpt-5.4-mini",
      classifierReasoning: "low",
      agentModel: "gpt-5.6-luna",
      agentReasoning: "high",
      workingDirectory: "/srv/momo",
      companion: "momo",
      activityMode: "supervised",
    },
    "",
    {
      snapshot() {
        return selection;
      },
    },
  );

  await brain.handle(
    { id: 1, playerName: "Alex", message: "你好" },
    { id: 1, route: "reply", reason: "greeting" },
  );
  selection = {
    model: "gpt-5.3-codex-spark",
    reasoning: "medium",
    revision: "2",
  };
  await assert.rejects(
    brain.handle(
      { id: 2, playerName: "Alex", message: "继续" },
      { id: 2, route: "reply", reason: "follow-up" },
    ),
    /model capacity temporarily unavailable/,
  );
  assert.equal(resumes, 1);
  assert.equal(starts, 1);
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
  assert.match(prompts[0], /"playerName":"Alex"/);
  assert.match(prompts[0], /Active goal capsule/);
  assert.match(prompts[0], /"objective":"你好"/);
  assert.match(prompts[0], /get_owner_status/);
  assert.doesNotMatch(prompts[0], /你是游戏玩家桃桃/);
  assert.doesNotMatch(prompts[0], /<autonomous_action_loop>/);
});

test("an accepted gameplay task gets a direct trusted ACK without a corrective turn", async () => {
  let turns = 0;
  const chats = [];
  const brain = new MomoBrain(
    () => ({
      async run() {
        turns += 1;
        return acceptedTaskWithoutChatTurn(
          "nnav-mcp-server-42",
          "embodied_move_to",
        );
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
    "supervised",
    () => ({ revision: "static" }),
    async (companion, message, receipt) => {
      chats.push({ companion, message, receipt });
    },
  );

  await brain.handle(
    { id: 120, type: "player_chat", playerName: "Alex", message: "过来" },
    { id: 120, route: "act", reason: "move request" },
  );

  assert.equal(turns, 1);
  assert.equal(brain.hasAwaitingTask("nnav-mcp-server-42"), true);
  assert.deepEqual(chats, [{
    companion: "momo",
    message: "好，我现在过去。",
    receipt: {
      kind: "accepted_task",
      tool: "embodied_move_to",
      taskId: "nnav-mcp-server-42",
      actionId: "mcp-server-42",
      message: "好，我现在过去。",
    },
  }]);
});

test("a generic synchronous verification still needs an agent chat turn", async () => {
  let turns = 0;
  const chats = [];
  const responses = [
    {
      items: [{
        type: "mcp_tool_call",
        server: "numen",
        tool: "structure_patch",
        status: "completed",
        result: {
          structured_content: {
            success: true,
            data: {
              async: false,
              postcondition: { verified: true },
            },
          },
          content: [],
        },
      }],
    },
    completedChatTurn(),
  ];
  const brain = new MomoBrain(
    () => ({
      async run() {
        turns += 1;
        return responses.shift();
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
    "supervised",
    () => ({ revision: "static" }),
    async (_companion, message) => {
      chats.push(message);
    },
  );

  await brain.handle(
    {
      id: 121,
      type: "player_chat",
      playerName: "Alex",
      message: "把方案修一下",
    },
    { id: 121, route: "act", reason: "patch request" },
  );

  assert.equal(turns, 2);
  assert.deepEqual(chats, []);
});

test("accepted task correlation is bounded but does not expire by wall time", () => {
  const brain = new MomoBrain(
    () => ({ async run() { return completedChatTurn(); } }),
    "momo",
    "",
    "supervised",
  );

  for (let index = 1; index <= 65; index += 1) {
    brain.noteAcceptedTasks(
      acceptedTaskWithoutChatTurn(`nnav-long-${index}`),
    );
  }

  assert.equal(brain.awaitingTaskIds.size, 64);
  assert.equal(brain.hasAwaitingTask("nnav-long-1", Number.MAX_VALUE), false);
  assert.equal(brain.hasAwaitingTask("nnav-long-65", Number.MAX_VALUE), true);
});

test("a new active-work goal queues without contaminating the current goal", async () => {
  let starts = 0;
  const prompts = [[], []];
  const trustedChats = [];
  const brain = new MomoBrain(
    () => {
      const threadIndex = starts++;
      let turns = 0;
      return {
        async run(prompt) {
          prompts[threadIndex].push(prompt);
          turns += 1;
          if (threadIndex === 0 && turns === 1) {
            return acceptedTaskWithoutChatTurn(
              "nnav-active-goal",
              "embodied_follow_owner",
            );
          }
          return completedChatTurn();
        },
      };
    },
    "momo",
    "",
    "supervised",
    () => ({ revision: "static" }),
    async (_companion, message, receipt) => {
      trustedChats.push({ message, kind: receipt.kind });
    },
  );

  await brain.handle(
    {
      id: 201,
      type: "player_chat",
      playerName: "Alex",
      playerUuid: "alex",
      message: "跟着我",
    },
    {
      id: 201,
      route: "act",
      reason: "follow",
      continues_goal: false,
    },
  );
  const queued = await brain.handle(
    {
      id: 202,
      type: "player_chat",
      playerName: "Steve",
      playerUuid: "steve",
      message: "去砍一棵树",
    },
    {
      id: 202,
      route: "act",
      reason: "independent gathering request",
      continues_goal: false,
    },
  );

  assert.deepEqual(queued, { interrupted: false, queued: true });
  assert.equal(starts, 1);
  assert.equal(brain.activeGoal.objective, "跟着我");
  assert.equal(brain.pendingPlayerGoals.length, 1);

  await brain.handleTaskEvent({
    id: 203,
    type: "task_finished",
    taskId: "nnav-active-goal",
    taskName: "embodied_follow_owner",
    status: "done",
    message: "follow stopped",
  });

  assert.equal(starts, 2);
  assert.equal(brain.pendingPlayerGoals.length, 0);
  assert.equal(brain.activeGoal.objective, "去砍一棵树");
  assert.match(prompts[1][0], /去砍一棵树/);
  assert.doesNotMatch(prompts[1][0], /跟着我/);
  assert.deepEqual(trustedChats.map((entry) => entry.kind), [
    "accepted_task",
    "queued_goal",
  ]);
});

test("an explicit correction continues the active goal immediately", async () => {
  let starts = 0;
  const prompts = [];
  let turns = 0;
  const brain = new MomoBrain(
    () => {
      starts += 1;
      return {
        async run(prompt) {
          prompts.push(prompt);
          turns += 1;
          return turns === 1
            ? acceptedTaskWithoutChatTurn("nnav-correction")
            : completedChatTurn();
        },
      };
    },
    "momo",
    "",
    "supervised",
    () => ({ revision: "static" }),
    async () => {},
  );

  await brain.handle(
    {
      id: 211,
      type: "player_chat",
      playerName: "Alex",
      playerUuid: "alex",
      message: "去门口",
    },
    {
      id: 211,
      route: "act",
      reason: "move",
      continues_goal: false,
    },
  );
  await brain.handle(
    {
      id: 212,
      type: "player_chat",
      playerName: "Alex",
      playerUuid: "alex",
      message: "你卡住了，换个办法",
    },
    {
      id: 212,
      route: "act",
      reason: "current movement is stuck",
      continues_goal: true,
    },
  );

  assert.equal(starts, 1);
  assert.equal(brain.pendingPlayerGoals.length, 0);
  assert.equal(brain.activeGoal.objective, "去门口");
  assert.equal(brain.activeGoal.latest_instruction, "你卡住了，换个办法");
  assert.match(prompts[1], /"objective":"去门口"/);
  assert.match(prompts[1], /"latest_instruction":"你卡住了，换个办法"/);
});

test("a fast reply capsule preserves one speaker's follow-up referent", async () => {
  let prompt;
  const brain = new MomoBrain(
    () => ({
      async run(value) {
        prompt = value;
        return completedChatTurn();
      },
    }),
    "momo",
  );
  brain.noteFastReply(
    {
      id: 220,
      type: "player_chat",
      playerName: "Alex",
      playerUuid: "alex",
      message: "熔炉怎么合成？",
    },
    "八个圆石围一圈，中间留空。",
  );
  brain.noteFastReply(
    {
      id: 221,
      type: "player_chat",
      playerName: "Steve",
      playerUuid: "steve",
      message: "火把怎么合成？",
    },
    "煤炭或木炭放在木棍上方。",
  );

  await brain.handle(
    {
      id: 222,
      type: "player_chat",
      playerName: "Alex",
      playerUuid: "alex",
      message: "那你帮我做一个",
    },
    {
      id: 222,
      route: "act",
      reason: "craft the previously discussed item",
      continues_goal: false,
    },
  );

  assert.match(prompt, /熔炉怎么合成/);
  assert.match(prompt, /八个圆石围一圈/);
  assert.doesNotMatch(prompt, /火把怎么合成/);
});

test("console chat never invents a human body or location", async () => {
  let prompt;
  const brain = new MomoBrain(
    () => ({
      async run(received) {
        prompt = received;
        return completedChatTurn();
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
    "supervised",
  );

  await brain.handle(
    {
      id: 12,
      type: "console_chat",
      playerName: "Server",
      playerUuid: null,
      message: "看看你附近安全吗",
    },
    { id: 12, route: "act", reason: "live safety check" },
  );

  assert.match(prompt, /authenticated server-console message/);
  assert.match(prompt, /has no human player body, UUID, gaze, or world position/);
  assert.match(prompt, /get_self_status/);
  assert.match(prompt, /embodied_survey_scene with anchor_mode=self/);
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

test("supervised mode does not continue an orphan task event", async () => {
  let turns = 0;
  const brain = new MomoBrain(
    () => ({
      async run() {
        turns += 1;
        return completedChatTurn();
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
    "supervised",
  );

  const result = await brain.handleTaskEvent({
    id: 131,
    type: "task_finished",
    taskId: "old-task",
    taskName: "build",
    status: "done",
    message: "finished before this brain started",
  });

  assert.equal(turns, 0);
  assert.equal(result.held, true);
});

test("supervised fast tasks reconcile only by accepted id across idle gaps", async () => {
  const prompts = [];
  const turns = [
    completedAsyncTaskTurn("t140", "craft"),
    completedAsyncTaskTurn(
      "t141",
      "structure_execute",
      { structured: true },
    ),
    completedChatTurn(),
  ];
  const brain = new MomoBrain(
    () => ({
      async run(prompt) {
        prompts.push(prompt);
        return turns.shift();
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
    "supervised",
  );

  await brain.handle(
    {
      id: 132,
      type: "player_chat",
      playerName: "Alex",
      message: "继续建屋",
    },
    { id: 132, route: "act", reason: "continue building" },
  );

  // The accepted task completed before task_status was sampled, so the
  // point-in-time body lease already reads idle.
  brain.refreshGoalLease(false);
  const orphan = await brain.handleTaskEvent({
    id: 133,
    type: "task_finished",
    taskId: "t999",
    taskName: "build",
    status: "done",
    message: "old unrelated completion",
  });
  assert.equal(orphan.held, true);
  assert.equal(prompts.length, 1);

  await brain.handleTaskEvent({
    id: 134,
    type: "task_finished",
    taskId: "t140",
    taskName: "craft",
    status: "done",
    message: "furnace crafted",
  });
  assert.equal(prompts.length, 2);
  assert.match(prompts[1], /"taskId":"t140"/);

  brain.refreshGoalLease(false);
  await brain.handleTaskEvent({
    id: 135,
    type: "task_finished",
    taskId: "t141",
    taskName: "structure_execute",
    status: "done",
    message: "checkpoint done",
  });
  assert.equal(prompts.length, 3);

  brain.refreshGoalLease(false);
  const held = await brain.handleBodyEvent({
    id: 136,
    type: "body_available",
  });
  assert.equal(prompts.length, 3);
  assert.equal(held.held, true);
});

test("supervised mode drops orphan body telemetry instead of replaying it later", async () => {
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt) {
        prompts.push(prompt);
        return completedChatTurn();
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
    "supervised",
  );

  const held = await brain.handleBodyEvent({
    id: 134,
    type: "body_log",
    message: "stale event from before an explicit goal",
  });
  assert.equal(held.held, true);

  brain.beginExplicitGoal();
  await brain.retryBodyContext();
  assert.equal(prompts.length, 0);
});

test("inactive supervised defense telemetry leaves no pending body events", async () => {
  let turns = 0;
  const brain = new MomoBrain(
    () => ({
      async run() {
        turns += 1;
        return completedChatTurn();
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
    "supervised",
  );

  assert.equal(
    brain.noteBodyEvent({
      id: "inactive-defense-start",
      type: "defense_started",
    }),
    false,
  );
  assert.equal(
    brain.noteBodyEvent({
      id: "inactive-damage",
      type: "damage_received",
    }),
    false,
  );
  const result = await brain.handleBodyEvent({
    id: "inactive-defense-finish",
    type: "defense_finished",
  });

  assert.equal(result.held, true);
  assert.deepEqual(brain.pendingBodyEvents, []);
  brain.beginExplicitGoal();
  assert.deepEqual(
    await brain.retryBodyContext(),
    { interrupted: false, empty: true },
  );
  assert.equal(turns, 0);
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

test("repeated state mismatch trips the placement retry fuse despite moving", async () => {
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
    taskName: "embodied_move_to",
    status: "done",
    message: "arrived",
  });
  await brain.handleTaskEvent({ ...failure, id: 23, taskId: "t23" });

  assert.match(prompts[0], /"retry_allowed":true/);
  assert.match(prompts[0], /structure_status/);
  assert.match(prompts[0], /placement_feasibility/);
  assert.match(prompts[0], /structure_patch/);
  assert.match(prompts[0], /moving alone is not a changed approach/);
  assert.match(prompts[1], /"placement_failure":false/);
  assert.match(prompts[2], /"identical_failures":2/);
  assert.match(prompts[2], /"retry_allowed":false/);
  assert.match(prompts[2], /do not start another unchanged build or structure_execute/);
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

test("defense interruption defers player chat until defense finishes", async () => {
  let turns = 0;
  let unblockRecovery = false;
  let onTurnStarted = null;
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt, options) {
        turns += 1;
        prompts.push(prompt);
        onTurnStarted?.();
        if (unblockRecovery) return completedChatTurn();
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

  let started;
  const entered = new Promise((resolve) => {
    started = resolve;
  });
  onTurnStarted = started;
  const handling = brain.handle(
    {
      id: 51,
      type: "player_chat",
      playerName: "Alex",
      playerUuid: "alex-uuid",
      message: "DEFENSE_DEFERRED_GO_HOME",
    },
    { id: 51, route: "act", reason: "go home" },
  );
  await entered;
  brain.interrupt({
    preserveTaskRecovery: true,
    preservePlayerGoal: true,
  });
  assert.deepEqual(await handling, { interrupted: true });

  brain.noteBodyEvent({
    id: "body-51-start",
    type: "defense_started",
  });
  unblockRecovery = true;
  onTurnStarted = null;
  await brain.handleBodyEvent({
    id: "body-51-finished",
    type: "defense_finished",
  });

  assert.equal(turns, 2);
  assert.match(prompts[1], /DEFENSE_DEFERRED_GO_HOME/);
  assert.match(prompts[1], /"body-51-start"/);
  assert.match(prompts[1], /"body-51-finished"/);
  assert.match(prompts[1], /get_self_status and task_status/);
  assert.match(prompts[1], /do not submit a duplicate/);
});

test("death keeps one deduplicated deferred goal until the body is available", async () => {
  let turns = 0;
  let blockTurns = true;
  let onTurnStarted = null;
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt, options) {
        turns += 1;
        prompts.push(prompt);
        onTurnStarted?.();
        if (!blockTurns) return completedChatTurn();
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
  const event = {
    id: 52,
    type: "player_chat",
    playerName: "Alex",
    playerUuid: "alex-uuid",
    message: "DEATH_DEFERRED_GO_HOME_ONCE",
  };
  const decision = { id: 52, route: "act", reason: "go home" };

  async function interruptSameChat() {
    let started;
    const entered = new Promise((resolve) => {
      started = resolve;
    });
    onTurnStarted = started;
    const handling = brain.handle(event, decision);
    await entered;
    brain.interrupt({
      preserveTaskRecovery: true,
      preservePlayerGoal: true,
    });
    assert.deepEqual(await handling, { interrupted: true });
  }

  await interruptSameChat();
  await interruptSameChat();
  brain.noteBodyEvent({
    id: "body-52-death",
    type: "death",
  });

  // A death event is buffered only; unavailable-body telemetry must not spin
  // up immediate model retries.
  assert.equal(turns, 2);

  blockTurns = false;
  onTurnStarted = null;
  await brain.handleBodyEvent({
    id: "body-52-available",
    type: "body_available",
  });

  assert.equal(turns, 3);
  assert.equal(
    prompts[2].match(/DEATH_DEFERRED_GO_HOME_ONCE/g)?.length,
    1,
  );
  assert.match(prompts[2], /"body-52-death"/);
  assert.match(prompts[2], /"body-52-available"/);
});

test("body recovery consumes deferred player goals one per acknowledged turn", async () => {
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt) {
        prompts.push(prompt);
        return completedChatTurn();
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
  );
  brain.pendingPlayerGoals.push(
    {
      key: "player_chat:61",
      event: {
        id: 61,
        type: "player_chat",
        playerName: "Alex",
        message: "FIRST_DEFERRED_GOAL",
      },
      decision: { id: 61, route: "act", reason: "first" },
    },
    {
      key: "player_chat:62",
      event: {
        id: 62,
        type: "player_chat",
        playerName: "Steve",
        message: "SECOND_DEFERRED_GOAL",
      },
      decision: { id: 62, route: "act", reason: "second" },
    },
  );

  assert.deepEqual(
    await brain.handleBodyEvent({
      id: "body-multiple-goals",
      type: "defense_finished",
    }),
    { interrupted: false },
  );

  assert.equal(prompts.length, 2);
  assert.match(prompts[0], /FIRST_DEFERRED_GOAL/);
  assert.doesNotMatch(prompts[0], /SECOND_DEFERRED_GOAL/);
  assert.match(prompts[1], /SECOND_DEFERRED_GOAL/);
  assert.doesNotMatch(prompts[1], /FIRST_DEFERRED_GOAL/);
  assert.equal(brain.pendingPlayerGoals.length, 0);
});

test("unacknowledged deferred player goal remains queued for retry", async () => {
  let acknowledge = false;
  const prompts = [];
  const brain = new MomoBrain(
    () => ({
      async run(prompt) {
        prompts.push(prompt);
        return acknowledge ? completedChatTurn() : { items: [] };
      },
    }),
    "momo",
    "你是游戏玩家桃桃。",
  );
  brain.pendingPlayerGoals.push({
    key: "player_chat:63",
    event: {
      id: 63,
      type: "player_chat",
      playerName: "Alex",
      message: "RETRY_DEFERRED_GOAL",
    },
    decision: { id: 63, route: "act", reason: "retry" },
  });

  await assert.rejects(
    brain.handleBodyEvent({
      id: "body-unacknowledged-goal",
      type: "defense_finished",
    }),
    /without calling send_chat/,
  );
  assert.equal(prompts.length, 2);
  assert.equal(brain.pendingPlayerGoals.length, 1);

  acknowledge = true;
  assert.deepEqual(await brain.retryBodyContext(), { interrupted: false });
  assert.equal(prompts.length, 3);
  assert.match(prompts[2], /RETRY_DEFERRED_GOAL/);
  assert.match(prompts[2], /"body-unacknowledged-goal"/);
  assert.equal(brain.pendingPlayerGoals.length, 0);
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
    "supervised",
  );
  brain.noteAcceptedTasks(completedAsyncTaskTurn("t31", "build"));
  brain.refreshGoalLease(false);

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
