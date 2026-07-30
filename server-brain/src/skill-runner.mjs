import { randomUUID } from "node:crypto";

import { executionPolicy } from "./experience-store.mjs";

const ACTIVE_STATES = new Set(["planning", "moving", "waiting"]);
const MAX_SKILL_ACTIVE_MS = 5 * 60 * 1000;
const DEFAULT_MAX_SKILL_WAIT_MS = 15 * 60 * 1000;
const DEFAULT_RECONCILE_INTERVAL_MS = 15 * 1000;
const MAX_PARAMETER_BYTES = 8 * 1024;

function toolData(payload) {
  return payload?.data != null && typeof payload.data === "object"
    ? payload.data
    : payload;
}

function isSuccessful(payload) {
  return (
    payload != null &&
    typeof payload === "object" &&
    payload.success === true
  );
}

function asyncReceipt(payload) {
  const data = toolData(payload);
  if (
    payload?.success === true &&
    data?.async === true &&
    typeof data.task_id === "string" &&
    data.task_id.trim() !== ""
  ) {
    return {
      taskId: data.task_id.trim(),
      actionId:
        typeof data.action_id === "string" && data.action_id.trim() !== ""
          ? data.action_id.trim()
          : null,
      jobId:
        typeof (data.job_id ?? data.job?.job_id) === "string"
          ? (data.job_id ?? data.job.job_id).trim()
          : null,
      serverSessionId:
        typeof (data.server_session_id ?? data.serverSessionId) === "string" &&
        (data.server_session_id ?? data.serverSessionId).trim() !== ""
          ? (data.server_session_id ?? data.serverSessionId).trim()
          : null,
    };
  }
  return null;
}

function resolveTemplates(value, parameters) {
  if (typeof value === "string") {
    const match = value.match(/^\$\{([a-z][a-z0-9_]*)\}$/u);
    return match == null ? value : structuredClone(parameters[match[1]]);
  }
  if (Array.isArray(value)) {
    return value.map((item) => resolveTemplates(item, parameters));
  }
  if (value != null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [
        key,
        resolveTemplates(item, parameters),
      ]),
    );
  }
  return value;
}

function inventoryCounts(status) {
  const counts = new Map();
  const data = toolData(status);
  for (const stack of Array.isArray(data?.inventory?.items)
    ? data.inventory.items
    : []) {
    if (typeof stack?.item !== "string") continue;
    const count = Number(stack.count ?? 1);
    if (!Number.isFinite(count) || count < 0) continue;
    counts.set(
      stack.item,
      (counts.get(stack.item) ?? 0) + count,
    );
  }
  return counts;
}

function position(status) {
  const value = toolData(status)?.position;
  return value != null &&
    Number.isFinite(value.x) &&
    Number.isFinite(value.y) &&
    Number.isFinite(value.z)
    ? value
    : null;
}

function verifierFailure(message) {
  const error = new Error(message);
  error.code = "SKILL_VERIFICATION_FAILED";
  return error;
}

function compactParameters(parameters) {
  return Object.fromEntries(
    Object.entries(parameters)
      .slice(0, 8)
      .map(([key, value]) => [
        key,
        typeof value === "string" ? value.slice(0, 80) : value,
      ]),
  );
}

function parameterKind(value) {
  if (Array.isArray(value)) return "array";
  if (value == null) return "null";
  return typeof value;
}

function validateParameterValue(value, fallback, field, depth = 0) {
  if (depth > 6) throw new TypeError(`${field} is nested too deeply`);
  if (parameterKind(value) !== parameterKind(fallback)) {
    throw new TypeError(`${field} must remain ${parameterKind(fallback)}`);
  }
  if (typeof value === "number" && !Number.isFinite(value)) {
    throw new TypeError(`${field} must be finite`);
  }
  if (typeof value === "string" && value.length > 500) {
    throw new TypeError(`${field} is too long`);
  }
  if (Array.isArray(value)) {
    if (value.length > 32) throw new TypeError(`${field} has too many items`);
    const exemplar = fallback[0];
    if (exemplar != null) {
      value.forEach((item, index) =>
        validateParameterValue(item, exemplar, `${field}[${index}]`, depth + 1),
      );
    }
  } else if (value != null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length > 32) throw new TypeError(`${field} has too many keys`);
    for (const [key, item] of entries) {
      if (!Object.hasOwn(fallback, key)) {
        throw new TypeError(`${field}.${key} is not declared`);
      }
      validateParameterValue(
        item,
        fallback[key],
        `${field}.${key}`,
        depth + 1,
      );
    }
  }
}

function validatedParameters(defaults, supplied) {
  if (
    supplied == null ||
    typeof supplied !== "object" ||
    Array.isArray(supplied)
  ) {
    throw new TypeError("skill parameters must be an object");
  }
  const unknown = Object.keys(supplied).filter(
    (key) => !Object.hasOwn(defaults, key),
  );
  if (unknown.length > 0) {
    throw new TypeError(`unknown skill parameters: ${unknown.join(", ")}`);
  }
  for (const [key, value] of Object.entries(supplied)) {
    validateParameterValue(value, defaults[key], `parameters.${key}`);
  }
  const parameters = {
    ...structuredClone(defaults),
    ...structuredClone(supplied),
  };
  if (Buffer.byteLength(JSON.stringify(parameters), "utf8") > MAX_PARAMETER_BYTES) {
    throw new TypeError("skill parameters are too large");
  }
  return parameters;
}

