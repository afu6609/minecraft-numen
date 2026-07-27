import { mkdir, readFile, readdir, rename, writeFile } from "node:fs/promises";
import path from "node:path";

const SKILL_ID = /^[a-z0-9]+(?:_[a-z0-9]+)*$/;
const STATUSES = new Set(["candidate", "trusted", "disabled"]);

function requireString(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError(`${field} must be a non-empty string`);
  }
  return value.trim();
}

function requireStringArray(value, field) {
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    throw new TypeError(`${field} must be an array of strings`);
  }
  return value.map((item) => item.trim()).filter(Boolean);
}

function normalizeStep(step, index) {
  if (step == null || typeof step !== "object" || Array.isArray(step)) {
    throw new TypeError(`steps[${index}] must be an object`);
  }
  const tool = requireString(step.tool, `steps[${index}].tool`);
  if (!SKILL_ID.test(tool)) {
    throw new TypeError(`steps[${index}].tool must be snake_case`);
  }
  const args =
    step.args == null
      ? {}
      : step.args;
  if (typeof args !== "object" || Array.isArray(args)) {
    throw new TypeError(`steps[${index}].args must be an object`);
  }
  return {
    tool,
    args: structuredClone(args),
    expect: requireString(step.expect, `steps[${index}].expect`),
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

  return {
    schema_version: 1,
    id,
    version: Number.isInteger(draft.version) && draft.version > 0 ? draft.version : 1,
    status,
    intent: requireString(draft.intent, "intent"),
    description: requireString(draft.description, "description"),
    parameters:
      draft.parameters != null &&
      typeof draft.parameters === "object" &&
      !Array.isArray(draft.parameters)
        ? structuredClone(draft.parameters)
        : {},
    preconditions: requireStringArray(draft.preconditions ?? [], "preconditions"),
    steps: draft.steps.map(normalizeStep),
    postconditions: requireStringArray(draft.postconditions ?? [], "postconditions"),
    recovery: requireStringArray(draft.recovery ?? [], "recovery"),
    provenance: {
      source_task: requireString(
        draft.provenance?.source_task ?? "unknown",
        "provenance.source_task",
      ),
      learned_at: draft.provenance?.learned_at ?? new Date().toISOString(),
      last_evidence: draft.provenance?.last_evidence ?? "",
    },
    validations: {
      successes,
      failures,
      last_validated_at: validations.last_validated_at ?? null,
    },
  };
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
  }

  async saveCandidate(draft) {
    const skill = normalizeSkill({ ...draft, status: "candidate" });
    await this.#write(skill);
    return skill;
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

  async recordValidation(id, { success, evidence = "" }) {
    const skill = await this.get(id);
    const validations = {
      ...skill.validations,
      successes: skill.validations.successes + (success ? 1 : 0),
      failures: skill.validations.failures + (success ? 0 : 1),
      last_validated_at: new Date().toISOString(),
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
  }

  async #write(skill) {
    await mkdir(this.root, { recursive: true });
    const target = this.#file(skill.id);
    const temporary = `${target}.${process.pid}.tmp`;
    await writeFile(temporary, `${JSON.stringify(skill, null, 2)}\n`, "utf8");
    await rename(temporary, target);
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
