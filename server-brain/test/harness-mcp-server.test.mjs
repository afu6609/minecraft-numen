import assert from "node:assert/strict";
import test from "node:test";

import { HarnessMcpServer } from "../src/harness-mcp-server.mjs";

test("loopback Harness MCP authenticates and exposes stable workflow tools", async (t) => {
  const runner = {
    async refreshCatalog() {
      return [{ id: "move_home", status: "candidate" }];
    },
    async run(skillId, parameters) {
      return {
        success: true,
        data: {
          async: true,
          task_id: "skill-test",
          action_id: "skill-test",
          server_session_id: "server-session-test",
          task: "run_skill",
          skill_id: skillId,
          parameters,
        },
      };
    },
  };
  const token = "test-token-with-at-least-24-characters";
  const server = new HarnessMcpServer(runner, { port: 0, token });
  const url = await server.start();
  t.after(() => server.close());

  const unauthorized = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "tools/list",
      params: {},
    }),
  });
  assert.equal(unauthorized.status, 401);

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 2,
      method: "tools/list",
      params: {},
    }),
  });
  const payload = await response.json();
  assert.deepEqual(
    payload.result.tools.map((tool) => tool.name),
    ["list_skills", "run_skill", "save_skill_candidate"],
  );
});
