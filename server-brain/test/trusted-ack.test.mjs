import assert from "node:assert/strict";
import test from "node:test";

import {
  acceptedNumenTaskIds,
  trustedAckForTurn,
} from "../src/trusted-ack.mjs";

function toolTurn(tool, payload, { structured = false } = {}) {
  return {
    items: [{
      type: "mcp_tool_call",
      server: "numen",
      tool,
      status: "completed",
      result: structured
        ? { structured_content: payload, content: [] }
        : {
            content: [{ type: "text", text: JSON.stringify(payload) }],
          },
    }],
  };
}

test("native and legacy opaque task ids are accepted from strict receipts", () => {
  const turn = toolTurn("embodied_move_to", {
    success: true,
    data: {
      async: true,
      task_id: "nnav-mcp-server-42",
      action_id: "mcp-server-42",
    },
  });

  assert.deepEqual([...acceptedNumenTaskIds(turn)], [
    "nnav-mcp-server-42",
  ]);
  assert.deepEqual(trustedAckForTurn(turn), {
    kind: "accepted_task",
    tool: "embodied_move_to",
    taskId: "nnav-mcp-server-42",
    actionId: "mcp-server-42",
    message: "好，我现在过去。",
  });
});

test("completed calls and unverified success envelopes cannot authorize an ACK", () => {
  assert.equal(
    trustedAckForTurn(toolTurn("goto", {
      success: true,
      data: { async: false },
    })),
    null,
  );
  assert.equal(
    trustedAckForTurn({
      items: [{
        type: "mcp_tool_call",
        server: "numen",
        tool: "goto",
        status: "completed",
        result: { content: [{ type: "text", text: "looks fine" }] },
      }],
    }),
    null,
  );
});

test("generic synchronous verification flags cannot authorize an action ACK", () => {
  assert.equal(trustedAckForTurn(toolTurn(
    "structure_patch",
    {
      success: true,
      data: {
        async: false,
        postcondition: { verified: true },
      },
    },
    { structured: true },
  )), null);
});

test("MCP isError overrides a success-looking async payload", () => {
  const turn = toolTurn("embodied_move_to", {
    success: true,
    data: {
      async: true,
      task_id: "nnav-mcp-server-99",
    },
  });
  turn.items[0].result.isError = true;

  assert.deepEqual([...acceptedNumenTaskIds(turn)], []);
  assert.equal(trustedAckForTurn(turn), null);
});

test("send_chat itself is never interpreted as a gameplay receipt", () => {
  assert.equal(
    trustedAckForTurn(toolTurn("send_chat", {
      success: true,
      data: { verified: true },
    })),
    null,
  );
});
