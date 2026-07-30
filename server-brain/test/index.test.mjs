import assert from "node:assert/strict";
import test from "node:test";

import { verifyMcp } from "../src/index.mjs";
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
