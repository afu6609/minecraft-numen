import { mkdir, readFile, readdir, rename, writeFile } from "node:fs/promises";
import path from "node:path";

const SKILL_ID = /^[a-z0-9]+(?:_[a-z0-9]+)*$/;
const STATUSES = new Set(["candidate", "trusted", "disabled"]);
const MAX_STEPS = 12;
const MAX_SKILL_BYTES = 32 * 1024;
const MAX_TEXT_LENGTH = 1_000;
const MAX_EVIDENCE_LENGTH = 2_048;
const TEMPLATE = /^\$\{([a-z][a-z0-9_]*)\}$/u;
const VERIFIER_TYPES = new Set([
  "tool_success",
  "task_terminal_done",
  "inventory_at_least",
  "inventory_delta",
  "structure_phase",
  "position_within",
  "kernel_verified_terminal",
  "body_idle",
  "health_at_least",
]);
const STEP_VERIFIER_TYPES = new Set([
  "tool_success",
  "task_terminal_done",
  "kernel_verified_terminal",
  "inventory_at_least",
  "inventory_delta",
  "structure_phase",
  "position_within",
  "body_idle",
  "health_at_least",
]);
const PRECONDITION_VERIFIER_TYPES = new Set([
  "inventory_at_least",
  "structure_phase",
  "position_within",
  "body_idle",
  "health_at_least",
]);
const POSTCONDITION_VERIFIER_TYPES = new Set([
  "inventory_at_least",
  "inventory_delta",
  "structure_phase",
  "position_within",
  "body_idle",
  "health_at_least",
]);
const ASYNC_SKILL_TOOLS = new Set([
  "embodied_execute_plan",
  "embodied_move_to",
  "mine",
  "collect_items",
  "structure_execute",
]);
const TERMINAL_VERIFIER_TYPES = new Set([
  "task_terminal_done",
  "kernel_verified_terminal",
]);

export const SKILL_TOOL_ALLOWLIST = Object.freeze([
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
  "embodied_plan_object",
  "embodied_plan_region",
  "embodied_execute_plan",
  "embodied_job_status",
  "embodied_move_to",
  "embodied_nav_status",
  "mine",
  "collect_items",
  "equip_item",
  "eat_item",
  "interact_at",
  "interact_entity",
  "lookup_recipe",
  "craft",
  "inspect_gui",
  "transfer",
  "close_gui",
  "structure_status",
  "placement_feasibility",
  "structure_execute",
]);
const SKILL_TOOLS = new Set(SKILL_TOOL_ALLOWLIST);

function requireString(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError(`${field} must be a non-empty string`);
  }
  const normalized = value.trim();
  if (normalized.length > MAX_TEXT_LENGTH) {
    throw new TypeError(`${field} exceeds ${MAX_TEXT_LENGTH} characters`);
  }
  return normalized;
}

function requireStringArray(value, field) {
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    throw new TypeError(`${field} must be an array of strings`);
  }
  return value.map((item) => item.trim()).filter(Boolean);
}

function optionalString(value, field, maxLength = MAX_TEXT_LENGTH) {
  if (typeof value !== "string") {
    throw new TypeError(`${field} must be a string`);
  }
  const normalized = value.trim();
  if (normalized.length > maxLength) {
    throw new TypeError(`${field} exceeds ${maxLength} characters`);
  }
  return normalized;
}

function validateJsonShape(value, field, depth = 0) {
  if (depth > 8) throw new TypeError(`${field} is nested too deeply`);
  if (typeof value === "string" && value.length > MAX_TEXT_LENGTH) {
    throw new TypeError(`${field} contains an oversized string`);
  }
  if (Array.isArray(value)) {
    if (value.length > 128) throw new TypeError(`${field} contains too many items`);
    value.forEach((item, index) =>
      validateJsonShape(item, `${field}[${index}]`, depth + 1),
    );
  } else if (value != null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length > 128) throw new TypeError(`${field} has too many keys`);
    for (const [key, item] of entries) {
      validateJsonShape(item, `${field}.${key}`, depth + 1);
    }
  }
}

