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
    classifierReasoning: "minimal",
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
  assert.deepEqual(
    constructed[1].config.mcp_servers.numen.disabled_tools,
    ["poll_server_events", "create_companion", "delete_companion"],
  );
});

test("brain makes one corrective turn when the agent forgets visible chat", async () => {
  let turns = 0;
  const brain = new MomoBrain(
    () => ({
      async run() {
        turns += 1;
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
  );

  await brain.handle(
    { id: 12, playerName: "Alex", message: "你好" },
    { id: 12, route: "reply", reason: "greeting" },
  );

  assert.equal(turns, 2);
});
