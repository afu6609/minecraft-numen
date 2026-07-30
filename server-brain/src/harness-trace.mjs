import { createHash } from "node:crypto";
import {
  appendFile,
  mkdir,
  rename,
  rm,
  stat,
} from "node:fs/promises";
import path from "node:path";

const TRACE_FIELDS = new Set([
  "goal_id",
  "trace_id",
  "turn_id",
  "kind",
  "profile",
  "recovery",
  "full_capsule",
  "server",
  "tool",
  "status",
  "tool_calls",
  "elapsed_ms",
  "timed_out",
  "task_id",
  "action_id",
  "job_id",
  "terminal_event_id",
  "skill_id",
  "skill_version",
  "macro_task_id",
  "inner_task_id",
  "active_task_ids",
  "missing_task_count",
  "foreign_live_task_count",
  "unresolved_turn_closed",
  "server_session_changed",
  "reason",
]);

function fingerprint(value) {
  return createHash("sha256")
    .update(String(value))
    .digest("hex")
    .slice(0, 16);
}

function safeTraceFields(fields) {
  const safe = {};
  for (const [key, value] of Object.entries(fields)) {
    if (!TRACE_FIELDS.has(key) || value == null) continue;
    if (typeof value === "string") {
      safe[key] =
        key === "reason" && !/^[a-z0-9_.:-]{1,80}$/iu.test(value)
          ? `sha256:${fingerprint(value)}`
          : value.slice(0, 160);
    } else if (
      typeof value === "number" &&
      Number.isFinite(value)
    ) {
      safe[key] = value;
    } else if (typeof value === "boolean") {
      safe[key] = value;
    } else if (Array.isArray(value)) {
      safe[key] = value
        .filter((item) => typeof item === "string")
        .slice(0, 16)
        .map((item) => item.slice(0, 160));
    }
  }
  return safe;
}

function positiveInteger(value, fallback) {
  return Number.isInteger(value) && value > 0 ? value : fallback;
}

export class GoalBudget {
  constructor(
    {
      maxTurns = 24,
      maxToolCalls = 96,
      maxRecoveryTurns = 12,
      maxElapsedMs = 45 * 60 * 1000,
    } = {},
    restored = null,
  ) {
    this.limits = Object.freeze({
      maxTurns: positiveInteger(maxTurns, 24),
      maxToolCalls: positiveInteger(maxToolCalls, 96),
      maxRecoveryTurns: positiveInteger(maxRecoveryTurns, 12),
      maxElapsedMs: positiveInteger(maxElapsedMs, 45 * 60 * 1000),
    });
    this.goalId = typeof restored?.goal_id === "string"
      ? restored.goal_id
      : null;
    this.traceId = typeof restored?.trace_id === "string"
      ? restored.trace_id
      : null;
    this.generation = Number.isInteger(restored?.generation)
      ? Math.max(0, restored.generation)
      : 0;
    this.startedAt = Number.isFinite(restored?.started_at)
      ? restored.started_at
      : Date.now();
    this.turns = Number.isInteger(restored?.turns)
      ? Math.max(0, restored.turns)
      : 0;
    this.toolCalls = Number.isInteger(restored?.tool_calls)
      ? Math.max(0, restored.tool_calls)
      : 0;
    this.recoveryTurns = Number.isInteger(restored?.recovery_turns)
      ? Math.max(0, restored.recovery_turns)
      : 0;
    this.modelElapsedMs = Number.isFinite(restored?.model_elapsed_ms)
      ? Math.max(0, restored.model_elapsed_ms)
      : 0;
  }

  begin(goalId, traceId, now = Date.now()) {
    this.goalId = goalId;
    this.traceId = traceId;
    this.generation = 0;
    this.startedAt = now;
    this.turns = 0;
    this.toolCalls = 0;
    this.recoveryTurns = 0;
    this.modelElapsedMs = 0;
  }

  renew(now = Date.now()) {
    this.generation += 1;
    this.startedAt = now;
    this.turns = 0;
    this.toolCalls = 0;
    this.recoveryTurns = 0;
    this.modelElapsedMs = 0;
  }