function exactFields(verifier, field, required, optional = []) {
  const permitted = new Set(["type", ...required, ...optional]);
  for (const key of Object.keys(verifier)) {
    if (!permitted.has(key)) {
      throw new TypeError(`${field}.${key} is not supported`);
    }
  }
  for (const key of required) {
    if (!Object.hasOwn(verifier, key)) {
      throw new TypeError(`${field}.${key} is required`);
    }
  }
}

function numericOrTemplate(value, field, { nonNegative = false } = {}) {
  if (typeof value === "string" && TEMPLATE.test(value)) return value;
  if (!Number.isFinite(value) || (nonNegative && value < 0)) {
    throw new TypeError(
      `${field} must be ${nonNegative ? "a non-negative " : "a "}finite number or a complete parameter template`,
    );
  }
  return value;
}

function stringOrTemplate(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError(`${field} must be a non-empty string or template`);
  }
  return value.trim();
}

function normalizeVerifier(verifier, field, allowedTypes = VERIFIER_TYPES) {
  if (
    verifier == null ||
    typeof verifier !== "object" ||
    Array.isArray(verifier)
  ) {
    throw new TypeError(`${field} must be an object`);
  }
  const type = requireString(verifier.type, `${field}.type`);
  if (!VERIFIER_TYPES.has(type) || !allowedTypes.has(type)) {
    throw new TypeError(`${field}.type is not supported in this position`);
  }
  let normalized;
  if (
    type === "tool_success" ||
    type === "task_terminal_done" ||
    type === "kernel_verified_terminal" ||
    type === "body_idle"
  ) {
    exactFields(verifier, field, []);
    normalized = { type };
  } else if (type === "health_at_least") {
    exactFields(verifier, field, ["value"]);
    normalized = {
      type,
      value: numericOrTemplate(verifier.value, `${field}.value`, {
        nonNegative: true,
      }),
    };
  } else if (type === "inventory_at_least") {
    exactFields(verifier, field, ["item", "count"]);
    normalized = {
      type,
      item: stringOrTemplate(verifier.item, `${field}.item`),
      count: numericOrTemplate(verifier.count, `${field}.count`, {
        nonNegative: true,
      }),
    };
  } else if (type === "inventory_delta") {
    exactFields(verifier, field, ["item", "minimum"]);
    normalized = {
      type,
      item: stringOrTemplate(verifier.item, `${field}.item`),
      minimum: numericOrTemplate(
        verifier.minimum,
        `${field}.minimum`,
        { nonNegative: true },
      ),
    };
  } else if (type === "position_within") {
    exactFields(verifier, field, ["x", "y", "z", "max_distance"]);
    normalized = {
      type,
      x: numericOrTemplate(verifier.x, `${field}.x`),
      y: numericOrTemplate(verifier.y, `${field}.y`),
      z: numericOrTemplate(verifier.z, `${field}.z`),
      max_distance: numericOrTemplate(
        verifier.max_distance,
        `${field}.max_distance`,
        { nonNegative: true },
      ),
    };
  } else {
    exactFields(verifier, field, ["phase"], ["workflow_id"]);
    normalized = {
      type,
      phase: stringOrTemplate(verifier.phase, `${field}.phase`),
      ...(verifier.workflow_id == null
        ? {}
        : {
            workflow_id: stringOrTemplate(
              verifier.workflow_id,
              `${field}.workflow_id`,
            ),
          }),
    };
  }
  validateJsonShape(normalized, field);
  return normalized;
}

function normalizeVerifierArray(
  value,
  field,
  { required = true, allowedTypes = VERIFIER_TYPES } = {},
) {
  if (!Array.isArray(value) || (required && value.length === 0)) {
    throw new TypeError(`${field} must contain at least one typed verifier`);
  }
  return value.map((verifier, index) =>
    normalizeVerifier(verifier, `${field}[${index}]`, allowedTypes),
  );
}

function validateTemplates(value, parameters, field) {
  if (typeof value === "string") {
    if (!value.includes("${")) return;
    const match = value.match(TEMPLATE);
    if (match == null) {
      throw new TypeError(
        `${field} templates must occupy the complete string value`,
      );
    }
    if (!Object.hasOwn(parameters, match[1])) {
      throw new TypeError(`${field} references unknown parameter ${match[1]}`);
    }
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) =>
      validateTemplates(item, parameters, `${field}[${index}]`),
    );
    return;
  }
  if (value != null && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      validateTemplates(item, parameters, `${field}.${key}`);
    }
  }
}

