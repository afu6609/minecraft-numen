import { setTimeout as delay } from "node:timers/promises";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

import { createCodexRuntimes } from "./codex-brain.mjs";
import { loadConfig } from "./config.mjs";
import { EventInbox } from "./event-inbox.mjs";
import { NumenMcpClient } from "./mcp-client.mjs";
import {
  parseServerCommandRequest,
  ServerCommandGateway,
} from "./server-command.mjs";
import {
  parseStopRequest,
  ServerControlGateway,
} from "./server-control.mjs";
import {
  decodeTestInstructionEvent,
  dispatchFreshTestInstruction,
} from "./arena-event.mjs";

function log(level, message, details = {}) {
  const entry = {
    time: new Date().toISOString(),
    level,
    message,
    ...details,
  };
  process.stdout.write(`${JSON.stringify(entry)}\n`);
}

function inboxPriority(event) {
  if (event?.type === "test_instruction") return 3;
  if (
    event?.type === "damage_received" ||
    event?.type === "defense_started" ||
    event?.type === "defense_finished" ||
    event?.type === "death" ||
    event?.type === "body_available"
  ) {
    return 2;
  }
  return event?.type === "task_finished" ? 1 : 0;
}

export async function verifyMcp(client) {
  await client.initialize();
  const tools = await client.listTools();
  const names = new Set(tools.map((tool) => tool.name));
  for (const required of [
    "list_companions",
    "poll_server_events",
    "poll_companion_events",
    "send_chat",
    "run_command",
    "task_stop",
    "follow_player",
    "structure_plan",
    "structure_status",
    "structure_execute",
    "structure_patch",
    "placement_feasibility",
    "survey_scene",
    "inspect_object",
    "observe_entity_intent",
    "get_combat_trace",
    "save_combat_policy",
    "combat_policy_status",
    "activate_combat_policy",
    "abort_combat_policy",
  ]) {
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
  const persona = (await readFile(config.personaFile, "utf8")).trim();
  if (persona === "") throw new Error("MOMO_PERSONA_FILE must not be empty");
  const { router, brain } = createCodexRuntimes(Codex, config, persona);
  const commandGateway = new ServerCommandGateway(client, config.companion);
  const controlGateway = new ServerControlGateway(client, config.companion);
  const once = argv.includes("--once");
  let stopping = false;
  const stop = () => {
    if (stopping) return;
    stopping = true;
    brain.interrupt({ preserveTaskRecovery: false });
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);

  log("info", "Momo server brain started", {
    mcp: config.mcpUrl,
    companion: config.companion,
    classifierModel: config.classifierModel,
    agentModel: config.agentModel,
    agentReasoning: config.agentReasoning,
    commandPlayers: config.commandPlayers,
  });

  const inbox = new EventInbox();
  let pollerError = null;
  const poller = (async () => {
    let consecutiveErrors = 0;
    let bodyPollErrors = 0;
    let deferredServerEvents = [];
    try {
      do {
        try {
          const events = deferredServerEvents.length > 0
            ? deferredServerEvents.splice(0)
            : await client.pollServerEvents(config.eventBatchSize);
          let bodyEvents = [];
          try {
            bodyEvents = await client.pollCompanionEvents(
              config.companion,
              config.eventBatchSize,
            );
            if (bodyPollErrors > 0) {
              log("info", "companion telemetry poll recovered", {
                previousErrors: bodyPollErrors,
              });
            }
            bodyPollErrors = 0;
          } catch (error) {
            bodyPollErrors += 1;
            if (bodyPollErrors === 1 || bodyPollErrors % 10 === 0) {
              log("warn", "companion telemetry temporarily unavailable", {
                error: error instanceof Error ? error.message : String(error),
                consecutiveErrors: bodyPollErrors,
              });
            }
          }

          // Companion telemetry was captured before any fresh test fence in
          // this poll cycle. Queue it in the old generation first so a fresh
          // server event below can reliably cancel it.
          for (const event of bodyEvents) {
            if (event.type === "defense_started" || event.type === "death") {
              const interruptedTurn = brain.interrupt({
                preserveTaskRecovery: true,
              });
              log(
                event.type === "death" ? "warn" : "info",
                "urgent body event interrupted stale reasoning",
                {
                  eventId: event.id,
                  companion: event.companionName,
                  type: event.type,
                  interruptedTurn,
                },
              );
            }
            inbox.push({
              event,
              decision: null,
              commandRequest: null,
            });
          }

          for (
            let eventIndex = 0;
            eventIndex < events.length;
            eventIndex += 1
          ) {
            const event = events[eventIndex];
            const decodedTestInstruction = decodeTestInstructionEvent(
              event,
              config.companion,
            );
            if (decodedTestInstruction.kind === "invalid") {
              log("warn", "invalid test instruction ignored", {
                eventId: event.id,
                reason: decodedTestInstruction.reason,
              });
              continue;
            }
            if (decodedTestInstruction.kind === "test_instruction") {
              const testEvent = decodedTestInstruction.event;
              if (testEvent.freshThread) {
                const dispatch = await dispatchFreshTestInstruction({
                  client,
                  companion: config.companion,
                  event: testEvent,
                  inbox,
                  brain,
                });
                if (!dispatch.ok) {
                  // The MCP event has already been drained. Retain it and all
                  // later events locally, then retry the stop fence before
                  // polling another server batch.
                  deferredServerEvents = events.slice(eventIndex);
                  log("warn", "fresh test instruction deferred", {
                    eventId: testEvent.id,
                    runId: testEvent.runId,
                    companion: testEvent.companionName,
                    interruptedTurn: dispatch.interruptedTurn,
                    reason: dispatch.reason,
                  });
                  break;
                }
                log("info", "fresh test instruction queued", {
                  eventId: testEvent.id,
                  runId: testEvent.runId,
                  companion: testEvent.companionName,
                  interruptedTurn: dispatch.interruptedTurn,
                });
                continue;
              }
              inbox.push({
                event: testEvent,
                decision: null,
                commandRequest: null,
              });
              continue;
            }
            const controlRequest = parseStopRequest(event);
            const commandRequest = parseServerCommandRequest(
              event,
              config.commandPlayers,
            );
            if (controlRequest != null) {
              inbox.cancelPlayerChatsThrough(event.id);
              const interruptedTurn = brain.interrupt({
                preserveTaskRecovery: false,
              });
              const result = await controlGateway.handle(event, controlRequest);
              log(result.ok ? "info" : "warn", "server control handled", {
                eventId: event.id,
                player: event.playerName,
                control: controlRequest.type,
                interruptedTurn,
                ok: result.ok,
                reason: result.reason,
              });
              continue;
            }
            inbox.push({
              event,
              decision: null,
              commandRequest,
            });
          }
          consecutiveErrors = 0;
          if (!once && !stopping) {
            await delay(config.pollIntervalMs);
          }
        } catch (error) {
          consecutiveErrors += 1;
          log("error", "event poller failed", {
            error: error instanceof Error ? error.message : String(error),
            consecutiveErrors,
          });
          if (once) throw error;
          const backoff = Math.min(
            30_000,
            1_000 * 2 ** Math.min(consecutiveErrors - 1, 5),
          );
          await delay(backoff);
        }
      } while (!once && !stopping);
    } catch (error) {
      pollerError = error;
    } finally {
      inbox.close();
    }
  })();

  try {
    while (true) {
      const pending = await inbox.takeAll();
      if (pending.length === 0 && inbox.closed) break;
      pending.sort(
        (left, right) =>
          inboxPriority(right.event) - inboxPriority(left.event),
      );

      const unclassified = pending.filter(
        (item) =>
          item.event.type === "player_chat" &&
          item.commandRequest == null &&
          item.decision == null &&
          !inbox.isCancelled(item.event),
      );
      if (unclassified.length > 0) {
        const decisions = await router.classify(
          unclassified.map((item) => item.event),
        );
        for (let index = 0; index < unclassified.length; index += 1) {
          unclassified[index].decision = decisions[index];
        }
      }

      for (const { event, decision, commandRequest } of pending) {
        if (inbox.isCancelled(event)) {
          log("info", "stale event cancelled by control fence", {
            eventId: event.id,
            type: event.type,
            player: event.playerName,
            status: event.status,
          });
          continue;
        }
        if (event.type === "test_instruction") {
          log("info", "test instruction dispatched directly", {
            eventId: event.id,
            runId: event.runId,
            companion: event.companionName,
            freshThread: event.freshThread,
            arenaAnchor: event.arenaAnchor,
          });
          try {
            await brain.handleTestInstruction(event);
          } catch (error) {
            log("error", "test instruction handling failed", {
              eventId: event.id,
              runId: event.runId,
              error: error instanceof Error ? error.message : String(error),
            });
          }
          continue;
        }
        if (event.type === "task_finished") {
          log(event.status === "done" ? "info" : "warn", "background task finished", {
            eventId: event.id,
            companion: event.companionName,
            taskId: event.taskId,
            task: event.taskName,
            status: event.status,
            result: event.message ?? "",
          });
          try {
            await brain.handleTaskEvent(event);
          } catch (error) {
            log("error", "background task reconciliation deferred", {
              eventId: event.id,
              error: error instanceof Error ? error.message : String(error),
            });
          }
          continue;
        }
        if (
          event.type === "damage_received" ||
          event.type === "defense_started" ||
          event.type === "death"
        ) {
          brain.noteBodyEvent(event);
          log("info", "companion body event buffered", {
            eventId: event.id,
            companion: event.companionName,
            type: event.type,
          });
          continue;
        }
        if (
          event.type === "defense_finished" ||
          event.type === "body_available"
        ) {
          log("info", "companion body context re-grounding", {
            eventId: event.id,
            companion: event.companionName,
            type: event.type,
          });
          try {
            await brain.handleBodyEvent(event);
          } catch (error) {
            // MomoBrain re-queues authoritative body/task context before
            // throwing. A transient model-capacity failure must not kill the
            // long-lived poller and lose the in-memory recovery context.
            log("error", "body context re-grounding deferred", {
              eventId: event.id,
              error: error instanceof Error ? error.message : String(error),
            });
            if (!once && !stopping) {
              await delay(5_000);
              try {
                await brain.retryBodyContext();
                log("info", "deferred body context re-grounding recovered", {
                  eventId: event.id,
                });
              } catch (retryError) {
                log("warn", "deferred body context remains queued", {
                  eventId: event.id,
                  error:
                    retryError instanceof Error
                      ? retryError.message
                      : String(retryError),
                });
              }
            }
          }
          continue;
        }
        if (event.type !== "player_chat") {
          log("warn", "unknown server event ignored", {
            eventId: event.id,
            type: event.type,
          });
          continue;
        }
        if (commandRequest != null) {
          const result = await commandGateway.handle(event, commandRequest);
          log(result.ok ? "info" : "warn", "server command handled", {
            eventId: event.id,
            player: event.playerName,
            command: commandRequest.command,
            ok: result.ok,
            reason: result.reason,
          });
          continue;
        }
        log("info", "chat routed", {
          eventId: event.id,
          player: event.playerName,
          route: decision.route,
          reason: decision.reason,
        });
        try {
          await brain.handle(event, decision);
        } catch (error) {
          log("error", "chat handling failed without stopping the poller", {
            eventId: event.id,
            player: event.playerName,
            error: error instanceof Error ? error.message : String(error),
          });
        }
      }
    }
  } finally {
    stopping = true;
    await poller;
  }
  if (pollerError != null) throw pollerError;

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
