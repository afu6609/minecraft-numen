import { randomUUID } from "node:crypto";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";

const STATE_VERSION = 1;

function catalogEntry(model, reasoning) {
  return Object.freeze({
    model,
    reasoning: Object.freeze([...reasoning]),
  });
}

/**
 * The combinations exposed by the Codex host used for this sidecar.
 *
 * Keep this deliberately explicit: accepting an arbitrary model string makes
 * a control command appear successful only for the next SDK thread to fail.
 */
export const AGENT_MODEL_CATALOG = Object.freeze([
  catalogEntry("gpt-5.6-luna", ["low", "medium", "high", "xhigh"]),
  catalogEntry("gpt-5.3-codex-spark", [
    "low",
    "medium",
    "high",
    "xhigh",
  ]),
]);

const MODEL_INDEX = new Map(
  AGENT_MODEL_CATALOG.map((entry) => [entry.model, entry]),
);

function requiredString(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError(`${field} must be a non-empty string`);
  }
  return value.trim();
}

function optionalString(value, field) {
  if (value == null) return undefined;
  return requiredString(value, field);
}

export function validateAgentSelection(selection) {
  if (selection == null || typeof selection !== "object") {
    throw new TypeError("model selection must be an object");
  }
  const model = requiredString(selection.model, "model");
  const reasoning = requiredString(selection.reasoning, "reasoning").toLowerCase();
  const entry = MODEL_INDEX.get(model);
  if (entry == null) {
    throw new TypeError(
      `unsupported model ${model}; choose one of ${AGENT_MODEL_CATALOG.map(
        (item) => item.model,
      ).join(", ")}`,
    );
  }
  if (!entry.reasoning.includes(reasoning)) {
    throw new TypeError(
      `${model} reasoning must be one of ${entry.reasoning.join(", ")}`,
    );
  }
  return Object.freeze({ model, reasoning });
}

function publicSnapshot(current, revision) {
  return Object.freeze({
    model: current.model,
    reasoning: current.reasoning,
    revision: String(revision),
  });
}

async function writeStateFile(file, state) {
  const directory = path.dirname(file);
  await mkdir(directory, { recursive: true });
  const temporary = `${file}.${process.pid}.${randomUUID()}.tmp`;
  try {
    await writeFile(temporary, `${JSON.stringify(state, null, 2)}\n`, {
      encoding: "utf8",
      flag: "wx",
      mode: 0o600,
    });
    await rename(temporary, file);
  } finally {
    await rm(temporary, { force: true }).catch(() => {});
  }
}

/**
 * Durable, atomically replaced gameplay-model selection.
 *
 * The in-memory revision advances only after the new complete model/reasoning
 * pair is on disk. Calls are serialized so two operator requests cannot
 * combine into a torn selection.
 */
export class AgentModelSelectionStore {
  static async open(file, initialSelection) {
    const resolvedFile = path.resolve(file);
    let parsed;
    try {
      parsed = JSON.parse(await readFile(resolvedFile, "utf8"));
    } catch (error) {
      if (error?.code !== "ENOENT") {
        throw new Error(
          `could not read model selection state ${resolvedFile}: ${
            error instanceof Error ? error.message : String(error)
          }`,
        );
      }
      const initial = validateAgentSelection(initialSelection);
      const state = {
        version: STATE_VERSION,
        revision: 1,
        model: initial.model,
        reasoning: initial.reasoning,
        updatedAt: new Date().toISOString(),
      };
      await writeStateFile(resolvedFile, state);
      return new AgentModelSelectionStore(
        resolvedFile,
        initial,
        state.revision,
      );
    }

    if (
      parsed == null ||
      typeof parsed !== "object" ||
      parsed.version !== STATE_VERSION ||
      !Number.isSafeInteger(parsed.revision) ||
      parsed.revision < 1
    ) {
      throw new TypeError(
        `model selection state ${resolvedFile} has an unsupported format`,
      );
    }
    const current = validateAgentSelection(parsed);
    return new AgentModelSelectionStore(
      resolvedFile,
      current,
      parsed.revision,
    );
  }

  constructor(file, current, revision) {
    this.file = file;
    this.current = current;
    this.revision = revision;
    this.writeTail = Promise.resolve();
  }

  snapshot() {
    return publicSnapshot(this.current, this.revision);
  }

  catalog() {
    return AGENT_MODEL_CATALOG;
  }

  async set(patch) {
    const operation = this.writeTail
      .catch(() => {})
      .then(async () => {
        if (patch == null || typeof patch !== "object") {
          throw new TypeError("model selection patch must be an object");
        }
        const next = validateAgentSelection({
          model: patch.model ?? this.current.model,
          reasoning: patch.reasoning ?? this.current.reasoning,
        });
        if (
          next.model === this.current.model &&
          next.reasoning === this.current.reasoning
        ) {
          return this.snapshot();
        }
        const revision = this.revision + 1;
        await writeStateFile(this.file, {
          version: STATE_VERSION,
          revision,
          model: next.model,
          reasoning: next.reasoning,
          updatedAt: new Date().toISOString(),
        });
        this.current = next;
        this.revision = revision;
        return this.snapshot();
      });
    this.writeTail = operation;
    return operation;
  }
}

