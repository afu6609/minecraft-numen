import { setTimeout as delay } from "node:timers/promises";
import { readFile } from "node:fs/promises";
import { randomBytes } from "node:crypto";
import { pathToFileURL } from "node:url";

import { BrainStateStore } from "./brain-state-store.mjs";
import { createCodexRuntimes } from "./codex-brain.mjs";
import { loadConfig } from "./config.mjs";
import { EventInbox } from "./event-inbox.mjs";
import { ExperienceStore } from "./experience-store.mjs";
import { HarnessMcpServer } from "./harness-mcp-server.mjs";
import { HarnessTrace } from "./harness-trace.mjs";
import { LandmarkStore } from "./landmark-store.mjs";
import { NumenMcpClient } from "./mcp-client.mjs";
import { SkillRunner } from "./skill-runner.mjs";
import { GAMEPLAY_ENABLED_TOOLS } from "./tool-capabilities.mjs";
import {
  AgentModelSelectionStore,
  BrainConfigGateway,
  decodeBrainConfigRequest,
} from "./model-control.mjs";
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

const MAX_CONTROL_EVENT_ATTEMPTS = 12;
const MAX_QUARANTINED_SERVER_EVENTS = 32;

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
  if (event?.type === "test_instruction") return 5;
  if (
    event?.type === "damage_received" ||
    event?.type === "defense_started" ||
    event?.type === "death"
  ) {
    return 4;
  }
  if (event?.type === "task_finished") return 3;
  if (
    event?.type === "defense_finished" ||
    event?.type === "body_available"
  ) return 2;
  if (event?.type === "harness_recovery") return -1;
  return 0;
}

function pendingTaskIds(inbox) {
  const snapshot = inbox.snapshot();
  return [...snapshot.items, ...snapshot.inFlight]
    .map((entry) => entry.item?.event)
    .filter((event) => event?.type === "task_finished")
    .map((event) => event.taskId)
    .filter((taskId) => typeof taskId === "string");
}

function durableServerEventKey(event) {
  if (
    event == null ||
    typeof event !== "object" ||
    typeof event.type !== "string"
  ) {
    return null;
  }
  const sessionId =
    event.serverSessionId ??
    event.server_session_id ??
    "";
  const receivedAt =
    event.receivedAtEpochMillis ??
    event.received_at_epoch_millis ??
    event.receivedAt ??
    "";
  const identity =
    ["string", "number"].includes(typeof event.id)
      ? event.id
      : event.runId ??
        event.taskId ??
        event.data?.requestId ??
        event.message ??
        "malformed";
  return [
    event.type,
    String(sessionId),
    String(identity),
    String(receivedAt),
  ].join(":");
}

function eventServerSessionId(event) {
  const value =
    event?.serverSessionId ??
    event?.server_session_id ??
    null;
  return typeof value === "string" && value.trim() !== ""
    ? value.trim()
    : null;
}

function isServerWideControlEvent(event) {
  return decodeBrainConfigRequest(event).kind !== "other";
}

export function shouldReplayTransportEvent(event, liveServerSessionId) {
  const eventSessionId = eventServerSessionId(event);
  return (
    isServerWideControlEvent(event) ||
    eventSessionId === liveServerSessionId
  );
}

export function shouldReplayTransportForCompanion(
  event,
  transportCompanion,
  configuredCompanion,
) {
  return (
    transportCompanion == null ||
    transportCompanion === configuredCompanion ||
    isServerWideControlEvent(event)
  );
}

