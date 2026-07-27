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
      "create_companion",
      "delete_companion",
      "run_command",
    ],
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
});