function normalizedRequester(value) {
  if (value == null) return undefined;
  if (typeof value !== "object") {
    throw new TypeError("requester must be an object");
  }
  const kind = requiredString(value.kind, "requester.kind").toLowerCase();
  if (kind !== "player" && kind !== "console") {
    throw new TypeError("requester.kind must be player or console");
  }
  const name = requiredString(value.name, "requester.name");
  const uuid = optionalString(value.uuid, "requester.uuid");
  return Object.freeze({
    kind,
    name,
    ...(uuid == null ? {} : { uuid }),
  });
}

/**
 * Decode the trusted Forge -> sidecar control envelope:
 * {type:"brain_config_request",
 *  data:{requestId,action,model?,reasoning?,requester?,expiresAtEpochMillis}}
 */
export function decodeBrainConfigRequest(event) {
  if (event?.type !== "brain_config_request") return { kind: "other" };
  const data = event.data;
  const diagnostic = {};
  if (typeof data?.requestId === "string" && data.requestId.trim() !== "") {
    diagnostic.requestId = data.requestId.trim();
  }
  try {
    if (data == null || typeof data !== "object") {
      throw new TypeError("brain config request data must be an object");
    }
    const requestId = requiredString(data.requestId, "requestId");
    const action = requiredString(data.action, "action").toLowerCase();
    if (!["get", "list", "set"].includes(action)) {
      throw new TypeError("action must be get, list, or set");
    }
    const requester = normalizedRequester(data.requester);
    if (
      !Number.isSafeInteger(data.expiresAtEpochMillis) ||
      data.expiresAtEpochMillis < 1
    ) {
      throw new TypeError("expiresAtEpochMillis must be a positive integer");
    }
    const model = optionalString(data.model, "model");
    const reasoning = optionalString(data.reasoning, "reasoning")?.toLowerCase();
    if (action === "set" && (model == null || reasoning == null)) {
      throw new TypeError("set requires both model and reasoning");
    }
    if (action !== "set" && (model != null || reasoning != null)) {
      throw new TypeError(`${action} does not accept model or reasoning`);
    }
    return {
      kind: "request",
      request: Object.freeze({
        requestId,
        action,
        expiresAtEpochMillis: data.expiresAtEpochMillis,
        ...(model == null ? {} : { model }),
        ...(reasoning == null ? {} : { reasoning }),
        ...(requester == null ? {} : { requester }),
      }),
    };
  } catch (error) {
    return {
      kind: "invalid",
      ...diagnostic,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

function reportPayload(store, {
  requestId,
  requester,
  success,
  error,
} = {}) {
  return {
    ...(requestId == null ? {} : { request_id: requestId }),
    ...(requester == null ? {} : { requester }),
    success: success !== false,
    applied: success !== false,
    ...(error == null ? {} : { error: String(error).slice(0, 300) }),
    current: store.snapshot(),
    catalog: store.catalog(),
  };
}

/**
 * Applies decoded requests and reports the authoritative result through the
 * companion-independent MCP control plane.
 */
export class BrainConfigGateway {
  constructor(client, store) {
    this.client = client;
    this.store = store;
    this.pendingReports = new Map();
  }

  async announce() {
    const report = reportPayload(this.store);
    await this.client.reportBrainConfigState(report);
    return report;
  }

  async reject(decoded) {
    const key = decoded.requestId;
    let report = key == null ? null : this.pendingReports.get(key);
    if (report == null) {
      report = reportPayload(this.store, {
        requestId: key,
        success: false,
        error: decoded.error,
      });
      if (key != null) this.pendingReports.set(key, report);
    }
    try {
      await this.client.reportBrainConfigState(report);
      if (key != null) this.pendingReports.delete(key);
      return report;
    } catch (error) {
      throw error;
    }
  }

  async handle(request) {
    let report = this.pendingReports.get(request.requestId);
    if (report == null) {
      let success = true;
      let error;
      try {
        if (Date.now() > request.expiresAtEpochMillis) {
          throw new Error("brain config request expired before it was applied");
        }
        if (request.action === "set") {
          await this.store.set({
            ...(request.model == null ? {} : { model: request.model }),
            ...(request.reasoning == null
              ? {}
              : { reasoning: request.reasoning }),
          });
        }
      } catch (caught) {
        success = false;
        error = caught instanceof Error ? caught.message : String(caught);
      }
      report = reportPayload(this.store, {
        requestId: request.requestId,
        requester: request.requester,
        success,
        error,
      });
      this.pendingReports.set(request.requestId, report);
    }
    try {
      await this.client.reportBrainConfigState(report);
      this.pendingReports.delete(request.requestId);
      return report;
    } catch (error) {
      throw error;
    }
  }
}