function normalizeStep(step, index, parameters) {
  if (step == null || typeof step !== "object" || Array.isArray(step)) {
    throw new TypeError(`steps[${index}] must be an object`);
  }
  const tool = requireString(step.tool, `steps[${index}].tool`);
  if (!SKILL_ID.test(tool)) {
    throw new TypeError(`steps[${index}].tool must be snake_case`);
  }
  if (!SKILL_TOOLS.has(tool)) {
    throw new TypeError(`steps[${index}].tool is not allowed in skills`);
  }
  const args =
    step.args == null
      ? {}
      : step.args;
  if (typeof args !== "object" || Array.isArray(args)) {
    throw new TypeError(`steps[${index}].args must be an object`);
  }
  validateJsonShape(args, `steps[${index}].args`);
  validateTemplates(args, parameters, `steps[${index}].args`);
  const verifiers = normalizeVerifierArray(
    step.verifiers,
    `steps[${index}].verifiers`,
    { allowedTypes: STEP_VERIFIER_TYPES },
  );
  const terminalVerifiers = verifiers.filter((verifier) =>
    TERMINAL_VERIFIER_TYPES.has(verifier.type),
  );
  if (ASYNC_SKILL_TOOLS.has(tool) && terminalVerifiers.length === 0) {
    throw new TypeError(
      `steps[${index}] async tool ${tool} requires a terminal verifier`,
    );
  }
  if (!ASYNC_SKILL_TOOLS.has(tool) && terminalVerifiers.length > 0) {
    throw new TypeError(
      `steps[${index}] synchronous tool ${tool} cannot use a terminal verifier`,
    );
  }
  if (
    tool === "embodied_execute_plan" &&
    !terminalVerifiers.some(
      (verifier) => verifier.type === "kernel_verified_terminal",
    )
  ) {
    throw new TypeError(
      `steps[${index}] embodied_execute_plan requires kernel_verified_terminal`,
    );
  }
  if (
    tool !== "embodied_execute_plan" &&
    terminalVerifiers.some(
      (verifier) => verifier.type === "kernel_verified_terminal",
    )
  ) {
    throw new TypeError(
      `steps[${index}] kernel_verified_terminal is reserved for embodied_execute_plan`,
    );
  }
  return {
    tool,
    args: structuredClone(args),
    expect: requireString(step.expect, `steps[${index}].expect`),
    verifiers,
    on_failure:
      step.on_failure == null
        ? "stop_and_replan"
        : requireString(step.on_failure, `steps[${index}].on_failure`),
  };
}

/**
 * Validate and normalize a model-produced skill draft before it reaches disk.
 */
