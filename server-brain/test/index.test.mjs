import assert from "node:assert/strict";
import test from "node:test";

import {
  shouldReplayTransportForCompanion,
  shouldReplayTransportEvent,
  verifyMcp,
} from "../src/index.mjs";
import { GAMEPLAY_ENABLED_TOOLS } from "../src/tool-capabilities.mjs";

const required = [...new Set([
  "list_companions",
  "poll_server_events",
  "poll_companion_events",
  "run_command",
  "report_brain_config_state",
  ...GAMEPLAY_ENABLED_TOOLS,
])];

function client(names) {
  return {
    async initialize() {},
    async listTools() {
      return names.map((name) => ({ name }));
    },
  };
}

test("MCP verification requires Embodied, blueprint, and combat tools", async () => {
  await assert.rejects(
    verifyMcp(client(required.filter((name) => name !== "get_owner_status"))),
    /missing get_owner_status/,
  );
  await assert.rejects(
    verifyMcp(client(required.filter((name) => name !== "observe_volume"))),
    /missing observe_volume/,
  );
  await assert.rejects(
    verifyMcp(client(required.filter((name) => name !== "structure_status"))),
    /missing structure_status/,
  );
  await assert.rejects(
    verifyMcp(client(required.filter((name) => name !== "structure_patch"))),
    /missing structure_patch/,
  );
  await assert.rejects(
    verifyMcp(
      client(required.filter((name) => name !== "placement_feasibility")),
    ),
    /missing placement_feasibility/,
  );
  await assert.rejects(
    verifyMcp(client(required.filter((name) => name !== "embodied_move_to"))),
    /missing embodied_move_to/,
  );
  await assert.rejects(
    verifyMcp(
      client(required.filter((name) => name !== "embodied_follow_owner")),
    ),
    /missing embodied_follow_owner/,
  );
  await assert.rejects(
    verifyMcp(
      client(required.filter((name) => name !== "embodied_survey_scene")),
    ),
    /missing embodied_survey_scene/,
  );
  await assert.rejects(
    verifyMcp(
      client(required.filter((name) => name !== "embodied_execute_plan")),
    ),
    /missing embodied_execute_plan/,
  );
  await assert.rejects(
    verifyMcp(client(required.filter((name) => name !== "get_combat_trace"))),
    /missing get_combat_trace/,
  );
  await assert.rejects(
    verifyMcp(client(required.filter((name) => name !== "save_combat_policy"))),
    /missing save_combat_policy/,
  );
  await verifyMcp(client(required));
});

test("old-session gameplay is quarantined while server-wide config survives", () => {
  assert.equal(
    shouldReplayTransportEvent(
      {
        id: 1,
        type: "player_chat",
        message: "momo stop",
        serverSessionId: "session-a",
      },
      "session-b",
    ),
    false,
  );
  assert.equal(
    shouldReplayTransportEvent(
      {
        id: 2,
        type: "brain_config_request",
        serverSessionId: "session-a",
        data: {
          requestId: "request-2",
          action: "get",
          expiresAtEpochMillis: Date.now() + 30_000,
          requester: { kind: "console", name: "console" },
        },
      },
      "session-b",
    ),
    true,
  );
  assert.equal(
    shouldReplayTransportEvent(
      { id: 3, type: "player_chat", message: "legacy event" },
      "session-b",
    ),
    false,
  );
});

test("transport owner mismatch retains only server-wide configuration", () => {
  const stop = {
    id: 4,
    type: "console_chat",
    message: "momo stop",
    serverSessionId: "session-b",
  };
  const config = {
    id: 5,
    type: "brain_config_request",
    data: {
      requestId: "request-5",
      action: "get",
      expiresAtEpochMillis: Date.now() + 30_000,
      requester: { kind: "console", name: "console" },
    },
  };

  assert.equal(
    shouldReplayTransportForCompanion(stop, "momo-old", "momo"),
    false,
  );
  assert.equal(
    shouldReplayTransportForCompanion(config, "momo-old", "momo"),
    true,
  );
});
