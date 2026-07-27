import path from "node:path";

const REASONING_EFFORTS = new Set(["minimal", "low", "medium", "high", "xhigh"]);
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "::1", "[::1]"]);

function integer(env, key, fallback, { min, max }) {
  const raw = env[key];
  const value = raw == null || raw.trim() === "" ? fallback : Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new TypeError(`${key} must be an integer from ${min} to ${max}`);
  }
  return value;
}

function nonEmpty(env, key, fallback) {
  const value = (env[key] ?? fallback).trim();
  if (value === "") throw new TypeError(`${key} must not be empty`);
  return value;
}

function reasoningEffort(env, key, fallback) {
  const value = nonEmpty(env, key, fallback);
  if (!REASONING_EFFORTS.has(value)) {
    throw new TypeError(`${key} must be one of ${[...REASONING_EFFORTS].join(", ")}`);
  }
  return value;
}

export function loadConfig(env = process.env, cwd = process.cwd()) {
  const mcpUrl = new URL(nonEmpty(env, "NUMEN_MCP_URL", "http://127.0.0.1:8765/mcp"));
  if (mcpUrl.protocol !== "http:" && mcpUrl.protocol !== "https:") {
    throw new TypeError("NUMEN_MCP_URL must use http or https");
  }
  if (!LOOPBACK_HOSTS.has(mcpUrl.hostname) && env.MOMO_ALLOW_REMOTE_MCP !== "true") {
    throw new TypeError(
      "NUMEN_MCP_URL must remain loopback-only unless MOMO_ALLOW_REMOTE_MCP=true",
    );
  }

  return Object.freeze({
    mcpUrl: mcpUrl.toString(),
    mcpToken: env.NUMEN_MCP_TOKEN?.trim() ?? "",
    companion: nonEmpty(env, "MOMO_COMPANION", "momo"),
    classifierModel: nonEmpty(env, "MOMO_CLASSIFIER_MODEL", "gpt-5.4-mini"),
    agentModel: nonEmpty(env, "MOMO_AGENT_MODEL", "gpt-5.4"),
    classifierReasoning: reasoningEffort(
      env,
      "MOMO_CLASSIFIER_REASONING",
      "minimal",
    ),
    agentReasoning: reasoningEffort(env, "MOMO_AGENT_REASONING", "medium"),
    pollIntervalMs: integer(env, "MOMO_POLL_INTERVAL_MS", 750, {
      min: 250,
      max: 60_000,
    }),
    eventBatchSize: integer(env, "MOMO_EVENT_BATCH_SIZE", 16, {
      min: 1,
      max: 64,
    }),
    mcpTimeoutMs: integer(env, "MOMO_MCP_TIMEOUT_MS", 10_000, {
      min: 1_000,
      max: 120_000,
    }),
    agentToolTimeoutSeconds: integer(
      env,
      "MOMO_AGENT_TOOL_TIMEOUT_SECONDS",
      330,
      { min: 10, max: 3_600 },
    ),
    codexPath: env.CODEX_PATH?.trim() || undefined,
    workingDirectory: path.resolve(env.MOMO_WORKING_DIRECTORY?.trim() || cwd),
  });
}
