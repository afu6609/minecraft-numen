import assert from "node:assert/strict";
import test from "node:test";

import {
  GAMEPLAY_CAPABILITY_GROUPS,
  GAMEPLAY_ENABLED_TOOLS,
  gameplayMcpToolPolicy,
  SIDECAR_ONLY_TOOLS,
} from "../src/tool-capabilities.mjs";

const RETIRED_DUPLICATE_TOOLS = [
  // Numen navigation replaced by the native embodied navigator.
  "goto",
  // Older raw/block perception replaced by the semantic layered surface.
  "scan_blocks",
  "look_around",
  "look_around_player",
  "inspect_block",
  // Numen semantic entry points are superseded by Momo Embodied.
  "survey_scene",
  "inspect_object",
];

test("gameplay profile is an exact duplicate-free allow list", () => {
  const flattened = Object.values(GAMEPLAY_CAPABILITY_GROUPS).flat();

  assert.deepEqual(GAMEPLAY_ENABLED_TOOLS, flattened);
  assert.equal(
    new Set(GAMEPLAY_ENABLED_TOOLS).size,
    GAMEPLAY_ENABLED_TOOLS.length,
  );
  assert.equal(
    GAMEPLAY_ENABLED_TOOLS.some((name) => SIDECAR_ONLY_TOOLS.includes(name)),
    false,
  );
  for (const name of RETIRED_DUPLICATE_TOOLS) {
    assert.equal(
      GAMEPLAY_ENABLED_TOOLS.includes(name),
      false,
      `${name} must remain hidden from the gameplay model`,
    );
  }
});

test("gameplay profile retains each required capability family", () => {
  assert.deepEqual(GAMEPLAY_CAPABILITY_GROUPS.communication, ["send_chat"]);
  assert.deepEqual(GAMEPLAY_CAPABILITY_GROUPS.task_control, [
    "task_status",
    "task_stop",
  ]);
  assert.deepEqual(GAMEPLAY_CAPABILITY_GROUPS.native_navigation, [
    "embodied_move_to",
    "embodied_follow_owner",
    "embodied_nav_status",
    "embodied_nav_stop",
  ]);
  assert.deepEqual(GAMEPLAY_CAPABILITY_GROUPS.compatibility_navigation, [
    "follow_player",
  ]);
  for (const name of [
    "get_self_status",
    "get_owner_status",
    "get_player_status",
    "embodied_survey_scene",
    "embodied_inspect_object",
    "embodied_plan_object",
    "embodied_plan_region",
    "embodied_execute_plan",
    "embodied_job_status",
    "embodied_cancel_job",
    "embodied_plan_undo",
    "observe_volume",
    "structure_plan",
    "structure_status",
    "structure_patch",
    "placement_feasibility",
    "structure_execute",
    "observe_entity_intent",
    "get_combat_trace",
    "save_combat_policy",
    "combat_policy_status",
    "activate_combat_policy",
    "abort_combat_policy",
  ]) {
    assert.equal(
      GAMEPLAY_ENABLED_TOOLS.includes(name),
      true,
      `${name} must remain available to the gameplay model`,
    );
  }
});

test("policy returns isolated SDK configuration arrays", () => {
  const first = gameplayMcpToolPolicy();
  const second = gameplayMcpToolPolicy();

  assert.deepEqual(first.enabled_tools, GAMEPLAY_ENABLED_TOOLS);
  assert.deepEqual(first.disabled_tools, SIDECAR_ONLY_TOOLS);
  assert.notEqual(first.enabled_tools, second.enabled_tools);
  assert.notEqual(first.disabled_tools, second.disabled_tools);

  first.enabled_tools.push("future_tool");
  first.disabled_tools.length = 0;
  assert.equal(GAMEPLAY_ENABLED_TOOLS.includes("future_tool"), false);
  assert.deepEqual(second.disabled_tools, SIDECAR_ONLY_TOOLS);
});
