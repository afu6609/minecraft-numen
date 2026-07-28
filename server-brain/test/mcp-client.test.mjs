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

  assert.equal(events[0].message, "hi");
  assert.equal(bodyEvents[0].message, "hi");
  assert.equal(requests[0].rpc.params.name, "poll_server_events");
  assert.equal(requests[1].rpc.params.name, "poll_companion_events");
  assert.equal(requests[1].rpc.params.arguments.companion, "momo");
  assert.equal(requests[1].rpc.params.arguments.limit, 6);
  assert.equal(requests[0].authorization, "Bearer secret");
});