function restoredRun(value) {
  if (
    value == null ||
    typeof value !== "object" ||
    typeof value.macro_task_id !== "string" ||
    typeof value.skill_id !== "string" ||
    !Number.isInteger(value.skill_version) ||
    !Number.isInteger(value.step_index) ||
    value.parameters == null ||
    typeof value.parameters !== "object"
  ) {
    return null;
  }
  return {
    macroTaskId: value.macro_task_id,
    skillId: value.skill_id,
    skillVersion: value.skill_version,
    parameters: structuredClone(value.parameters),
    stepIndex: Math.max(0, value.step_index),
    waitingTaskId:
      typeof value.waiting_task_id === "string" ? value.waiting_task_id : null,
    waitingTool:
      typeof value.waiting_tool === "string" ? value.waiting_tool : null,
    waitingActionId:
      typeof value.waiting_action_id === "string"
        ? value.waiting_action_id
        : null,
    waitingJobId:
      typeof value.waiting_job_id === "string" ? value.waiting_job_id : null,
    waitingSince: Number.isFinite(value.waiting_since)
      ? value.waiting_since
      : Number.isFinite(value.started_at)
        ? value.started_at
        : Date.now(),
    startedAt: Number.isFinite(value.started_at)
      ? value.started_at
      : Date.now(),
    toolElapsedMs: Number.isFinite(value.tool_elapsed_ms)
      ? Math.max(0, value.tool_elapsed_ms)
      : 0,
    traceId: typeof value.trace_id === "string" ? value.trace_id : null,
    goalId: typeof value.goal_id === "string" ? value.goal_id : null,
    profile: typeof value.profile === "string" ? value.profile : "orient",
    baselineStatus:
      value.baseline_status != null && typeof value.baseline_status === "object"
        ? structuredClone(value.baseline_status)
        : null,
    lastResult:
      value.last_result != null && typeof value.last_result === "object"
        ? structuredClone(value.last_result)
        : null,
    cancelRequested: value.cancel_requested === true,
    startedAsync: value.started_async === true,
    serverSessionId:
      typeof value.server_session_id === "string"
        ? value.server_session_id
        : null,
    terminalPending:
      value.terminal_pending != null &&
      typeof value.terminal_pending === "object"
        ? structuredClone(value.terminal_pending)
        : null,
    restored: true,
  };
}

export class SkillRunner {
  constructor(
    store,
    client,
    companion,
    {
      restored = null,
      onChange = null,
      emitTerminal = null,
      trace = null,
      callTimeoutMs = 335_000,
      durableCheckpoint = null,
      authorizeToolCall = null,
      noteToolElapsed = null,
      onServerSessionChange = null,
      maxWaitMs = DEFAULT_MAX_SKILL_WAIT_MS,
      reconcileIntervalMs = DEFAULT_RECONCILE_INTERVAL_MS,
    } = {},
  ) {
    this.store = store;
    this.client = client;
    this.companion = companion;
    this.onChange = typeof onChange === "function" ? onChange : null;
    this.emitTerminal =
      typeof emitTerminal === "function" ? emitTerminal : null;
    this.trace = trace;
    this.callTimeoutMs = callTimeoutMs;
    this.durableCheckpoint =
      typeof durableCheckpoint === "function" ? durableCheckpoint : null;
    this.authorizeToolCall =
      typeof authorizeToolCall === "function" ? authorizeToolCall : null;
    this.noteToolElapsed =
      typeof noteToolElapsed === "function" ? noteToolElapsed : null;
    this.onServerSessionChange =
      typeof onServerSessionChange === "function"
        ? onServerSessionChange
        : null;
    this.maxWaitMs =
      Number.isInteger(maxWaitMs) && maxWaitMs > 0
        ? maxWaitMs
        : DEFAULT_MAX_SKILL_WAIT_MS;
    this.reconcileIntervalMs =
      Number.isInteger(reconcileIntervalMs) && reconcileIntervalMs > 0
        ? reconcileIntervalMs
        : DEFAULT_RECONCILE_INTERVAL_MS;
    this.active = restoredRun(restored);
    this.starting = false;
    this.startCancelled = false;
    this.handlingTaskEvent = false;
    this.runtimeReconcilePromise = null;
    this.lastRuntimeReconcileAt = 0;
    this.serverSessionId = this.active?.serverSessionId ?? null;
    this.invocationContext = null;
    this.catalogCache = [];
  }

  async refreshCatalog() {
    const skills = await this.store.list();
    this.catalogCache = skills
      .filter((skill) => skill.status !== "disabled")
      .map((skill) => ({
        id: skill.id,
        version: skill.version,
        status: skill.status,
        intent: skill.intent.slice(0, 120),
        description: skill.description.slice(0, 180),
        parameters: compactParameters(skill.parameters),
        stepTools: [...new Set(skill.steps.map((step) => step.tool))],
      }));
    return this.catalog();
  }

