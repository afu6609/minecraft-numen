/**
 * The gameplay model shares one Numen MCP server with the sidecar control
 * plane. Keep the model-facing surface explicit: newly registered Java tools
 * must not silently become available to the model.
 *
 * Codex applies `enabled_tools` first and then removes `disabled_tools`.
 * Keeping the control-plane deny list as a second fence makes ownership clear
 * even if a future profile accidentally includes one of these names.
 */
export const SIDECAR_ONLY_TOOLS = Object.freeze([
  "list_companions",
  "poll_server_events",
  "poll_companion_events",
  "create_companion",
  "delete_companion",
  "run_command",
  "report_brain_config_state",
]);

export const GAMEPLAY_CAPABILITY_GROUPS = Object.freeze({
  communication: Object.freeze([
    "send_chat",
  ]),
  state: Object.freeze([
    "get_self_status",
    "get_owner_status",
    "get_player_status",
    "get_world_info",
  ]),
  task_control: Object.freeze([
    "task_status",
    "task_stop",
  ]),
  semantic_perception: Object.freeze([
    "embodied_survey_scene",
    "embodied_inspect_object",
    "observe_volume",
    "scan_nearby_entities",
    "inspect_block_storage",
  ]),
  regional_workflow: Object.freeze([
    "embodied_plan_object",
    "embodied_plan_region",
    "embodied_execute_plan",
    "embodied_job_status",
    "embodied_cancel_job",
    "embodied_plan_undo",
  ]),
  native_navigation: Object.freeze([
    "embodied_move_to",
    "embodied_follow_owner",
    "embodied_nav_status",
    "embodied_nav_stop",
  ]),
  compatibility_navigation: Object.freeze([
    // Native follow currently targets only the companion's fixed owner. Keep
    // the exact-player follower for requests from another authenticated human
    // until native navigation accepts an arbitrary player UUID.
    "follow_player",
  ]),
  survival_actions: Object.freeze([
    "melee_attack",
    "ranged_attack",
    "mine",
    "collect_items",
    "equip_item",
    "eat_item",
    "drop_items",
    "interact_at",
    "interact_entity",
  ]),
  crafting_inventory: Object.freeze([
    "lookup_recipe",
    "craft",
    "inspect_gui",
    "transfer",
    "close_gui",
  ]),
  structure_workflow: Object.freeze([
    "build",
    "break_block",
    "structure_plan",
    "structure_status",
    "structure_patch",
    "placement_feasibility",
    "structure_execute",
  ]),
  combat_learning: Object.freeze([
    "observe_entity_intent",
    "get_combat_trace",
    "save_combat_policy",
    "combat_policy_status",
    "activate_combat_policy",
    "abort_combat_policy",
  ]),
});

export const GAMEPLAY_ENABLED_TOOLS = Object.freeze(
  Object.values(GAMEPLAY_CAPABILITY_GROUPS).flat(),
);