export function normalizeSkill(draft) {
  if (draft == null || typeof draft !== "object" || Array.isArray(draft)) {
    throw new TypeError("skill draft must be an object");
  }

  const id = requireString(draft.id, "id");
  if (!SKILL_ID.test(id)) {
    throw new TypeError("id must contain lowercase letters, numbers, and underscores only");
  }

  if (!Array.isArray(draft.steps) || draft.steps.length === 0) {
    throw new TypeError("steps must contain at least one tool call");
  }
  if (draft.steps.length > MAX_STEPS) {
    throw new TypeError(`steps must contain at most ${MAX_STEPS} tool calls`);
  }

  const status = draft.status ?? "candidate";
  if (!STATUSES.has(status)) {
    throw new TypeError("status must be candidate, trusted, or disabled");
  }

  const validations = draft.validations ?? {};
  const successes = Number.isInteger(validations.successes) ? validations.successes : 1;
  const failures = Number.isInteger(validations.failures) ? validations.failures : 0;
  if (successes < 0 || failures < 0) {
    throw new TypeError("validation counters cannot be negative");
  }
  const executionIds = Array.isArray(validations.execution_ids)
    ? validations.execution_ids
        .filter(
          (value) =>
            typeof value === "string" &&
            value.length > 0 &&
            value.length <= 160,
        )
        .slice(-32)
    : [];
  const executionResults = Array.isArray(validations.execution_results)
    ? validations.execution_results
        .filter(
          (entry) =>
            typeof entry?.id === "string" &&
            entry.id.length > 0 &&
            entry.id.length <= 160 &&
            typeof entry.success === "boolean",
        )
        .slice(-32)
        .map((entry) => ({ id: entry.id, success: entry.success }))
    : executionIds.map((id) => ({ id, success: true }));

  const parameters =
    draft.parameters != null &&
    typeof draft.parameters === "object" &&
    !Array.isArray(draft.parameters)
      ? structuredClone(draft.parameters)
      : {};
  validateJsonShape(parameters, "parameters");

  const skill = {
    schema_version: 1,
    id,
    version: Number.isInteger(draft.version) && draft.version > 0 ? draft.version : 1,
    status,
    intent: requireString(draft.intent, "intent"),
    description: requireString(draft.description, "description"),
    parameters,
    preconditions: requireStringArray(draft.preconditions ?? [], "preconditions"),
    precondition_verifiers: normalizeVerifierArray(
      draft.precondition_verifiers,
      "precondition_verifiers",
      { allowedTypes: PRECONDITION_VERIFIER_TYPES },
    ),
    steps: draft.steps.map((step, index) =>
      normalizeStep(step, index, parameters),
    ),
    postconditions: requireStringArray(draft.postconditions ?? [], "postconditions"),
    postcondition_verifiers: normalizeVerifierArray(
      draft.postcondition_verifiers,
      "postcondition_verifiers",
      { allowedTypes: POSTCONDITION_VERIFIER_TYPES },
    ),
    recovery: requireStringArray(draft.recovery ?? [], "recovery"),
    provenance: {
      source_task: requireString(
        draft.provenance?.source_task ?? "unknown",
        "provenance.source_task",
      ),
      learned_at: requireString(
        draft.provenance?.learned_at ?? new Date().toISOString(),
        "provenance.learned_at",
      ),
      last_evidence:
        draft.provenance?.last_evidence == null
          ? ""
          : optionalString(
              draft.provenance.last_evidence,
              "provenance.last_evidence",
              MAX_EVIDENCE_LENGTH,
            ),
    },
    validations: {
      successes,
      failures,
      last_validated_at: validations.last_validated_at ?? null,
      execution_ids: [
        ...new Set([
          ...executionIds,
          ...executionResults.map((entry) => entry.id),
        ]),
      ].slice(-32),
      execution_results: executionResults,
    },
  };
  for (const [field, verifiers] of [
    ["precondition_verifiers", skill.precondition_verifiers],
    ["postcondition_verifiers", skill.postcondition_verifiers],
  ]) {
    validateTemplates(verifiers, parameters, field);
  }
  for (let index = 0; index < skill.steps.length; index += 1) {
    validateTemplates(
      skill.steps[index].verifiers,
      parameters,
      `steps[${index}].verifiers`,
    );
  }
  if (skill.provenance.last_evidence.length > MAX_EVIDENCE_LENGTH) {
    throw new TypeError(
      `provenance.last_evidence exceeds ${MAX_EVIDENCE_LENGTH} characters`,
    );
  }
  if (Buffer.byteLength(JSON.stringify(skill), "utf8") > MAX_SKILL_BYTES) {
    throw new TypeError(`skill exceeds ${MAX_SKILL_BYTES} bytes`);
  }
  return skill;
}

export function executionPolicy(skill) {
  if (skill.status === "disabled") return "disabled";
  return skill.status === "trusted" ? "verify_final" : "verify_each_step";
}

/**
 * File-backed learned workflow store. Writes are atomic on the same filesystem:
 * serialize to a temporary sibling, then rename over the current version.
 */
export class ExperienceStore {
  constructor(root, { promotionSuccesses = 3 } = {}) {
    if (!Number.isInteger(promotionSuccesses) || promotionSuccesses < 2) {
      throw new TypeError("promotionSuccesses must be an integer >= 2");
    }
    this.root = path.resolve(root);
    this.promotionSuccesses = promotionSuccesses;
    this.writeTails = new Map();
  }

  async saveCandidate(draft) {
    const skill = normalizeSkill({ ...draft, status: "candidate" });
    await mkdir(this.root, { recursive: true });
    await writeFile(
      this.#file(skill.id),
      `${JSON.stringify(skill, null, 2)}\n`,
      { encoding: "utf8", flag: "wx", mode: 0o600 },
    );
    return skill;
  }