  allowance({ recovery = false, now = Date.now() } = {}) {
    if (this.goalId == null) return { allowed: true, reason: null };
    if (this.turns >= this.limits.maxTurns) {
      return { allowed: false, reason: "model_turn_budget" };
    }
    if (this.toolCalls >= this.limits.maxToolCalls) {
      return { allowed: false, reason: "tool_call_budget" };
    }
    if (recovery && this.recoveryTurns >= this.limits.maxRecoveryTurns) {
      return { allowed: false, reason: "recovery_turn_budget" };
    }
    if (this.modelElapsedMs >= this.limits.maxElapsedMs) {
      return { allowed: false, reason: "active_harness_time_budget" };
    }
    return { allowed: true, reason: null };
  }

  noteTurn({ toolCalls = 0, elapsedMs = 0, recovery = false } = {}) {
    this.turns += 1;
    this.toolCalls += Math.max(0, toolCalls);
    this.modelElapsedMs += Math.max(0, elapsedMs);
    if (recovery) this.recoveryTurns += 1;
  }

  noteToolCalls(count = 1, elapsedMs = 0) {
    this.toolCalls += Math.max(0, count);
    this.modelElapsedMs += Math.max(0, elapsedMs);
  }

  promptView(now = Date.now()) {
    return {
      generation: this.generation,
      remaining_turns: Math.max(0, this.limits.maxTurns - this.turns),
      remaining_tool_calls: Math.max(
        0,
        this.limits.maxToolCalls - this.toolCalls,
      ),
      remaining_recovery_turns: Math.max(
        0,
        this.limits.maxRecoveryTurns - this.recoveryTurns,
      ),
      remaining_elapsed_ms: Math.max(
        0,
        this.limits.maxElapsedMs - this.modelElapsedMs,
      ),
    };
  }

  snapshot() {
    return {
      goal_id: this.goalId,
      trace_id: this.traceId,
      generation: this.generation,
      started_at: this.startedAt,
      turns: this.turns,
      tool_calls: this.toolCalls,
      recovery_turns: this.recoveryTurns,
      model_elapsed_ms: this.modelElapsedMs,
    };
  }
}

/**
 * Ordered NDJSON trace writer. Records intentionally exclude prompts, chat,
 * inventories, coordinates, and tool arguments; only correlation and budgets
 * are retained.
 */
export class HarnessTrace {
  constructor(
    file,
    {
      onError = null,
      maxBytes = 8 * 1024 * 1024,
    } = {},
  ) {
    this.file = path.resolve(file);
    this.onError = typeof onError === "function" ? onError : null;
    this.tail = Promise.resolve();
    this.maxBytes =
      Number.isInteger(maxBytes) && maxBytes >= 1024
        ? maxBytes
        : 8 * 1024 * 1024;
    this.currentBytes = null;
    this.lastError = null;
  }

  record(type, fields = {}) {
    const entry = {
      time: new Date().toISOString(),
      type,
      ...safeTraceFields(fields),
    };
    this.tail = this.tail
      .then(async () => {
        await mkdir(path.dirname(this.file), { recursive: true });
        if (this.currentBytes == null) {
          try {
            this.currentBytes = (await stat(this.file)).size;
          } catch (error) {
            if (error?.code !== "ENOENT") throw error;
            this.currentBytes = 0;
          }
        }
        const line = `${JSON.stringify(entry)}\n`;
        const lineBytes = Buffer.byteLength(line, "utf8");
        if (
          this.currentBytes > 0 &&
          this.currentBytes + lineBytes > this.maxBytes
        ) {
          const rotated = `${this.file}.1`;
          await rm(rotated, { force: true });
          await rename(this.file, rotated);
          this.currentBytes = 0;
        }
        await appendFile(this.file, line, {
          encoding: "utf8",
          mode: 0o600,
        });
        this.currentBytes += lineBytes;
        this.lastError = null;
      })
      .catch((error) => {
        this.lastError = error;
        this.onError?.(error);
      });
  }

  async flush() {
    await this.tail;
    if (this.lastError != null) throw this.lastError;
  }
}