export const GAMEPLAY_TOOL_PROFILES = Object.freeze({
  conversation: Object.freeze([
    "send_chat",
  ]),
  orient: Object.freeze([
    "send_chat",
    "get_self_status",
    "get_owner_status",
    "get_player_status",
    "get_world_info",
    "task_status",
    "task_stop",
    "embodied_survey_scene",
    "embodied_inspect_object",
    "observe_volume",
    "scan_nearby_entities",
    "inspect_block_storage",
    "embodied_move_to",
    "embodied_follow_owner",
    "embodied_nav_status",
    "embodied_nav_stop",
    "follow_player",
    "interact_at",
  ]),
  reconcile: Object.freeze([
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
  ]),
  gather: Object.freeze([
    "send_chat",
    "get_self_status",
    "get_player_status",
    "task_status",
    "task_stop",
    "embodied_survey_scene",
    "embodied_inspect_object",
    "embodied_move_to",
    "embodied_nav_status",
    "embodied_nav_stop",
    "mine",
    "collect_items",
    "equip_item",
    "eat_item",
    "lookup_recipe",
    "craft",
  ]),
  craft: Object.freeze([
    "send_chat",
    "get_self_status",
    "task_status",
    "task_stop",
    "embodied_survey_scene",
    "inspect_block_storage",
    "embodied_move_to",
    "embodied_nav_status",
    "embodied_nav_stop",
    "mine",
    "collect_items",
    "interact_at",
    "lookup_recipe",
    "craft",
    "inspect_gui",
    "transfer",
    "close_gui",
  ]),
  structure: Object.freeze([
    "send_chat",
    "get_self_status",
    "task_status",
    "embodied_survey_scene",
    "observe_volume",
    "embodied_move_to",
    "embodied_nav_status",
    "embodied_nav_stop",
    "lookup_recipe",
    "craft",
    "mine",
    "collect_items",
    "structure_plan",
    "structure_status",
    "structure_patch",
    "placement_feasibility",
    "structure_execute",
  ]),
  regional_edit: Object.freeze([
    "send_chat",
    "get_self_status",
    "get_owner_status",
    "task_status",
    "embodied_survey_scene",
    "embodied_inspect_object",
    "observe_volume",
    "embodied_move_to",
    "embodied_nav_status",
    "embodied_nav_stop",
    "embodied_plan_object",
    "embodied_plan_region",
    "embodied_execute_plan",
    "embodied_job_status",
    "embodied_cancel_job",
    "embodied_plan_undo",
    "break_block",
  ]),
  direct_action: Object.freeze([
    "send_chat",
    "get_self_status",
    "get_owner_status",
    "task_status",
    "task_stop",
    "embodied_survey_scene",
    "embodied_inspect_object",
    "observe_volume",
    "embodied_move_to",
    "embodied_nav_status",
    "embodied_nav_stop",
    "build",
    "break_block",
    "interact_at",
    "interact_entity",
    "equip_item",
    "collect_items",
  ]),
  survival: Object.freeze([
    "send_chat",
    "get_self_status",
    "get_player_status",
    "get_world_info",
    "task_status",
    "scan_nearby_entities",
    "embodied_survey_scene",
    "embodied_inspect_object",
    "embodied_move_to",
    "embodied_nav_status",
    "embodied_nav_stop",
    "melee_attack",
    "ranged_attack",
    "collect_items",
    "equip_item",
    "eat_item",
    "interact_at",
    "interact_entity",
  ]),
  combat_learning: Object.freeze([
    "send_chat",
    "get_self_status",
    "get_player_status",
    "get_world_info",
    "task_status",
    "scan_nearby_entities",
    "embodied_move_to",
    "embodied_nav_status",
    "embodied_nav_stop",
    "equip_item",
    "eat_item",
    "observe_entity_intent",
    "get_combat_trace",
    "save_combat_policy",
    "combat_policy_status",
    "activate_combat_policy",
    "abort_combat_policy",
    "interact_entity",
  ]),
});

export const DEFAULT_GAMEPLAY_TOOL_PROFILE = "orient";

export const HARNESS_TOOL_PROFILES = Object.freeze({
  conversation: Object.freeze([]),
  orient: Object.freeze([]),
  reconcile: Object.freeze([]),
  gather: Object.freeze(["run_skill"]),
  craft: Object.freeze(["run_skill"]),
  structure: Object.freeze(["run_skill"]),
  regional_edit: Object.freeze(["run_skill"]),
  direct_action: Object.freeze(["run_skill"]),
  survival: Object.freeze([]),
  combat_learning: Object.freeze([]),
});

export function normalizeToolProfile(profile) {
  return Object.hasOwn(GAMEPLAY_TOOL_PROFILES, profile)
    ? profile
    : DEFAULT_GAMEPLAY_TOOL_PROFILE;
}

/**
 * Return fresh arrays because Codex SDK serializes this object as CLI config.
 * Callers may safely spread or amend the returned value without mutating the
 * canonical profile.
 */
export function gameplayMcpToolPolicy(profile = DEFAULT_GAMEPLAY_TOOL_PROFILE) {
  const normalized = normalizeToolProfile(profile);
  return {
    enabled_tools: [...GAMEPLAY_TOOL_PROFILES[normalized]],
    disabled_tools: [...SIDECAR_ONLY_TOOLS],
  };
}

export function harnessMcpToolPolicy(profile = DEFAULT_GAMEPLAY_TOOL_PROFILE) {
  const normalized = normalizeToolProfile(profile);
  const enabled = HARNESS_TOOL_PROFILES[normalized];
  return {
    enabled_tools: [...enabled],
    disabled_tools: [
      "list_skills",
      ...["run_skill", "save_skill_candidate"].filter(
        (tool) => !enabled.includes(tool),
      ),
    ],
  };
}