  async saveRevision(id, expectedVersion, draft) {
    return this.#serialize(id, async () => {
    this.#assertId(id);
    if (!Number.isInteger(expectedVersion) || expectedVersion < 1) {
      throw new TypeError("expectedVersion must be a positive integer");
    }
    const current = await this.get(id);
    if (current.status === "trusted") {
      throw new Error(
        "trusted skills cannot be revised through the model-facing candidate API",
      );
    }
    if (current.version !== expectedVersion) {
      throw new Error(
        `skill revision conflict: expected ${expectedVersion}, current ${current.version}`,
      );
    }
    const updated = normalizeSkill({
      ...draft,
      id,
      version: current.version + 1,
      status: "candidate",
      validations: {
        successes: 0,
        failures: 0,
        last_validated_at: null,
        execution_ids: [],
        execution_results: [],
      },
      provenance: {
        ...draft.provenance,
        last_evidence: "",
      },
    });
    await this.#write(updated);
    return updated;
    });
  }

  async get(id) {
    this.#assertId(id);
    const raw = await readFile(this.#file(id), "utf8");
    return normalizeSkill(JSON.parse(raw));
  }

  async list() {
    await mkdir(this.root, { recursive: true });
    const names = await readdir(this.root);
    const skills = [];
    for (const name of names.sort()) {
      if (!name.endsWith(".json")) continue;
      skills.push(await this.get(name.slice(0, -5)));
    }
    return skills;
  }

  async recordValidation(
    id,
    {
      success,
      evidence = "",
      executionId,
      expectedVersion,
    },
  ) {
    return this.#serialize(id, async () => {
    if (typeof evidence !== "string" || evidence.length > MAX_EVIDENCE_LENGTH) {
      throw new TypeError(
        `evidence must be at most ${MAX_EVIDENCE_LENGTH} characters`,
      );
    }
    if (
      typeof executionId !== "string" ||
      executionId.trim() === "" ||
      executionId.length > 160
    ) {
      throw new TypeError("executionId must be a bounded non-empty string");
    }
    if (!Number.isInteger(expectedVersion) || expectedVersion < 1) {
      throw new TypeError("expectedVersion must be a positive integer");
    }
    const skill = await this.get(id);
    if (skill.validations.execution_ids.includes(executionId)) {
      return skill;
    }
    if (skill.version !== expectedVersion) {
      throw new Error(
        `skill definition changed during execution: expected ${expectedVersion}, current ${skill.version}`,
      );
    }
    const validations = {
      ...skill.validations,
      successes: skill.validations.successes + (success ? 1 : 0),
      failures: skill.validations.failures + (success ? 0 : 1),
      last_validated_at: new Date().toISOString(),
      execution_ids: [
        ...skill.validations.execution_ids,
        executionId,
      ].slice(-32),
      execution_results: [
        ...skill.validations.execution_results,
        { id: executionId, success: success === true },
      ].slice(-32),
    };

    const status =
      success &&
      validations.failures === 0 &&
      validations.successes >= this.promotionSuccesses
        ? "trusted"
        : "candidate";

    const updated = normalizeSkill({
      ...skill,
      version: skill.version + 1,
      status,
      validations,
      provenance: {
        ...skill.provenance,
        last_evidence: evidence,
      },
    });
    await this.#write(updated);
    return updated;
    });
  }

  async #write(skill) {
    await mkdir(this.root, { recursive: true });
    const target = this.#file(skill.id);
    const temporary = `${target}.${process.pid}.tmp`;
    await writeFile(
      temporary,
      `${JSON.stringify(skill, null, 2)}\n`,
      { encoding: "utf8", mode: 0o600 },
    );
    await rename(temporary, target);
  }

  async #serialize(id, operation) {
    this.#assertId(id);
    const previous = this.writeTails.get(id) ?? Promise.resolve();
    const current = previous.catch(() => {}).then(operation);
    this.writeTails.set(id, current);
    try {
      return await current;
    } finally {
      if (this.writeTails.get(id) === current) {
        this.writeTails.delete(id);
      }
    }
  }

  #file(id) {
    this.#assertId(id);
    return path.join(this.root, `${id}.json`);
  }

  #assertId(id) {
    if (typeof id !== "string" || !SKILL_ID.test(id)) {
      throw new TypeError("invalid skill id");
    }
  }
}
