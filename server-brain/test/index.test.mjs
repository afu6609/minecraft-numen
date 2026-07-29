import assert from "node:assert/strict";
import test from "node:test";

import { verifyMcp } from "../src/index.mjs";

const required = [
  "list_companions",
  "poll_server_events",
  "poll_companion_events",
  "send_chat",
  "run_command",
  "task_stop",
  "follow_player",
  "structure_plan",
  "structure_status",
  "structure_execute",
  "structure_patch",
  "placement_feasibility",
  "survey_scene",
  "inspect_object",
  "observe_entity_intent",
  "get_combat_trace",
  "save_combat_policy",
  "combat_policy_status",
  "activate_combat_policy",
  "abort_combat_policy",
];

function client(names) {
  return {
    async initialize() {},
    async listTools() {
      return names.map((name) => ({ name }));
    },
  };
}

test("MCP verification requires blueprint recovery and supervised combat tools", async () => {
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
    verifyMcp(client(required.filter((name) => name !== "survey_scene"))),
    /missing survey_scene/,
  );
  await assert.rejects(
    verifyMcp(client(required.filter((name) => name !== "inspect_object"))),
    /missing inspect_object/,
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
