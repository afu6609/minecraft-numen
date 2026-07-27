import { setTimeout as delay } from "node:timers/promises";
import { pathToFileURL } from "node:url";

import { createCodexRuntimes } from "./codex-brain.mjs";
import { loadConfig } from "./config.mjs";
import { NumenMcpClient } from "./mcp-client.mjs";

function log(level, message, details = {}) {
  const entry = {
    time: new Date().toISOString(),
    level,
    message,
    ...details,
  };
  process.stdout.write(`${JSON.stringify(entry)}\n`);
}

async function verifyMcp(client) {
  await client.initialize();
  const tools = await client.listTools();
  const names = new Set(tools.map((tool) => tool.name));
  for (const required of ["list_companions", "poll_server_events", "send_chat"]) {
    if (!names.has(required)) {
      throw new Error(
        `Numen MCP is missing ${required}; install the matching momo/server-agent numen-api build`,
      );
    }
  }
}

export async function run({
  env = process.env,
  argv = process.argv.slice(2),
  importCodex = () => import("@openai/codex-sdk"),
} = {}) {
  const config = loadConfig(env);
  const client = new NumenMcpClient(config.mcpUrl, {
    token: config.mcpToken,
    timeoutMs: config.mcpTimeoutMs,
  });
  await verifyMcp(client);

  const { Codex } = await importCodex();
  const { router, brain } = createCodexRuntimes(Codex, config);
  const once = argv.includes("--once");
  let stopping = false;
  const stop = () => {
    stopping = true;
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);

  log("info", "Momo server brain started", {
    mcp: config.mcpUrl,
    companion: config.companion,
    classifierModel: config.classifierModel,
    agentModel: config.agentModel,
  });

  let consecutiveErrors = 0;
  const pending = [];
  do {
    try {
      if (pending.length === 0) {
        const events = await client.pollServerEvents(config.eventBatchSize);
        pending.push(...events.map((event) => ({ event, decision: null })));
      }
      if (pending.some((item) => item.decision == null)) {
        const decisions = await router.classify(pending.map((item) => item.event));
        for (let index = 0; index < pending.length; index += 1) {
          pending[index].decision = decisions[index];
        }
      }
      while (pending.length > 0) {
        const { event, decision } = pending[0];
        log("info", "chat routed", {
          eventId: event.id,
          player: event.playerName,
          route: decision.route,
          reason: decision.reason,
        });
        await brain.handle(event, decision);
        pending.shift();
      }
      consecutiveErrors = 0;
      if (!once && !stopping) {
        await delay(config.pollIntervalMs);
      }
    } catch (error) {
      consecutiveErrors += 1;
      log("error", "brain loop failed", {
        error: error instanceof Error ? error.message : String(error),
        consecutiveErrors,
      });
      if (once) throw error;
      const backoff = Math.min(30_000, 1_000 * 2 ** Math.min(consecutiveErrors - 1, 5));
      await delay(backoff);
    }
  } while (!once && !stopping);

  log("info", "Momo server brain stopped");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run().catch((error) => {
    log("fatal", "Momo server brain could not start", {
      error: error instanceof Error ? error.stack : String(error),
    });
    process.exitCode = 1;
  });
}
