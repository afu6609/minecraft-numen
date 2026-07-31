import assert from "node:assert/strict";
import test from "node:test";

import {
  GAMEPLAY_CAPABILITY_GROUPS,
  GAMEPLAY_ENABLED_TOOLS,
  GAMEPLAY_TOOL_PROFILES,
  HARNESS_TOOL_PROFILES,
  gameplayMcpToolPolicy,
  harnessMcpToolPolicy,
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

test("phase profiles are bounded subsets with only intentional omissions", () => {
  const covered = new Set();
  for (const [profile, tools] of Object.entries(GAMEPLAY_TOOL_PROFILES)) {
    assert.equal(
      Object.hasOwn(HARNESS_TOOL_PROFILES, profile),
      true,
      `${profile} is missing its Harness profile`,
    );
    assert.equal(new Set(tools).size, tools.length, `${profile} has duplicates`);
    assert.ok(
      tools.length + HARNESS_TOOL_PROFILES[profile].length <= 18,
      `${profile} exceeds the 18-tool combined bound`,
    );
    assert.equal(tools.includes("send_chat"), true);
    for (const tool of tools) {
      assert.equal(
        GAMEPLAY_ENABLED_TOOLS.includes(tool),
        true,
        `${profile} exposes unknown ${tool}`,
      );
      assert.equal(SIDECAR_ONLY_TOOLS.includes(tool), false);
      covered.add(tool);
    }
  }
  const intentionallyHidden = ["drop_items"];
  assert.deepEqual(
    GAMEPLAY_ENABLED_TOOLS
      .filter((tool) => !covered.has(tool))
      .sort(),
    intentionallyHidden,
  );
  assert.deepEqual(GAMEPLAY_TOOL_PROFILES.conversation, ["send_chat"]);
  assert.equal(
    GAMEPLAY_TOOL_PROFILES.orient.includes("structure_status"),
    true,
  );
  assert.equal(
    GAMEPLAY_TOOL_PROFILES.orient.includes("interact_at"),
    false,
  );
});

test("movement-capable action profiles expose symmetric navigation control", () => {
  for (const profile of [
    "craft",
    "structure",
    "regional_edit",
    "direct_action",
  ]) {
    const tools = GAMEPLAY_TOOL_PROFILES[profile];
    assert.equal(tools.includes("embodied_move_to"), true, profile);
    assert.equal(tools.includes("embodied_nav_status"), true, profile);
    assert.equal(tools.includes("embodied_nav_stop"), true, profile);
    assert.equal(
      tools.length + HARNESS_TOOL_PROFILES[profile].length,
      18,
      `${profile} must stay within the combined enabled-tool budget`,
    );
  }
});

test("reconcile is a strictly read-only live-state profile", () => {
  const readOnlyTools = new Set([
    "send_chat",
    "get_self_status",
    "get_owner_status",
    "get_player_status",
    "get_world_info",
    "task_status",
    "embodied_survey_scene",
    "embodied_inspect_object",
    "observe_volume",
    "scan_nearby_entities",
    "inspect_block_storage",
    "embodied_nav_status",
    "embodied_job_status",
    "structure_status",
    "combat_policy_status",
  ]);

  assert.deepEqual(
    [...GAMEPLAY_TOOL_PROFILES.reconcile].sort(),
    [...readOnlyTools].sort(),
  );
  assert.deepEqual(harnessMcpToolPolicy("reconcile").enabled_tools, []);
});

test("only verified workflow execution is model-facing in action phases", () => {
  const reusableActionPhases = new Set([
    "gather",
    "craft",
    "structure",
    "regional_edit",
    "direct_action",
  ]);

  for (const profile of Object.keys(GAMEPLAY_TOOL_PROFILES)) {
    const policy = harnessMcpToolPolicy(profile);
    assert.deepEqual(
      policy.enabled_tools,
      reusableActionPhases.has(profile) ? ["run_skill"] : [],
      profile,
    );
    assert.equal(policy.enabled_tools.includes("save_skill_candidate"), false);
    assert.equal(policy.disabled_tools.includes("save_skill_candidate"), true);
  }
});

test("policy returns isolated SDK configuration arrays", () => {
  const first = gameplayMcpToolPolicy("structure");
  const second = gameplayMcpToolPolicy("structure");

  assert.deepEqual(first.enabled_tools, GAMEPLAY_TOOL_PROFILES.structure);
  assert.deepEqual(first.disabled_tools, SIDECAR_ONLY_TOOLS);
  assert.notEqual(first.enabled_tools, second.enabled_tools);
  assert.notEqual(first.disabled_tools, second.disabled_tools);

  first.enabled_tools.push("future_tool");
  first.disabled_tools.length = 0;
  assert.equal(GAMEPLAY_ENABLED_TOOLS.includes("future_tool"), false);
  assert.deepEqual(second.disabled_tools, SIDECAR_ONLY_TOOLS);
});
