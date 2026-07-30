import assert from "node:assert/strict";
import { createServer } from "node:http";
import test from "node:test";

import { NumenMcpClient } from "../src/mcp-client.mjs";

test("MCP client authenticates and parses queued events", async (t) => {
  const requests = [];
  const server = createServer((request, response) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => {
      const rpc = JSON.parse(body);
      requests.push({ rpc, authorization: request.headers.authorization });
      const text = JSON.stringify([
        { id: 3, type: "player_chat", playerName: "Alex", message: "hi" },
      ]);
      response.setHeader("Content-Type", "application/json");
      response.end(
        JSON.stringify({
          jsonrpc: "2.0",
          id: rpc.id,
          result: {
            content: [{ type: "text", text }],
            isError: false,
          },
        }),
      );
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();
  const client = new NumenMcpClient(
    `http://127.0.0.1:${address.port}/mcp`,
    { token: "secret" },
  );

  const events = await client.pollServerEvents(4);
  const bodyEvents = await client.pollCompanionEvents("momo", 6);
  await client.reportBrainConfigState({
    request_id: "request-1",
    success: true,
    applied: true,
    current: {
      model: "gpt-5.3-codex-spark",
      reasoning: "high",
      revision: "2",
    },
    catalog: [],
  });
  await client.runCommand(
    "momo",
    "/time set day",
    "server-event:server-session-a:42",
  );

  assert.equal(events[0].message, "hi");
  assert.equal(bodyEvents[0].message, "hi");
  assert.equal(requests[0].rpc.params.name, "poll_server_events");
  assert.equal(requests[1].rpc.params.name, "poll_companion_events");
  assert.equal(requests[1].rpc.params.arguments.companion, "momo");
  assert.equal(requests[1].rpc.params.arguments.limit, 6);
  assert.equal(requests[2].rpc.params.name, "report_brain_config_state");
  assert.equal(requests[2].rpc.params.arguments.request_id, "request-1");
  assert.equal(requests[2].rpc.params.arguments.requestId, undefined);
  assert.equal(requests[3].rpc.params.name, "run_command");
  assert.equal(
    requests[3].rpc.params.arguments.request_id,
    "server-event:server-session-a:42",
  );
  assert.equal(requests[0].authorization, "Bearer secret");
});

test("live body inspection returns exact generic and native task ids", async () => {
  const responses = [
    {
      result: {
        content: [{
          type: "text",
          text: JSON.stringify({
            success: true,
            data: {
              task_id: "t41",
              task: "mine",
              state: "running",
              server_session_id: "server-session-1",
            },
          }),
        }],
      },
    },
    {
      result: {
        content: [{
          type: "text",
          text: JSON.stringify({
            success: true,
            data: {
              task_id: "nnav-42",
              action_id: "mcp-42",
              state: "moving",
            },
          }),
        }],
      },
    },
    {
      result: {
        content: [{
          type: "text",
          text: JSON.stringify({
            success: true,
            data: {
              job: {
                job_id: "job-43",
                external_task_id: "job-task-43",
                state: "running",
              },
            },
          }),
        }],
      },
    },
  ];
  const client = new NumenMcpClient("http://127.0.0.1:8765/mcp", {
    fetchImpl: async () => ({
      ok: true,
      async json() {
        return { jsonrpc: "2.0", id: 1, ...responses.shift() };
      },
    }),
  });

  const live = await client.inspectBodyWork("momo", {
    jobIds: ["job-43"],
  });
  assert.deepEqual(live.activeTaskIds, ["t41", "nnav-42", "job-task-43"]);
  assert.equal(live.serverSessionId, "server-session-1");
  assert.equal(live.task.data.task, "mine");
  assert.equal(live.navigation.data.action_id, "mcp-42");
  assert.equal(live.jobs[0].data.job.job_id, "job-43");
});

test("live body inspection fails closed without authoritative navigation", async () => {
  const responses = [
    {
      success: true,
      data: {
        state: "idle",
        server_session_id: "server-session-1",
      },
    },
    {
      success: false,
      message: "navigation subsystem unavailable",
    },
  ];
  const client = new NumenMcpClient("http://127.0.0.1:8765/mcp", {
    fetchImpl: async () => ({
      ok: true,
      async json() {
        const payload = responses.shift();
        return {
          jsonrpc: "2.0",
          id: 1,
          result: {
            content: [{ type: "text", text: JSON.stringify(payload) }],
          },
        };
      },
    }),
  });

  await assert.rejects(
    client.inspectBodyWork("momo"),
    /nav_status was not authoritative/,
  );
});

test("live body inspection rejects malformed success-less status envelopes", async () => {
  const client = new NumenMcpClient("http://127.0.0.1:8765/mcp", {
    fetchImpl: async () => ({
      ok: true,
      async json() {
        return {
          jsonrpc: "2.0",
          id: 1,
          result: {
            content: [{
              type: "text",
              text: JSON.stringify({
                data: {
                  state: "idle",
                  server_session_id: "server-session-1",
                },
              }),
            }],
          },
        };
      },
    }),
  });

  await assert.rejects(
    client.inspectBodyWork("momo"),
    /authoritative server session/,
  );
});
