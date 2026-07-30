import assert from "node:assert/strict";
import test from "node:test";

import {
  acceptedNumenTaskReceipts,
  acceptedNumenTaskIds,
  authoritativeTaskStatusSession,
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

test("the final successful task status is the turn session authority", () => {
  const turn = {
    items: [
      ...toolTurn("task_status", {
        success: true,
        data: { state: "running", server_session_id: "session-a" },
      }).items,
      ...toolTurn("task_status", {
        success: true,
        data: { state: "idle", server_session_id: "session-b" },
      }).items,
    ],
  };

  assert.equal(authoritativeTaskStatusSession(turn), "session-b");
});

test("trusted ACK ignores a receipt from a non-authoritative session", () => {
  const turn = toolTurn("mine", {
    success: true,
    data: {
      async: true,
      task_id: "old-session-task",
      action_id: "old-session-action",
      server_session_id: "session-a",
    },
  });

  assert.equal(
    trustedAckForTurn(turn, { serverSessionId: "session-b" }),
    null,
  );
});

test("native and legacy opaque task ids are accepted from strict receipts", () => {
  const turn = toolTurn("embodied_move_to", {
    success: true,
    data: {
      async: true,
      task_id: "nnav-mcp-server-42",
      action_id: "mcp-server-42",
      server_session_id: "7f4a-session-42",
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

test("conflicting structured and text envelopes cannot authorize an ACK", () => {
  const accepted = {
    success: true,
    data: {
      async: true,
      task_id: "nnav-mcp-server-100",
    },
  };
  const turn = toolTurn("embodied_move_to", accepted, { structured: true });
  turn.items[0].result.content = [{
    type: "text",
    text: JSON.stringify({
      success: false,
      data: {
        async: false,
        task_id: "nnav-mcp-server-100",
      },
    }),
  }];

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

test("full receipts retain action and embodied job correlation", () => {
  const turn = {
    items: [{
      type: "mcp_tool_call",
      server: "numen",
      tool: "embodied_execute_plan",
      status: "completed",
      result: {
        content: [{
          type: "text",
          text: JSON.stringify({
            success: true,
            data: {
              async: true,
              task_id: "ejob-7",
              action_id: "mcp-7",
              server_session_id: "server-session-7",
              job: { job_id: "job-7" },
            },
          }),
        }],
      },
    }],
  };

  assert.deepEqual(acceptedNumenTaskReceipts(turn), [{
    kind: "accepted_task",
    server: "numen",
    tool: "embodied_execute_plan",
    taskId: "ejob-7",
    actionId: "mcp-7",
    jobId: "job-7",
    serverSessionId: "server-session-7",
  }]);
});
