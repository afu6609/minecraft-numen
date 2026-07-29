function nonBlank(value) {
  if (typeof value !== "string") return null;
  const clean = value.trim();
  return clean === "" ? null : clean;
}

function isTargetCompanion(event, expectedCompanion) {
  const expected = nonBlank(expectedCompanion)?.toLowerCase();
  if (expected == null) return false;
  return (
    nonBlank(event.companionName)?.toLowerCase() === expected ||
    nonBlank(event.companionUuid)?.toLowerCase() === expected
  );
}

/**
 * Decode the trusted server envelope without turning it into an agent tool.
 *
 * The producer already validates this shape, but the sidecar treats the MCP
 * boundary as untrusted so a malformed event cannot start an ambiguous run.
 */
export function decodeTestInstructionEvent(event, expectedCompanion) {
  if (event?.type !== "test_instruction") {
    return { kind: "other" };
  }
  if (!isTargetCompanion(event, expectedCompanion)) {
    return {
      kind: "invalid",
      reason: "test instruction targets a different companion",
    };
  }

  const runId = nonBlank(event.runId);
  const message = nonBlank(event.message);
  const dimension = nonBlank(event.arenaAnchor?.dimension);
  const { x, y, z } = event.arenaAnchor ?? {};
  if (runId == null) {
    return { kind: "invalid", reason: "test instruction has no runId" };
  }
  if (message == null) {
    return { kind: "invalid", reason: "test instruction is blank" };
  }
  if (
    dimension == null ||
    !Number.isInteger(x) ||
    !Number.isInteger(y) ||
    !Number.isInteger(z)
  ) {
    return {
      kind: "invalid",
      reason: "test instruction has no explicit arena anchor",
    };
  }

  return {
    kind: "test_instruction",
    event: {
      ...event,
      companionName: nonBlank(event.companionName),
      runId,
      message,
      arenaAnchor: { dimension, x, y, z },
      // Backward-compatible default: every test starts isolated unless the
      // trusted producer explicitly opts into continuation.
      freshThread: event.freshThread !== false,
    },
  };
}

const ALREADY_IDLE =
  /没有进行中的后台任务|already idle|no background task/iu;

/**
 * Hard fence between two arena runs.
 *
 * Aborting the Codex turn is not enough: an accepted Numen task keeps moving
 * the body on the server. Do not expose the fresh objective until that task is
 * stopped (or the body is authoritatively already idle).
 */
export async function dispatchFreshTestInstruction({
  client,
  companion,
  event,
  inbox,
  brain,
}) {
  // Establish the generation fence before the asynchronous MCP stop. Without
  // this ordering, the worker could begin reconciling an old queued event
  // while stopTask is in flight and submit fresh work behind the stop.
  inbox.beginFreshTestRun(event.id);
  const interruptedTurn = brain.interrupt({
    preserveTaskRecovery: false,
  });
  try {
    await client.stopTask(companion);
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    if (!ALREADY_IDLE.test(reason)) {
      return {
        ok: false,
        interruptedTurn,
        reason: reason.slice(0, 180),
      };
    }
  }

  const queued = inbox.push({
    event,
    decision: null,
    commandRequest: null,
  });
  return {
    ok: queued,
    interruptedTurn,
    reason: queued ? undefined : "event inbox is closed",
  };
}