  catalog({ profile = null, goalId = null } = {}) {
    return this.catalogCache
      .filter(
        (entry) =>
          profile == null ||
          entry.stepTools.every((tool) =>
            this.#toolAuthorization({
              goalId,
              profile,
              tool,
              reserve: false,
            }).allowed
          ),
      )
      .map(({ stepTools: _stepTools, ...entry }) =>
        structuredClone(entry)
      )
      .slice(0, 12);
  }

  setInvocationContext(context) {
    this.invocationContext =
      context == null ? null : structuredClone(context);
  }

  setServerSessionId(serverSessionId) {
    if (typeof serverSessionId !== "string" || serverSessionId.trim() === "") {
      throw new TypeError("serverSessionId must be a non-empty string");
    }
    const normalized = serverSessionId.trim();
    const previous = this.serverSessionId;
    this.serverSessionId = normalized;
    if (previous !== normalized) {
      this.onServerSessionChange?.(normalized, previous);
      return true;
    }
    return false;
  }

  async synchronizeServerSession(
    serverSessionId,
    { reason = "runtime_server_session_observed" } = {},
  ) {
    if (typeof serverSessionId !== "string" || serverSessionId.trim() === "") {
      throw new TypeError("serverSessionId must be a non-empty string");
    }
    const normalized = serverSessionId.trim();
    const previous = this.serverSessionId;
    const run = this.active;
    const runSessionChanged =
      run != null &&
      (
        (
          run.serverSessionId != null &&
          run.serverSessionId !== normalized
        ) ||
        (
          run.serverSessionId == null &&
          run.startedAsync === true
        )
      );
    this.setServerSessionId(normalized);
    if (run == null) {
      return {
        changed: previous !== normalized,
        invalidatedMacroTaskId: null,
      };
    }
    if (run.terminalPending != null) {
      await this.#publishPendingTerminal(run);
      return {
        changed: previous !== normalized,
        invalidatedMacroTaskId: null,
        terminalReplayed: true,
      };
    }
    if (!runSessionChanged) {
      if (run.serverSessionId == null) {
        run.serverSessionId = normalized;
        await this.#durableCheckpoint();
      }
      return {
        changed: previous !== normalized,
        invalidatedMacroTaskId: null,
      };
    }

    const invalidatedMacroTaskId = run.macroTaskId;
    await this.#fail(
      verifierFailure(
        `Minecraft server session changed during skill execution (${reason})`,
      ),
      {
        emit: true,
        recordValidation: false,
        terminal: {
          status: "unknown_after_restart",
          task_id: run.waitingTaskId,
        },
      },
    );
    return {
      changed: true,
      invalidatedMacroTaskId,
    };
  }

  snapshot() {
    const run = this.active;
    if (run == null) return null;
    return {
      macro_task_id: run.macroTaskId,
      skill_id: run.skillId,
      skill_version: run.skillVersion,
      parameters: structuredClone(run.parameters),
      step_index: run.stepIndex,
      waiting_task_id: run.waitingTaskId,
      waiting_tool: run.waitingTool,
      waiting_action_id: run.waitingActionId,
      waiting_job_id: run.waitingJobId,
      waiting_since: run.waitingSince,
      started_at: run.startedAt,
      tool_elapsed_ms: run.toolElapsedMs,
      trace_id: run.traceId,
      goal_id: run.goalId,
      profile: run.profile,
      baseline_status: structuredClone(run.baselineStatus),
      last_result: structuredClone(run.lastResult),
      cancel_requested: run.cancelRequested,
      started_async: run.startedAsync,
      server_session_id: run.serverSessionId,
      terminal_pending:
        run.terminalPending == null
          ? null
          : structuredClone(run.terminalPending),
    };
  }

  async saveCandidate(draft, { expectedVersion = null } = {}) {
    const context = this.invocationContext ?? {};
    const guarded = {
      ...draft,
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
        source_task:
          context.goalId ??
          draft.provenance?.source_task ??
          "unverified_harness_draft",
        learned_at: new Date().toISOString(),
        last_evidence: "",
      },
    };
    const saved =
      expectedVersion == null
        ? await this.store.saveCandidate(guarded)
        : await this.store.saveRevision(
            guarded.id,
            expectedVersion,
            guarded,
          );
    await this.refreshCatalog();
    this.trace?.record("skill.drafted", {
      goal_id: context.goalId ?? null,
      trace_id: context.traceId ?? null,
      skill_id: saved.id,
      skill_version: saved.version,
    });
    return saved;
  }

  async run(skillId, suppliedParameters = {}) {
    if (this.active != null || this.starting) {
      throw new Error(
        this.active == null
          ? "another skill invocation is already being prepared"
          : `skill ${this.active.skillId} is already waiting on ${this.active.waitingTaskId ?? "a synchronous step"}`,
      );
    }
    this.starting = true;
    this.startCancelled = false;
    let run = null;
    try {
      const skill = await this.store.get(skillId);
      if (skill.status === "disabled") {
        throw new Error(`skill ${skillId} is disabled`);
      }
      const parameters = validatedParameters(
        skill.parameters,
        suppliedParameters,
      );
      const context = this.invocationContext ?? {};
      this.#preflightDefinition(skill, context);
      if (this.startCancelled) {
        throw verifierFailure("skill was cancelled while preparing");
      }
      const baselineStatus = await this.#call("get_self_status", {});
      if (!isSuccessful(baselineStatus)) {
        throw verifierFailure("get_self_status failed before skill execution");
      }
      if (this.startCancelled) {
        throw verifierFailure("skill was cancelled while preparing");
      }
      run = {
        macroTaskId: `skill-${randomUUID()}`,
        skillId: skill.id,
        skillVersion: skill.version,
        parameters,
        stepIndex: 0,
        waitingTaskId: null,
        waitingTool: null,
        waitingActionId: null,
        waitingJobId: null,
        waitingSince: null,
        startedAt: Date.now(),
        toolElapsedMs: 0,
        traceId: context.traceId ?? null,
        goalId: context.goalId ?? null,
        profile: context.profile ?? "orient",
        baselineStatus,
        lastResult: null,
        cancelRequested: false,
        startedAsync: false,
        restored: false,
        serverSessionId: this.serverSessionId,
        terminalPending: null,
      };
      this.active = run;
      await this.#durableCheckpoint();
      this.trace?.record("skill.started", {
        goal_id: run.goalId,
        trace_id: run.traceId,
        skill_id: run.skillId,
        skill_version: run.skillVersion,
        macro_task_id: run.macroTaskId,
      });

      await this.#verifyCollection(
        [{ type: "body_idle" }],
        run,
        { phase: "precondition", currentStatus: baselineStatus },
      );
      await this.#verifyCollection(
        skill.precondition_verifiers,
        run,
        { phase: "precondition", currentStatus: baselineStatus },
      );
      const result = await this.#advance(skill);
      if (result.state === "waiting") {
        return {
          success: true,
          message: `Skill ${skill.id} accepted and waiting for its body task.`,
          data: {
            async: true,
            task_id: run.macroTaskId,
            action_id: run.macroTaskId,
            task: "run_skill",
            server_session_id: run.serverSessionId,
            skill_id: skill.id,
            skill_version: skill.version,
          },
        };
      }
      return result.payload;
    } catch (error) {
      if (run != null && this.active === run) {
        await this.#fail(error, { emit: false });
      }
      throw error;
    } finally {
      this.starting = false;
      this.startCancelled = false;
    }
  }

  async handleTaskEvent(event) {
    if (this.runtimeReconcilePromise != null) {
      await this.runtimeReconcilePromise.catch(() => {});
    }
    this.handlingTaskEvent = true;
    try {
      return await this.#handleTaskEvent(event);
    } finally {
      this.handlingTaskEvent = false;
    }
  }

  async #handleTaskEvent(event) {
    const run = this.active;
    if (
      run == null ||
      typeof event?.taskId !== "string" ||
      event.taskId !== run.waitingTaskId ||
      (
        typeof event.companionName === "string" &&
        event.companionName.toLowerCase() !== this.companion.toLowerCase()
      ) ||
      (
        run.waitingActionId != null &&
        typeof event.actionId === "string" &&
        event.actionId !== run.waitingActionId
      ) ||
      (
        run.waitingTool != null &&
        typeof event.taskName === "string" &&
        event.taskName !== run.waitingTool
      ) ||
      (
        run.serverSessionId != null &&
        typeof (event.serverSessionId ?? event.server_session_id) ===
          "string" &&
        (event.serverSessionId ?? event.server_session_id) !==
          run.serverSessionId
      )
    ) {
      return { claimed: false };
    }
    const skill = await this.store.get(run.skillId);
    if (skill.version !== run.skillVersion) {
      const committed = skill.validations.execution_results.find(
        (entry) => entry.id === run.macroTaskId,
      );
      if (committed != null) {
        run.terminalPending = {
          status: committed.success ? "done" : "failed",
          message: committed.success
            ? `Skill ${run.skillId} completed with typed postconditions.`
            : `Skill ${run.skillId} failed its typed verification.`,
        };
        await this.#durableCheckpoint();
        await this.#publishPendingTerminal(run);
        return {
          claimed: true,
          state: committed.success ? "completed" : "failed",
        };
      }
      await this.#fail(
        verifierFailure(
          `skill ${run.skillId} changed from version ${run.skillVersion} to ${skill.version} while running`,
        ),
        { emit: true, recordValidation: false },
      );
      return { claimed: true, state: "failed" };
    }
    const step = skill.steps[run.stepIndex];
    if (step == null || step.tool !== run.waitingTool) {
      await this.#fail(
        verifierFailure("persisted skill step no longer matches its task receipt"),
        { emit: true, recordValidation: false },
      );
      return { claimed: true, state: "failed" };
    }
    const terminal = {
      status: event.status,
      task_id: event.taskId,
      event_id: event.id ?? null,
      message: event.message ?? "",
    };
    this.trace?.record("skill.inner_terminal", {
      goal_id: run.goalId,
      trace_id: run.traceId,
      skill_id: run.skillId,
      macro_task_id: run.macroTaskId,
      tool: run.waitingTool,
      task_id: event.taskId,
      terminal_event_id: event.id ?? null,
      status: event.status,
    });
    if (event.status !== "done") {
      const error = verifierFailure(
        `skill step ${run.stepIndex} ended with ${event.status}`,
      );
      await this.#fail(error, {
        emit: true,
        terminal,
        recordValidation: ![
          "stopped",
          "unknown_after_restart",
        ].includes(event.status),
      });
      return { claimed: true, state: "failed" };
    }
    if (run.cancelRequested) {
      await this.#fail(
        verifierFailure(
          `skill was cancelled while waiting: ${run.cancelReason ?? "operator stop"}`,
        ),
        {
          emit: true,
          terminal: { ...terminal, status: "stopped" },
          recordValidation: false,
        },
      );
      return { claimed: true, state: "failed" };
    }

    try {
      const verifiers =
        executionPolicy(skill) === "verify_each_step"
          ? step.verifiers
          : step.verifiers.filter((verifier) =>
              [
                "tool_success",
                "task_terminal_done",
                "kernel_verified_terminal",
              ].includes(verifier.type),
            );
      await this.#verifyCollection(verifiers, run, {
        phase: "terminal",
        payload: run.lastResult,
        terminal,
      });
      run.stepIndex += 1;
      run.waitingTaskId = null;
      run.waitingTool = null;
      run.waitingActionId = null;
      run.waitingJobId = null;
      run.waitingSince = null;
      run.lastResult = null;
      await this.#durableCheckpoint();
      const result = await this.#advance(skill);
      return { claimed: true, state: result.state };
    } catch (error) {
      await this.#fail(error, { emit: true, terminal });
      return { claimed: true, state: "failed" };
    }
  }

  cancelActive(reason = "operator stop") {
    if (this.active == null) {
      if (!this.starting) return false;
      this.startCancelled = true;
      return true;
    }
    this.active.cancelRequested = true;
    this.active.cancelReason = String(reason).slice(0, 180);
    this.#checkpoint();
    this.#cancelWaiting(this.active).catch((error) => {
      this.trace?.record("skill.cancel_failed", {
        goal_id: this.active?.goalId ?? null,
        trace_id: this.active?.traceId ?? null,
        skill_id: this.active?.skillId ?? null,
        macro_task_id: this.active?.macroTaskId ?? null,
        reason: error instanceof Error ? error.message : String(error),
      });
    });
    return true;
  }

  async #cancelWaiting(run) {
    if (run.waitingTaskId == null) return;
    if (run.waitingTool === "embodied_move_to") {
      await this.client.callToolJson(
        "embodied_nav_stop",
        {
          companion: this.companion,
          task_id: run.waitingTaskId,
        },
        { timeoutMs: this.callTimeoutMs },
      );
      return;
    }
    if (run.waitingJobId != null) {
      await this.client.callToolJson(
        "embodied_cancel_job",
        {
          companion: this.companion,
          job_id: run.waitingJobId,
        },
        { timeoutMs: this.callTimeoutMs },
      );
      return;
    }
    await this.client.callToolJson(
      "task_stop",
      {
        companion: this.companion,
        task_id: run.waitingTaskId,
      },
      { timeoutMs: this.callTimeoutMs },
    );
  }

  /**
   * Reconcile a waiting workflow against an authoritative, same-session body
   * snapshot. Cancellation can be finalized immediately once its exact inner
   * task is absent; an ordinary missing terminal remains conservative until
   * the bounded wait lease expires.
   */
  async reconcileRuntimeState(
    live,
    {
      now = Date.now(),
      reason = "runtime_skill_reconciliation",
    } = {},
  ) {
    if (this.runtimeReconcilePromise != null) {
      return this.runtimeReconcilePromise;
    }
    if (this.handlingTaskEvent) {
      return { checked: false, reason: "task_terminal_transition_in_progress" };
    }
    const operation = this.#reconcileRuntimeState(live, { now, reason });
    this.runtimeReconcilePromise = operation;
    try {
      return await operation;
    } finally {
      if (this.runtimeReconcilePromise === operation) {
        this.runtimeReconcilePromise = null;
      }
    }
  }

  async #reconcileRuntimeState(live, { now, reason }) {
    const run = this.active;
    if (run == null) return { checked: true, active: false };
    if (run.terminalPending != null) {
      await this.#publishPendingTerminal(run);
      return { checked: true, active: false, terminalReplayed: true };
    }
    if (
      typeof live?.serverSessionId !== "string" ||
      live.serverSessionId.trim() === "" ||
      !Array.isArray(live.activeTaskIds)
    ) {
      throw verifierFailure(
        "runtime skill reconciliation requires authoritative live body state",
      );
    }
    const liveSession = live.serverSessionId.trim();
    if (
      run.serverSessionId != null &&
      run.serverSessionId !== liveSession
    ) {
      const result = await this.synchronizeServerSession(liveSession, {
        reason,
      });
      return {
        checked: true,
        active: this.active != null,
        sessionChanged: true,
        ...result,
      };
    }
    this.setServerSessionId(liveSession);
    if (run.serverSessionId == null) {
      run.serverSessionId = liveSession;
      await this.#durableCheckpoint();
    }
    if (this.active !== run || run.waitingTaskId == null) {
      return {
        checked: true,
        active: this.active != null,
        waiting: false,
      };
    }

    const exactLive = live.activeTaskIds.includes(run.waitingTaskId);
    const waitingSince = Number.isFinite(run.waitingSince)
      ? run.waitingSince
      : run.startedAt;
    const leaseExpired = now - waitingSince >= this.maxWaitMs;
    if (!exactLive) {
      if (run.cancelRequested) {
        await this.#fail(
          verifierFailure(
            `skill cancellation reached authoritative idle (${reason})`,
          ),
          {
            emit: true,
            recordValidation: false,
            terminal: {
              status: "stopped",
              task_id: run.waitingTaskId,
            },
          },
        );
        return {
          checked: true,
          active: false,
          finalizedCancellation: true,
        };
      }
      if (leaseExpired) {
        await this.#fail(
          verifierFailure(
            `skill task ${run.waitingTaskId} disappeared after its bounded wait lease`,
          ),
          {
            emit: true,
            recordValidation: false,
            terminal: {
              status: "unknown_after_restart",
              task_id: run.waitingTaskId,
            },
          },
        );
        return {
          checked: true,
          active: false,
          leaseExpired: true,
          missingTerminal: true,
        };
      }
      return {
        checked: true,
        active: true,
        waiting: true,
        exact: false,
      };
    }

    if (leaseExpired || run.cancelRequested) {
      if (!run.cancelRequested) {
        run.cancelRequested = true;
        run.cancelReason = "bounded skill wait lease expired";
        await this.#durableCheckpoint();
      }
      // A stop request is idempotent and may have been lost with the transport
      // that carried it. Retry it on each bounded reconciliation interval
      // until authoritative live state proves that the inner task is gone.
      await this.#cancelWaiting(run);
      return {
        checked: true,
        active: true,
        waiting: true,
        leaseExpired,
        stopRequested: true,
        cancellationPending: true,
      };
    }
    return {
      checked: true,
      active: true,
      waiting: true,
      exact: true,
      leaseExpired,
    };
  }

  async reconcileRuntimeLease({
    now = Date.now(),
    force = false,
    reason = "periodic_skill_lease",
  } = {}) {
    const run = this.active;
    if (
      run == null ||
      run.waitingTaskId == null ||
      this.handlingTaskEvent
    ) {
      return { checked: false, reason: "no_waiting_skill" };
    }
    if (
      !force &&
      now - this.lastRuntimeReconcileAt < this.reconcileIntervalMs
    ) {
      return { checked: false, reason: "reconcile_interval" };
    }
    this.lastRuntimeReconcileAt = now;
    const live = await this.client.inspectBodyWork(this.companion, {
      jobIds:
        run.waitingJobId == null ? [] : [run.waitingJobId],
    });
    return this.reconcileRuntimeState(live, { now, reason });
  }

  async reconcileLiveState(live, { pendingTaskIds = [] } = {}) {
    const run = this.active;
    if (run == null) return { active: false };
    if (run.terminalPending != null) {
      await this.#publishPendingTerminal(run);
      return { active: false, terminalReplayed: true };
    }
    const skill = await this.store.get(run.skillId);
    const committed = skill.validations.execution_results.find(
      (entry) => entry.id === run.macroTaskId,
    );
    if (committed != null) {
      run.terminalPending = {
        status: committed.success ? "done" : "failed",
        message: committed.success
          ? `Skill ${run.skillId} completed with typed postconditions.`
          : `Skill ${run.skillId} failed its typed verification.`,
      };
      await this.#durableCheckpoint();
      await this.#publishPendingTerminal(run);
      return { active: false, terminalReplayed: true, committed: true };
    }
    const liveSession =
      typeof live?.serverSessionId === "string" ? live.serverSessionId : null;
    this.setServerSessionId(liveSession);
    if (
      (
        run.serverSessionId === liveSession &&
        run.waitingTaskId != null &&
        pendingTaskIds.includes(run.waitingTaskId)
      ) ||
      (
        run.serverSessionId === liveSession &&
        run.waitingTaskId != null &&
        live?.activeTaskIds?.includes(run.waitingTaskId)
      )
    ) {
      run.restored = false;
      await this.#durableCheckpoint();
      return { active: true, waiting: true, exact: true };
    }
    await this.#fail(
      verifierFailure(
        `persisted skill task ${run.waitingTaskId ?? "unknown"} is not live after restart`,
      ),
      {
        emit: true,
        recordValidation: false,
        terminal: {
          status: "unknown_after_restart",
          task_id: run.waitingTaskId,
        },
      },
    );
    return { active: false, recoveredFailure: true };
  }

  async #advance(skill) {
    const run = this.active;
    while (run.stepIndex < skill.steps.length) {
      if (run.cancelRequested) {
        throw verifierFailure("skill was cancelled before its next step");
      }
      if (run.toolElapsedMs >= MAX_SKILL_ACTIVE_MS) {
        throw verifierFailure(
          "skill active Harness time budget ended before its next step",
        );
      }
      const step = skill.steps[run.stepIndex];
      const args = resolveTemplates(step.args, run.parameters);
      const payload = await this.#call(step.tool, args);
      if (!isSuccessful(payload)) {
        throw verifierFailure(
          `skill tool ${step.tool} failed: ${String(payload?.message ?? "unknown failure")}`,
        );
      }
      const receipt = asyncReceipt(payload);
      run.lastResult = payload;
      if (receipt != null) {
        if (receipt.serverSessionId == null) {
          throw verifierFailure(
            `async skill step ${run.stepIndex} returned no server session`,
          );
        }
        if (
          run.serverSessionId != null &&
          run.serverSessionId !== receipt.serverSessionId
        ) {
          throw verifierFailure(
            "Minecraft server session changed while accepting a skill action",
          );
        }
        if (run.serverSessionId == null) {
          run.serverSessionId = receipt.serverSessionId;
          this.setServerSessionId(receipt.serverSessionId);
        }
        if (
          !step.verifiers.some((verifier) =>
            ["task_terminal_done", "kernel_verified_terminal"].includes(
              verifier.type,
            ),
          )
        ) {
          throw verifierFailure(
            `async skill step ${run.stepIndex} has no terminal verifier`,
          );
        }
        run.waitingTaskId = receipt.taskId;
        run.waitingTool = step.tool;
        run.waitingActionId = receipt.actionId;
        run.waitingJobId = receipt.jobId;
        run.waitingSince = Date.now();
        run.startedAsync = true;
        await this.#durableCheckpoint();
        this.trace?.record("skill.inner_task_accepted", {
          goal_id: run.goalId,
          trace_id: run.traceId,
          skill_id: run.skillId,
          macro_task_id: run.macroTaskId,
          tool: step.tool,
          task_id: receipt.taskId,
          action_id: receipt.actionId,
        });
        return { state: "waiting" };
      }
      if (
        step.verifiers.some((verifier) =>
          ["task_terminal_done", "kernel_verified_terminal"].includes(
            verifier.type,
          ),
        )
      ) {
        throw verifierFailure(
          `skill step ${run.stepIndex} expected an async task receipt`,
        );
      }

      if (executionPolicy(skill) === "verify_each_step") {
        await this.#verifyCollection(step.verifiers, run, {
          phase: "synchronous_step",
          payload,
        });
      } else {
        await this.#verifyCollection(
          step.verifiers.filter((verifier) => verifier.type === "tool_success"),
          run,
          { phase: "synchronous_step", payload },
        );
      }
      run.stepIndex += 1;
      run.lastResult = null;
      await this.#durableCheckpoint();
    }

    await this.#verifyCollection(skill.postcondition_verifiers, run, {
      phase: "postcondition",
      payload: run.lastResult,
    });
    const completed = {
      macroTaskId: run.macroTaskId,
      skillId: run.skillId,
      skillVersion: run.skillVersion,
      goalId: run.goalId,
      traceId: run.traceId,
      serverSessionId: run.serverSessionId,
    };
    await this.store.recordValidation(run.skillId, {
      success: true,
      evidence: "typed Harness postconditions verified",
      executionId: run.macroTaskId,
      expectedVersion: run.skillVersion,
    });
    this.trace?.record("skill.completed", {
      goal_id: completed.goalId,
      trace_id: completed.traceId,
      skill_id: completed.skillId,
      macro_task_id: completed.macroTaskId,
    });
    const payload = {
      success: true,
      message: `Skill ${completed.skillId} completed with typed postconditions.`,
      data: {
        async: false,
        skill_id: completed.skillId,
        skill_version: completed.skillVersion,
        postcondition: { verified: true },
      },
    };
    if (run.startedAsync === true) {
      run.terminalPending = {
        status: "done",
        message: payload.message,
      };
      await this.#durableCheckpoint();
      await this.#publishPendingTerminal(run);
    } else {
      this.active = null;
      await this.#durableCheckpoint();
    }
    await this.refreshCatalog().catch(() => {});
    return { state: "completed", payload, completed };
  }

  async #verifyCollection(verifiers, run, context) {
    let currentStatus = context.currentStatus ?? null;
    let taskStatus = null;
    let navStatus = null;
    const needStatus = verifiers.some((verifier) =>
      [
        "inventory_at_least",
        "inventory_delta",
        "position_within",
        "health_at_least",
      ].includes(verifier.type),
    );
    if (needStatus && currentStatus == null) {
      currentStatus = await this.#call("get_self_status", {});
    }
    if (needStatus && !isSuccessful(currentStatus)) {
      throw verifierFailure("authoritative self status was unavailable");
    }
    if (verifiers.some((verifier) => verifier.type === "body_idle")) {
      taskStatus = await this.#call("task_status", {});
      navStatus = await this.#call("embodied_nav_status", {});
      if (!isSuccessful(taskStatus) || !isSuccessful(navStatus)) {
        throw verifierFailure("body idle status was unavailable");
      }
      const sessionId = toolData(taskStatus)?.server_session_id;
      if (typeof sessionId !== "string" || sessionId.trim() === "") {
        throw verifierFailure(
          "body_idle task status had no authoritative server session",
        );
      }
      const normalizedSessionId = sessionId.trim();
      const beforeFirstStep =
        context.phase === "precondition" &&
        run.stepIndex === 0 &&
        run.startedAsync !== true &&
        run.lastResult == null;
      if (
        run.serverSessionId != null &&
        run.serverSessionId !== normalizedSessionId &&
        !beforeFirstStep
      ) {
        throw verifierFailure(
          "Minecraft server session changed during skill execution",
        );
      }
      if (
        run.serverSessionId !== normalizedSessionId ||
        this.serverSessionId !== normalizedSessionId
      ) {
        run.serverSessionId = normalizedSessionId;
        this.setServerSessionId(normalizedSessionId);
        await this.#durableCheckpoint();
      }
    }

    for (const raw of verifiers) {
      const verifier = resolveTemplates(raw, run.parameters);
      if (verifier.type === "tool_success") {
        if (!isSuccessful(context.payload)) {
          throw verifierFailure("tool_success verifier failed");
        }
      } else if (
        verifier.type === "task_terminal_done" ||
        verifier.type === "kernel_verified_terminal"
      ) {
        if (context.phase === "terminal" && context.terminal?.status !== "done") {
          throw verifierFailure(`${verifier.type} verifier failed`);
        }
      } else if (verifier.type === "body_idle") {
        const taskData = toolData(taskStatus);
        if (
          (
            taskData?.task_id != null &&
            ["running", "queued"].includes(taskData?.state)
          ) ||
          ACTIVE_STATES.has(toolData(navStatus)?.state)
        ) {
          throw verifierFailure("body_idle verifier found active work");
        }
      } else if (verifier.type === "health_at_least") {
        const hp = Number(toolData(currentStatus)?.hp);
        const minimum = Number(verifier.value);
        if (
          !Number.isFinite(hp) ||
          !Number.isFinite(minimum) ||
          hp < minimum
        ) {
          throw verifierFailure("health_at_least verifier failed");
        }
      } else if (
        verifier.type === "inventory_at_least" ||
        verifier.type === "inventory_delta"
      ) {
        const item = String(verifier.item);
        const current = inventoryCounts(currentStatus).get(item) ?? 0;
        const required = Number(
          verifier.type === "inventory_at_least"
            ? verifier.count
            : verifier.minimum,
        );
        const actual =
          verifier.type === "inventory_at_least"
            ? current
            : current - (inventoryCounts(run.baselineStatus).get(item) ?? 0);
        if (!Number.isFinite(required) || actual < required) {
          throw verifierFailure(`${verifier.type} verifier failed for ${item}`);
        }
      } else if (verifier.type === "position_within") {
        const current = position(currentStatus);
        const target = {
          x: Number(verifier.x),
          y: Number(verifier.y),
          z: Number(verifier.z),
          maxDistance: Number(verifier.max_distance),
        };
        if (
          !Number.isFinite(target.x) ||
          !Number.isFinite(target.y) ||
          !Number.isFinite(target.z) ||
          !Number.isFinite(target.maxDistance) ||
          target.maxDistance < 0
        ) {
          throw verifierFailure("position_within verifier was not finite");
        }
        const dx = current == null ? Infinity : current.x - target.x;
        const dy = current == null ? Infinity : current.y - target.y;
        const dz = current == null ? Infinity : current.z - target.z;
        const distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > target.maxDistance) {
          throw verifierFailure("position_within verifier failed");
        }
      } else if (verifier.type === "structure_phase") {
        let payload = context.payload;
        if (verifier.workflow_id != null) {
          payload = await this.#call("structure_status", {
            workflow_id: verifier.workflow_id,
          });
        }
        if (!isSuccessful(payload)) {
          throw verifierFailure("structure status was unavailable");
        }
        if (toolData(payload)?.phase !== verifier.phase) {
          throw verifierFailure("structure_phase verifier failed");
        }
      }
    }
  }

  async #call(tool, args) {
    const goalId =
      this.active?.goalId ??
      this.invocationContext?.goalId ??
      null;
    const profile =
      this.active?.profile ??
      this.invocationContext?.profile ??
      null;
    const authorization = this.#toolAuthorization({
      goalId,
      profile,
      tool,
      reserve: true,
    });
    if (!authorization.allowed) {
      throw verifierFailure(
        `skill tool authorization rejected ${tool}: ${authorization?.reason ?? "not authorized"}`,
      );
    }
    const started = Date.now();
    let payload;
    let failure = null;
    try {
      payload = await this.client.callToolJson(
        tool,
        { ...args, companion: this.companion },
        { timeoutMs: this.callTimeoutMs },
      );
      return payload;
    } catch (error) {
      failure = error;
      throw error;
    } finally {
      const elapsedMs = Date.now() - started;
      if (this.active != null) {
        this.active.toolElapsedMs += elapsedMs;
      }
      this.noteToolElapsed?.({ goalId, tool, elapsedMs });
      this.trace?.record("skill.tool", {
        goal_id: this.active?.goalId ?? goalId,
        trace_id: this.active?.traceId ?? null,
        skill_id: this.active?.skillId ?? null,
        macro_task_id: this.active?.macroTaskId ?? null,
        tool,
        status:
          failure != null
            ? "failed"
            : isSuccessful(payload)
              ? "completed"
              : "failed",
        elapsed_ms: elapsedMs,
      });
    }
  }

  #preflightDefinition(skill, context) {
    for (const tool of new Set(skill.steps.map((step) => step.tool))) {
      const authorization = this.#toolAuthorization({
        goalId: context.goalId ?? null,
        profile: context.profile ?? null,
        tool,
        reserve: false,
      });
      if (!authorization.allowed) {
        throw verifierFailure(
          `skill definition rejected before execution at ${tool}: ${authorization.reason ?? "not authorized"}`,
        );
      }
    }
  }

  #toolAuthorization({ goalId, profile, tool, reserve }) {
    if (this.authorizeToolCall == null) {
      return { allowed: true, reason: null };
    }
    const authorization = this.authorizeToolCall({
      goalId,
      profile,
      tool,
      reserve,
    });
    if (authorization === true || authorization?.allowed === true) {
      return { allowed: true, reason: null };
    }
    return {
      allowed: false,
      reason: authorization?.reason ?? "not authorized",
    };
  }

  async #fail(
    error,
    {
      emit,
      terminal = null,
      recordValidation = true,
    },
  ) {
    const run = this.active;
    if (run == null) return;
    if (recordValidation) {
      try {
        await this.store.recordValidation(run.skillId, {
          success: false,
          evidence: String(error?.message ?? error).slice(0, 2_048),
          executionId: run.macroTaskId,
          expectedVersion: run.skillVersion,
        });
      } catch {
        // The original execution failure remains authoritative.
      }
    }
    const completed = {
      macroTaskId: run.macroTaskId,
      skillId: run.skillId,
      skillVersion: run.skillVersion,
      goalId: run.goalId,
      traceId: run.traceId,
      serverSessionId: run.serverSessionId,
    };
    this.trace?.record("skill.failed", {
      goal_id: completed.goalId,
      trace_id: completed.traceId,
      skill_id: completed.skillId,
      macro_task_id: completed.macroTaskId,
      inner_task_id: terminal?.task_id ?? null,
      status: terminal?.status ?? "failed",
      reason: String(error?.message ?? error).slice(0, 180),
    });
    if (emit) {
      const terminalStatus =
        terminal?.status === "stopped" ||
        terminal?.status === "unknown_after_restart"
          ? terminal.status
          : "failed";
      run.terminalPending = {
        status: terminalStatus,
        message: String(error?.message ?? error).slice(0, 240),
      };
      await this.#durableCheckpoint();
      await this.#publishPendingTerminal(run);
    } else {
      this.active = null;
      await this.#durableCheckpoint();
    }
    await this.refreshCatalog().catch(() => {});
  }

  async #publishPendingTerminal(run) {
    const pending = run.terminalPending;
    if (pending == null) return;
    this.#emitMacroTerminal(
      {
        macroTaskId: run.macroTaskId,
        skillId: run.skillId,
        skillVersion: run.skillVersion,
        serverSessionId:
          this.serverSessionId ?? run.serverSessionId,
      },
      pending.status,
      pending.message,
    );
    await this.durableCheckpoint?.();
    if (this.active === run) {
      this.active = null;
      await this.#durableCheckpoint();
    }
  }

  #emitMacroTerminal(completed, status, message) {
    this.emitTerminal?.({
      id: `skill-terminal-${completed.macroTaskId}`,
      type: "task_finished",
      companionName: this.companion,
      taskId: completed.macroTaskId,
      actionId: completed.macroTaskId,
      taskName: "run_skill",
      status,
      message,
      skillId: completed.skillId,
      skillVersion: completed.skillVersion,
      serverSessionId: completed.serverSessionId ?? null,
    });
  }

  #checkpoint() {
    this.onChange?.(this.snapshot());
  }

  async #durableCheckpoint() {
    this.#checkpoint();
    await this.durableCheckpoint?.();
  }
}