export async function verifyMcp(client) {
  await client.initialize();
  const tools = await client.listTools();
  const names = new Set(tools.map((tool) => tool.name));
  const requiredTools = new Set([
    "list_companions",
    "poll_server_events",
    "poll_companion_events",
    "run_command",
    "report_brain_config_state",
    ...GAMEPLAY_ENABLED_TOOLS,
  ]);
  for (const required of requiredTools) {
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
  const stateStore = await BrainStateStore.open(config.brainStateFile, {
    onError: (error) =>
      log("error", "Harness state checkpoint failed", {
        error: error instanceof Error ? error.message : String(error),
      }),
  });
  const restoredBrain = stateStore.restoredBrain();
  const compatibleRestoredState =
    restoredBrain == null ||
    (
      restoredBrain.snapshot_version === 1 &&
      restoredBrain.companion === config.companion
    );
  if (!compatibleRestoredState) {
    log("warn", "discarding incompatible Harness state", {
      configuredCompanion: config.companion,
      restoredCompanion: restoredBrain?.companion ?? null,
      snapshotVersion: restoredBrain?.snapshot_version ?? null,
    });
  }
  const usableRestoredBrain = compatibleRestoredState
    ? restoredBrain
    : null;
  const trace = new HarnessTrace(config.traceFile, {
    onError: (error) =>
      log("warn", "Harness trace append failed", {
        error: error instanceof Error ? error.message : String(error),
      }),
    maxBytes: config.traceMaxMb * 1024 * 1024,
  });
  let brain = null;
  const landmarkStore = await LandmarkStore.open(config.landmarkFile, {
    worldKey: config.worldKey,
    companion: config.companion,
    onError: (error) =>
      log("warn", "landmark checkpoint failed", {
        error: error instanceof Error ? error.message : String(error),
      }),
  });
  const inbox = new EventInbox({
    restored: compatibleRestoredState
      ? stateStore.restoredInbox()
      : null,
    serverSessionId: usableRestoredBrain?.server_session_id ?? null,
    onChange: (snapshot) => stateStore.scheduleInbox(snapshot),
  });
  const experienceStore = new ExperienceStore(config.experienceDirectory);
  const skillRunner = new SkillRunner(
    experienceStore,
    client,
    config.companion,
    {
      restored: usableRestoredBrain?.active_skill_run,
      onChange: () => brain?.checkpoint("skill_runner_changed"),
      emitTerminal: (event) =>
        inbox.push({
          event,
          decision: null,
          commandRequest: null,
        }),
      trace,
      callTimeoutMs: config.agentToolTimeoutSeconds * 1_000,
      durableCheckpoint: () => stateStore.flush(),
      authorizeToolCall: ({ goalId, profile, tool, reserve = true }) =>
        brain?.reserveSkillToolCall(goalId, {
          profile,
          tool,
          reserve,
        }) ?? {
          allowed: false,
          reason: "brain_not_ready",
        },
      noteToolElapsed: ({ goalId, elapsedMs }) =>
        brain?.noteSkillToolElapsed(goalId, elapsedMs),
      onServerSessionChange: (serverSessionId) => {
        inbox.synchronizeServerSession(serverSessionId);
        return brain?.synchronizeServerSession(serverSessionId, {
          reason: "skill_runner_server_session_observed",
        });
      },
    },
  );
  await skillRunner.refreshCatalog();
  const harnessToken = randomBytes(32).toString("hex");
  process.env.MOMO_HARNESS_MCP_TOKEN = harnessToken;
  const harnessServer = new HarnessMcpServer(skillRunner, {
    port: config.harnessMcpPort,
    token: harnessToken,
    onError: (error) =>
      log("error", "Harness MCP request failed", {
        error: error instanceof Error ? error.message : String(error),
      }),
  });
  const harnessMcpUrl = await harnessServer.start();
  try {
  const modelSelection = await AgentModelSelectionStore.open(
    config.modelStateFile,
    {
      model: config.agentModel,
      reasoning: config.agentReasoning,
    },
  );
  const brainConfigGateway = new BrainConfigGateway(client, modelSelection);
  await brainConfigGateway.announce();

  const { Codex } = await importCodex();
  const persona = (await readFile(config.personaFile, "utf8")).trim();
  if (persona === "") throw new Error("MOMO_PERSONA_FILE must not be empty");
  const runtimes = createCodexRuntimes(
    Codex,
    { ...config, harnessMcpUrl },
    persona,
    modelSelection,
    {
      sendChat: (companion, message) =>
        client.sendChat(companion, message),
      restoredState: usableRestoredBrain,
      onStateChange: (snapshot) => stateStore.scheduleBrain(snapshot),
      durableCheckpoint: () => stateStore.flush(),
      trace,
      skillRunner,
      landmarkStore,
      planningAckEnabled: true,
    },
  );
  const { router } = runtimes;
  brain = runtimes.brain;
  const restoredTransport = stateStore.restoredTransport();
  let deferredServerEvents =
    Array.isArray(restoredTransport?.server_events)
      ? restoredTransport
          .server_events
          .filter(
            (event) =>
              event != null &&
              typeof event === "object" &&
              typeof event.type === "string",
          )
          .map((event) => structuredClone(event))
      : [];
  let quarantinedServerEvents = Array.isArray(
    restoredTransport?.quarantined_server_events,
  )
    ? restoredTransport.quarantined_server_events
        .filter(
          (entry) =>
            entry != null &&
            typeof entry === "object" &&
            entry.event != null &&
            typeof entry.event === "object",
        )
        .slice(-MAX_QUARANTINED_SERVER_EVENTS)
        .map((entry) => structuredClone(entry))
    : [];
  const controlEventFailures = new Map();
  for (const entry of Array.isArray(restoredTransport?.control_failures)
    ? restoredTransport.control_failures
    : []) {
    if (
      typeof entry?.key === "string" &&
      Number.isInteger(entry.attempts) &&
      entry.attempts > 0
    ) {
      controlEventFailures.set(entry.key, {
        attempts: entry.attempts,
        last_error:
          typeof entry.last_error === "string"
            ? entry.last_error.slice(0, 180)
            : "",
        last_attempt_at:
          typeof entry.last_attempt_at === "string"
            ? entry.last_attempt_at
            : null,
      });
    }
  }
  const checkpointDeferredServerEvents = () => {
    stateStore.scheduleTransport({
      companion: config.companion,
      server_events: deferredServerEvents.map((event) =>
        structuredClone(event),
      ),
      control_failures: [...controlEventFailures.entries()].map(
        ([key, failure]) => ({ key, ...structuredClone(failure) }),
      ),
      quarantined_server_events: quarantinedServerEvents.map((entry) =>
        structuredClone(entry),
      ),
    });
  };
  const deferServerEvent = (event) => {
    const key = durableServerEventKey(event);
    if (
      key != null &&
      deferredServerEvents.some(
        (candidate) => durableServerEventKey(candidate) === key,
      )
    ) {
      return false;
    }
    deferredServerEvents.push(structuredClone(event));
    checkpointDeferredServerEvents();
    return true;
  };
  const completeDeferredServerEvent = (event) => {
    const key = durableServerEventKey(event);
    const index =
      key == null
        ? -1
        : deferredServerEvents.findIndex(
            (candidate) => durableServerEventKey(candidate) === key,
          );
    if (index < 0) return false;
    deferredServerEvents.splice(index, 1);
    controlEventFailures.delete(key);
    checkpointDeferredServerEvents();
    return true;
  };
  const quarantineDeferredServerEvent = (
    event,
    { reason, attempts = 0, error = "" },
  ) => {
    const key = durableServerEventKey(event);
    const index =
      key == null
        ? -1
        : deferredServerEvents.findIndex(
            (candidate) => durableServerEventKey(candidate) === key,
          );
    if (index < 0) return false;
    const [removed] = deferredServerEvents.splice(index, 1);
    controlEventFailures.delete(key);
    quarantinedServerEvents.push({
      event: structuredClone(removed),
      reason,
      attempts,
      error: String(error).slice(0, 180),
      quarantined_at: new Date().toISOString(),
    });
    if (quarantinedServerEvents.length > MAX_QUARANTINED_SERVER_EVENTS) {
      quarantinedServerEvents = quarantinedServerEvents.slice(
        -MAX_QUARANTINED_SERVER_EVENTS,
      );
    }
    checkpointDeferredServerEvents();
    return true;
  };
  const noteDeferredControlFailure = (event, error) => {
    const key = durableServerEventKey(event);
    if (key == null) {
      quarantineDeferredServerEvent(event, {
        reason: "unkeyed_control_failure",
        attempts: 1,
        error,
      });
      return { attempts: 1, quarantined: true };
    }
    const previous = controlEventFailures.get(key);
    const attempts = (previous?.attempts ?? 0) + 1;
    const message =
      error instanceof Error ? error.message : String(error);
    controlEventFailures.set(key, {
      attempts,
      last_error: message.slice(0, 180),
      last_attempt_at: new Date().toISOString(),
    });
    if (attempts >= MAX_CONTROL_EVENT_ATTEMPTS) {
      quarantineDeferredServerEvent(event, {
        reason: "control_retry_exhausted",
        attempts,
        error: message,
      });
      return { attempts, quarantined: true };
    }
    checkpointDeferredServerEvents();
    return { attempts, quarantined: false };
  };
  const quarantineStaleSessionEvents = (serverSessionId) => {
    const stale = deferredServerEvents.filter(
      (event) => !shouldReplayTransportEvent(event, serverSessionId),
    );
    for (const event of stale) {
      quarantineDeferredServerEvent(event, {
        reason: "stale_server_session",
        error: `event session ${eventServerSessionId(event)} != live session ${serverSessionId}`,
      });
    }
    return stale.length;
  };
  const restoredTransportOwner =
    typeof restoredTransport?.companion === "string"
      ? restoredTransport.companion
      : typeof restoredBrain?.companion === "string"
        ? restoredBrain.companion
        : null;
  if (
    restoredTransportOwner != null &&
    restoredTransportOwner !== config.companion
  ) {
    const wrongOwnerEvents = deferredServerEvents.filter(
      (event) =>
        !shouldReplayTransportForCompanion(
          event,
          restoredTransportOwner,
          config.companion,
        ),
    );
    for (const event of wrongOwnerEvents) {
      quarantineDeferredServerEvent(event, {
        reason: "transport_companion_mismatch",
        error: `transport owner ${restoredTransportOwner} != configured companion ${config.companion}`,
      });
    }
    if (wrongOwnerEvents.length > 0) {
      log("warn", "quarantined transport events for another companion", {
        restoredTransportOwner,
        configuredCompanion: config.companion,
        count: wrongOwnerEvents.length,
      });
    }
  }
  stateStore.scheduleBrain(brain.exportState());
  stateStore.scheduleInbox(inbox.snapshot());
  checkpointDeferredServerEvents();

  const bootstrapServerEvents = await client.pollServerEvents(
    config.eventBatchSize,
  );
  for (const event of bootstrapServerEvents) {
    deferServerEvent(event);
  }
  if (bootstrapServerEvents.length > 0) {
    // poll_server_events is destructive. Persist the untouched envelopes before
    // any live-session reconciliation or worker-visible inbox transition.
    await stateStore.flush();
  }
  const bootstrapBodyEvents = await client.pollCompanionEvents(
    config.companion,
    config.eventBatchSize,
  );
  for (const event of bootstrapBodyEvents) {
    if (event.type === "defense_started" || event.type === "death") {
      skillRunner.cancelActive(event.type);
      brain.interrupt({
        preserveTaskRecovery: true,
        preservePlayerGoal: true,
      });
    }
    inbox.push({
      event,
      decision: null,
      commandRequest: null,
    });
  }
  await stateStore.flush();

  const live = await client.inspectBodyWork(config.companion, {
    jobIds: brain.awaitingJobIds(),
  });
  inbox.synchronizeServerSession(live.serverSessionId);
  const staleStartupEvents = quarantineStaleSessionEvents(
    live.serverSessionId,
  );
  if (staleStartupEvents > 0) {
    log("warn", "quarantined events from a previous Minecraft session", {
      liveServerSessionId: live.serverSessionId,
      count: staleStartupEvents,
    });
    await stateStore.flush();
  }
  const durableTaskIds = pendingTaskIds(inbox);
  skillRunner.setServerSessionId(live.serverSessionId);
  const skillRecovery = await skillRunner.reconcileLiveState(live, {
    pendingTaskIds: durableTaskIds,
  });
  const logicalLive = {
    ...live,
    activeTaskIds: [
      ...live.activeTaskIds,
      ...(skillRecovery.active && skillRunner.snapshot()?.macro_task_id
        ? [skillRunner.snapshot().macro_task_id]
        : []),
    ],
  };
  const startupRecovery = brain.reconcileLiveState(logicalLive, {
    pendingTaskIds: pendingTaskIds(inbox),
  });
  if (startupRecovery.recoveryNeeded) {
    inbox.push({
      event: {
        id: `harness-recovery-${brain.activeGoal?.goal_id ?? "idle"}-${live.serverSessionId}`,
        type: "harness_recovery",
        companionName: config.companion,
      },
      decision: null,
      commandRequest: null,
    });
  }
  await stateStore.flush();
  const synchronizeRuntimeSession = async (
    serverSessionId,
    reason,
  ) => {
    const previousBrainSession = brain.serverSessionId;
    const restarted =
      previousBrainSession != null &&
      previousBrainSession !== serverSessionId;
    inbox.synchronizeServerSession(serverSessionId);
    const skillSession = await skillRunner.synchronizeServerSession(
      serverSessionId,
      { reason },
    );
    const brainSession = brain.synchronizeServerSession(serverSessionId, {
      reason: `runtime_session:${reason}`,
    });
    if (restarted) {
      inbox.push({
        event: {
          id: `harness-runtime-recovery-${serverSessionId}`,
          type: "harness_recovery",
          companionName: config.companion,
          serverSessionId,
          reason,
        },
        decision: null,
        commandRequest: null,
      });
      await stateStore.flush();
      log("warn", "Minecraft server session changed at runtime", {
        reason,
        invalidatedTasks: brainSession.invalidatedTaskIds.length,
        invalidatedSkill:
          skillSession.invalidatedMacroTaskId ?? null,
      });
    }
    return { restarted, brainSession, skillSession };
  };
  const refreshGoalLease = async (reason) => {
    try {
      const liveStatus =
        config.activityMode === "supervised"
          ? await client.inspectBodyWork(config.companion, {
              jobIds: brain.awaitingJobIds(),
            })
          : await client.inspectServerSession(config.companion);
      await synchronizeRuntimeSession(
        liveStatus.serverSessionId,
        `goal_lease:${reason}`,
      );
      if (config.activityMode === "supervised") {
        const active = liveStatus.activeTaskIds.length > 0;
        brain.refreshGoalLease(active);
        log("info", "supervised goal lease refreshed", {
          reason,
          active,
        });
      }
    } catch (error) {
      // Fail closed: telemetry loss must not turn into unbounded continuation.
      if (config.activityMode === "supervised") {
        brain.refreshGoalLease(false);
      }
      log("warn", "runtime body status refresh failed closed", {
        reason,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  };
  const commandGateway = new ServerCommandGateway(client, config.companion);
  const controlGateway = new ServerControlGateway(client, config.companion);
  const once = argv.includes("--once");
  let stopping = false;
  const stop = () => {
    if (stopping) return;
    stopping = true;
    brain.interrupt({
      preserveTaskRecovery: true,
      preservePlayerGoal: true,
    });
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);

  log("info", "Momo server brain started", {
    mcp: config.mcpUrl,
    companion: config.companion,
    activityMode: config.activityMode,
    classifierModel: config.classifierModel,
    agentModel: modelSelection.snapshot().model,
    agentReasoning: modelSelection.snapshot().reasoning,
    modelRevision: modelSelection.snapshot().revision,
    commandPlayers: config.commandPlayers,
    harnessMcp: harnessMcpUrl,
    restoredGoal: brain.activeGoal?.goal_id ?? null,
    restoredTasks: brain.awaitingTaskIds.size,
    startupRecoveryNeeded: startupRecovery.recoveryNeeded,
  });

  let pollerError = null;
  const poller = (async () => {
    let consecutiveErrors = 0;
    let bodyPollErrors = 0;
    try {
      do {
        try {
          const replayingDeferred = deferredServerEvents.length > 0;
          let events;
          if (replayingDeferred) {
            // A persisted envelope is historical evidence, never session
            // authority. Probe the live JVM first so an old queue cannot move
            // the runtime from a freshly confirmed session B back to A.
            const liveSession = await client.inspectServerSession(
              config.companion,
            );
            await synchronizeRuntimeSession(
              liveSession.serverSessionId,
              "transport_replay_probe",
            );
            const staleCount = quarantineStaleSessionEvents(
              liveSession.serverSessionId,
            );
            if (staleCount > 0) {
              log("warn", "quarantined stale durable transport events", {
                liveServerSessionId: liveSession.serverSessionId,
                count: staleCount,
              });
              await stateStore.flush();
            }
            events = deferredServerEvents.map((event) =>
              structuredClone(event),
            );
          } else {
            events = await client.pollServerEvents(config.eventBatchSize);
            for (const event of events) deferServerEvent(event);
            if (events.length > 0) {
              // poll_server_events is destructive. Persist the untouched batch
              // before it can affect live session state or become worker-visible.
              await stateStore.flush();
              const sessions = [
                ...new Set(
                  events
                    .map((event) => eventServerSessionId(event))
                    .filter((value) => value != null),
                ),
              ];
              const observedSessionId =
                sessions.length <= 1
                  ? (sessions[0] ?? null)
                  : (
                      await client.inspectServerSession(config.companion)
                    ).serverSessionId;
              if (observedSessionId != null) {
                await synchronizeRuntimeSession(
                  observedSessionId,
                  sessions.length <= 1
                    ? "server_event_poll"
                    : "mixed_server_event_poll_probe",
                );
                const staleCount = quarantineStaleSessionEvents(
                  observedSessionId,
                );
                if (staleCount > 0) {
                  log("warn", "quarantined mixed-session polled events", {
                    liveServerSessionId: observedSessionId,
                    count: staleCount,
                  });
                  await stateStore.flush();
                }
              }
              events = deferredServerEvents.map((event) =>
                structuredClone(event),
              );
            }
          }
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
              skillRunner.cancelActive(event.type);
              const interruptedTurn = brain.interrupt({
                preserveTaskRecovery: true,
                preservePlayerGoal: true,
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
          if (bodyEvents.length > 0) {
            // poll_companion_events is destructive as well. Do not leave body
            // telemetry only in memory while handling a slower control event.
            await stateStore.flush();
          }

          const stagedInboxItems = [];
          let deferredControlFailure = null;
          for (
            let eventIndex = 0;
            eventIndex < events.length;
            eventIndex += 1
          ) {
            const event = events[eventIndex];
            const decodedBrainConfig = decodeBrainConfigRequest(event);
            if (decodedBrainConfig.kind !== "other") {
              try {
                const result =
                  decodedBrainConfig.kind === "invalid"
                    ? await brainConfigGateway.reject(decodedBrainConfig)
                    : await brainConfigGateway.handle(
                        decodedBrainConfig.request,
                      );
                log(
                  result.success ? "info" : "warn",
                  "brain config handled",
                  {
                    eventId: event.id,
                    requestId:
                      decodedBrainConfig.request?.requestId ??
                      decodedBrainConfig.requestId,
                    action: decodedBrainConfig.request?.action ?? "invalid",
                    success: result.success,
                    applied: result.applied,
                    error: result.error,
                    ...result.current,
                  },
                );
                completeDeferredServerEvent(event);
                await stateStore.flush();
              } catch (error) {
                // The already-drained event remains in the durable transport
                // queue. BrainConfigGateway also retains an already-applied
                // ACK, so replay cannot change the selected model twice.
                const retry = noteDeferredControlFailure(event, error);
                log("warn", "brain config acknowledgement deferred", {
                  eventId: event.id,
                  requestId:
                    decodedBrainConfig.request?.requestId ??
                      decodedBrainConfig.requestId,
                  error: error instanceof Error ? error.message : String(error),
                  attempts: retry.attempts,
                  quarantined: retry.quarantined,
                });
                await stateStore.flush();
                if (!retry.quarantined) {
                  deferredControlFailure = error;
                  break;
                }
              }
              continue;
            }
            const decodedTestInstruction = decodeTestInstructionEvent(
              event,
              config.companion,
            );
            if (decodedTestInstruction.kind === "invalid") {
              log("warn", "invalid test instruction ignored", {
                eventId: event.id,
                reason: decodedTestInstruction.reason,
              });
              completeDeferredServerEvent(event);
              await stateStore.flush();
              continue;
            }
            if (decodedTestInstruction.kind === "test_instruction") {
              const testEvent = decodedTestInstruction.event;
              if (testEvent.freshThread) {
                skillRunner.cancelActive("fresh test run");
                const dispatch = await dispatchFreshTestInstruction({
                  client,
                  companion: config.companion,
                  event: testEvent,
                  inbox,
                  brain,
                  enqueue: false,
                });
                if (!dispatch.ok) {
                  // Keep the event in the durable transport queue and retry
                  // its stop fence before polling another server batch.
                  const retry = noteDeferredControlFailure(
                    event,
                    dispatch.reason ?? "fresh test stop fence failed",
                  );
                  log("warn", "fresh test instruction deferred", {
                    eventId: testEvent.id,
                    runId: testEvent.runId,
                    companion: testEvent.companionName,
                    interruptedTurn: dispatch.interruptedTurn,
                    reason: dispatch.reason,
                    attempts: retry.attempts,
                    quarantined: retry.quarantined,
                  });
                  await stateStore.flush();
                  if (!retry.quarantined) {
                    deferredControlFailure = new Error(
                      dispatch.reason ?? "fresh test stop fence failed",
                    );
                    break;
                  }
                  continue;
                }
                if (dispatch.liveBody != null) {
                  await skillRunner.reconcileRuntimeState(
                    dispatch.liveBody,
                    { reason: "fresh_test_authoritative_stop" },
                  );
                }
                log("info", "fresh test instruction staged", {
                  eventId: testEvent.id,
                  runId: testEvent.runId,
                  companion: testEvent.companionName,
                  interruptedTurn: dispatch.interruptedTurn,
                });
                stagedInboxItems.push({
                  sourceEvent: event,
                  item: dispatch.item,
                  generation: inbox.currentGeneration(),
                  cancelled: false,
                });
                continue;
              }
              stagedInboxItems.push({
                sourceEvent: event,
                item: {
                  event: testEvent,
                  decision: null,
                  commandRequest: null,
                },
                generation: inbox.currentGeneration(),
                cancelled: false,
              });
              continue;
            }
            const controlRequest = parseStopRequest(event);
            const commandRequest = parseServerCommandRequest(
              event,
              config.commandPlayers,
            );
            if (controlRequest != null) {
              if (!inbox.cancelWorkThrough(event.id, event)) {
                quarantineDeferredServerEvent(event, {
                  reason: "stale_or_invalid_stop_fence",
                  error: "operator stop did not match the live server session",
                });
                log("warn", "stale server stop request quarantined", {
                  eventId: event.id,
                  eventServerSessionId: eventServerSessionId(event),
                  liveServerSessionId: brain.serverSessionId,
                });
                await stateStore.flush();
                continue;
              }
              skillRunner.cancelActive("operator stop");
              const interruptedTurn = brain.enterHold();
              // A stop later in the same durable batch supersedes an earlier
              // test instruction that has not yet become worker-visible.
              for (const staged of stagedInboxItems) {
                if (staged.item?.event?.type === "test_instruction") {
                  staged.cancelled = true;
                }
              }
              let result;
              try {
                result = await controlGateway.handle(event, controlRequest);
              } catch (error) {
                result = {
                  ok: false,
                  reason:
                    error instanceof Error ? error.message : String(error),
                };
              }
              log(result.ok ? "info" : "warn", "server control handled", {
                eventId: event.id,
                player: event.playerName,
                control: controlRequest.type,
                interruptedTurn,
                ok: result.ok,
                reason: result.reason,
              });
              if (!result.ok) {
                const retry = noteDeferredControlFailure(
                  event,
                  result.reason ?? "server stop failed",
                );
                log("warn", "server stop request retained for retry", {
                  eventId: event.id,
                  attempts: retry.attempts,
                  quarantined: retry.quarantined,
                });
                await stateStore.flush();
                if (!retry.quarantined) {
                  deferredControlFailure = new Error(
                    result.reason ?? "server stop failed",
                  );
                  break;
                }
                continue;
              }
              if (result.liveBody != null) {
                await skillRunner.reconcileRuntimeState(
                  result.liveBody,
                  { reason: "operator_authoritative_stop" },
                );
              }
              completeDeferredServerEvent(event);
              await stateStore.flush();
              continue;
            }
            stagedInboxItems.push({
              sourceEvent: event,
              item: {
                event,
                decision: null,
                commandRequest,
              },
              generation: inbox.currentGeneration(),
              cancelled: false,
            });
          }
          for (const staged of stagedInboxItems) {
            if (!staged.cancelled) {
              inbox.push(staged.item, {
                generation: staged.generation,
              });
            }
            completeDeferredServerEvent(staged.sourceEvent);
          }
          // The transport -> inbox transition is committed as one merged
          // checkpoint after all preceding control fences in this prefix.
          await stateStore.flush();
          try {
            const skillLease = await skillRunner.reconcileRuntimeLease();
            if (
              skillLease.finalizedCancellation ||
              skillLease.leaseExpired
            ) {
              log(
                skillLease.finalizedCancellation ? "info" : "warn",
                "verified skill wait lease reconciled",
                {
                  finalizedCancellation:
                    skillLease.finalizedCancellation === true,
                  leaseExpired: skillLease.leaseExpired === true,
                  stopRequested: skillLease.stopRequested === true,
                  missingTerminal: skillLease.missingTerminal === true,
                },
              );
            }
          } catch (error) {
            log("warn", "verified skill lease probe deferred", {
              error: error instanceof Error ? error.message : String(error),
            });
          }
          if (deferredControlFailure != null) {
            throw deferredControlFailure;
          }
          if (consecutiveErrors > 0) {
            await brainConfigGateway.announce();
            log("info", "brain config state re-announced after MCP recovery", {
              previousErrors: consecutiveErrors,
              ...modelSelection.snapshot(),
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
      await stateStore.flush();
      if (pending.length === 0 && inbox.closed) break;
      pending.sort(
        (left, right) =>
          inboxPriority(right.event) - inboxPriority(left.event),
      );

      const unclassified = pending.filter(
        (item) =>
          (item.event.type === "player_chat" ||
            item.event.type === "console_chat") &&
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
        inbox.touch();
        await stateStore.flush();
      }

      for (const item of pending) {
        const { event, decision, commandRequest } = item;
        if (inbox.isCancelled(event)) {
          log("info", "stale event cancelled by control fence", {
            eventId: event.id,
            type: event.type,
            player: event.playerName,
            status: event.status,
          });
          inbox.complete(item);
          await stateStore.flush();
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
          let retryTest = false;
          try {
            await brain.handleTestInstruction(event);
          } catch (error) {
            retryTest = true;
            log("error", "test instruction handling failed", {
              eventId: event.id,
              runId: event.runId,
              error: error instanceof Error ? error.message : String(error),
            });
          } finally {
            await refreshGoalLease("test_instruction");
          }
          if (retryTest) {
            inbox.retry(item);
            await stateStore.flush();
            if (!once && !stopping) await delay(5_000);
          } else {
            inbox.complete(item);
            await stateStore.flush();
          }
          continue;
        }
        if (event.type === "harness_recovery") {
          try {
            await brain.retryBodyContext();
            await refreshGoalLease("startup_recovery");
            log("info", "persisted Harness context reconciled");
            inbox.complete(item);
          } catch (error) {
            log("warn", "persisted Harness context remains queued", {
              error: error instanceof Error ? error.message : String(error),
            });
            inbox.retry(item);
            await stateStore.flush();
            if (!once && !stopping) await delay(5_000);
            continue;
          }
          await stateStore.flush();
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
          let skillClaim;
          try {
            skillClaim = await skillRunner.handleTaskEvent(event);
          } catch (error) {
            log("error", "verified skill terminal handling deferred", {
              eventId: event.id,
              taskId: event.taskId,
              error: error instanceof Error ? error.message : String(error),
            });
            inbox.retry(item);
            await stateStore.flush();
            if (!once && !stopping) await delay(5_000);
            continue;
          }
          if (skillClaim.claimed) {
            log(
              skillClaim.state === "failed" ? "warn" : "info",
              "verified skill claimed inner task terminal",
              {
                eventId: event.id,
                taskId: event.taskId,
                state: skillClaim.state,
              },
            );
            await refreshGoalLease("skill_task_finished");
            inbox.complete(item);
            await stateStore.flush();
            continue;
          }
          let taskDispositionRecorded = false;
          try {
            await brain.handleTaskEvent(event);
            taskDispositionRecorded = true;
          } catch (error) {
            taskDispositionRecorded =
              brain.hasPendingTaskEvent(event) ||
              brain.handledInput(event) != null;
            log("error", "background task reconciliation deferred", {
              eventId: event.id,
              durable: taskDispositionRecorded,
              error: error instanceof Error ? error.message : String(error),
            });
          } finally {
            await refreshGoalLease("task_finished");
          }
          if (!taskDispositionRecorded) {
            inbox.retry(item);
            await stateStore.flush();
            if (!once && !stopping) await delay(5_000);
            continue;
          }
          inbox.complete(item);
          await stateStore.flush();
          continue;
        }
        if (
          event.type === "damage_received" ||
          event.type === "defense_started" ||
          event.type === "death"
        ) {
          if (event.type === "defense_started" || event.type === "death") {
            skillRunner.cancelActive(event.type);
          }
          const buffered = brain.noteBodyEvent(event);
          log(
            "info",
            buffered
              ? "companion body event buffered"
              : "inactive companion body event dropped",
            {
              eventId: event.id,
              companion: event.companionName,
              type: event.type,
            },
          );
          inbox.complete(item);
          await stateStore.flush();
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
          } finally {
            await refreshGoalLease("body_context");
          }
          inbox.complete(item);
          await stateStore.flush();
          continue;
        }
        if (
          event.type !== "player_chat" &&
          event.type !== "console_chat"
        ) {
          log("warn", "unknown server event ignored", {
            eventId: event.id,
            type: event.type,
          });
          inbox.complete(item);
          await stateStore.flush();
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
          inbox.complete(item);
          await stateStore.flush();
          continue;
        }
        log("info", "chat routed", {
          eventId: event.id,
          player: event.playerName,
          channel: event.type,
          route: decision.route,
          reason: decision.reason,
        });
        try {
          if (
            decision.route === "reply" &&
            decision.fastReply === true &&
            typeof decision.reply === "string" &&
            decision.reply !== ""
          ) {
            await client.sendChat(config.companion, decision.reply);
            brain.noteFastReply(event, decision.reply);
            log("info", "low-cost chat reply sent directly", {
              eventId: event.id,
              player: event.playerName,
              channel: event.type,
            });
          } else {
            await brain.handle(event, decision);
          }
        } catch (error) {
          log("error", "chat handling failed without stopping the poller", {
            eventId: event.id,
            player: event.playerName,
            error: error instanceof Error ? error.message : String(error),
          });
          // A model turn may already have durably accepted an async action
          // before only its player-visible ACK transport failed. Re-queuing
          // that source event would let recovery dispatch the same action
          // twice, so only defer inputs with no durable Brain disposition.
          if (brain.handledInput(event) == null) {
            brain.queuePlayerGoal(event, decision);
          }
          if (!once && !stopping) {
            await delay(5_000);
            try {
              await brain.retryBodyContext();
            } catch (retryError) {
              log("warn", "failed chat remains in durable goal queue", {
                eventId: event.id,
                error:
                  retryError instanceof Error
                    ? retryError.message
                    : String(retryError),
              });
            }
          }
        } finally {
          await refreshGoalLease(event.type);
        }
        inbox.complete(item);
        await stateStore.flush();
      }
    }
  } finally {
    stopping = true;
    await poller;
    brain.checkpoint("shutdown");
    stateStore.scheduleInbox(inbox.snapshot());
    await stateStore.flush();
    try {
      await trace.flush();
    } catch (error) {
      log("warn", "Harness trace did not fully flush", {
        error: error instanceof Error ? error.message : String(error),
      });
    }
    await harnessServer.close();
  }
  if (pollerError != null) throw pollerError;

  log("info", "Momo server brain stopped");
  } catch (error) {
    await harnessServer.close().catch(() => {});
    try {
      await trace.flush();
    } catch {
      // Startup/worker failure remains authoritative.
    }
    throw error;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run().catch((error) => {
    log("fatal", "Momo server brain could not start", {
      error: error instanceof Error ? error.stack : String(error),
    });
    process.exitCode = 1;
  });
}
