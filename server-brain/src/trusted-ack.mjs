const OPAQUE_ACTION_ID = /^[A-Za-z][A-Za-z0-9._:-]{0,127}$/u;
const OPAQUE_SESSION_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const ASYNC_RECEIPT_TOOLS = new Set([
  "follow_player",
  "melee_attack",
  "ranged_attack",
  "build",
  "break_block",
  "mine",
  "collect_items",
  "craft",
  "structure_execute",
  "embodied_move_to",
  "embodied_follow_owner",
  "embodied_execute_plan",
  "run_skill",
]);

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value != null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, item]) => [key, canonical(item)]),
    );
  }
  return value;
}

function payloadsFromResult(result) {
  if (result == null) return [];
  const payloads = [];
  if (typeof result === "string") {
    try {
      const parsed = JSON.parse(result);
      if (parsed != null && typeof parsed === "object") payloads.push(parsed);
    } catch {
      // Plain text cannot authorize an acknowledgement about live-world work.
    }
    return payloads;
  }
  if (typeof result !== "object") return payloads;
  if (typeof result.success === "boolean") payloads.push(result);
  for (const structured of [
    result.structured_content,
    result.structuredContent,
  ]) {
    if (structured != null && typeof structured === "object") {
      payloads.push(structured);
    }
  }
  for (const block of Array.isArray(result.content) ? result.content : []) {
    if (block?.type !== "text" || typeof block.text !== "string") continue;
    try {
      const parsed = JSON.parse(block.text);
      if (parsed != null && typeof parsed === "object") payloads.push(parsed);
    } catch {
      // Older Numen responses may contain ordinary prose. Only strict JSON
      // envelopes are trusted as action receipts.
    }
  }
  if (payloads.length <= 1) return payloads;
  const fingerprints = new Set(
    payloads.map((payload) => JSON.stringify(canonical(payload))),
  );
  return fingerprints.size === 1 ? [payloads[0]] : [];
}

function completedGameplayCalls(turn) {
  const calls = [];
  for (const item of Array.isArray(turn?.items) ? turn.items : []) {
    if (
      item?.type !== "mcp_tool_call" ||
      !(
        item.server === "numen" ||
        (item.server === "momo_harness" && item.tool === "run_skill")
      ) ||
      item.status !== "completed" ||
      item.tool === "send_chat" ||
      item.result?.isError === true
    ) {
      continue;
    }
    for (const payload of payloadsFromResult(item.result)) {
      calls.push({ item, payload });
    }
  }
  return calls;
}

function acceptedReceipt(item, payload) {
  const data = payload?.data;
  const taskId = data?.task_id ?? data?.taskId;
  if (
    payload?.success !== true ||
    data?.async !== true ||
    typeof taskId !== "string" ||
    !OPAQUE_ACTION_ID.test(taskId) ||
    !ASYNC_RECEIPT_TOOLS.has(item.tool)
  ) {
    return null;
  }
  const actionId = data?.action_id ?? data?.actionId;
  const jobId = data?.job_id ?? data?.jobId ?? data?.job?.job_id;
  const serverSessionId =
    data?.server_session_id ??
    data?.serverSessionId;
  if (
    (actionId != null &&
      (typeof actionId !== "string" || !OPAQUE_ACTION_ID.test(actionId))) ||
    (jobId != null &&
      (typeof jobId !== "string" || !OPAQUE_ACTION_ID.test(jobId))) ||
    typeof serverSessionId !== "string" ||
    !OPAQUE_SESSION_ID.test(serverSessionId)
  ) {
    return null;
  }
  return {
    kind: "accepted_task",
    server: item.server,
    tool: item.tool,
    taskId,
    actionId:
      typeof actionId === "string" && OPAQUE_ACTION_ID.test(actionId)
        ? actionId
        : null,
    jobId:
      typeof jobId === "string" && OPAQUE_ACTION_ID.test(jobId)
        ? jobId
        : null,
    serverSessionId,
  };
}

function acceptedMessage(tool) {
  if (/follow/iu.test(tool)) return "好，我跟上。";
  if (/(?:goto|move|nav)/iu.test(tool)) return "好，我现在过去。";
  if (/(?:mine|break|collect|harvest)/iu.test(tool)) {
    return "好，我开始收集。";
  }
  if (/(?:build|structure_execute|embodied_execute_plan)/iu.test(tool)) {
    return "好，我开始动手了。";
  }
  if (/(?:craft|smelt|cook)/iu.test(tool)) return "好，我开始制作。";
  if (/(?:attack|combat)/iu.test(tool)) return "好，我来处理。";
  if (/(?:scan|survey|observe|inspect)/iu.test(tool)) {
    return "好，我先看清楚周围。";
  }
  return "好，我开始处理了。";
}

export function acceptedNumenTaskReceipts(turn) {
  const accepted = new Map();
  for (const { item, payload } of completedGameplayCalls(turn)) {
    const receipt = acceptedReceipt(item, payload);
    if (receipt != null) accepted.set(receipt.taskId, receipt);
  }
  return [...accepted.values()];
}

/**
 * A successful task_status response is the live JVM authority for a completed
 * turn. Async receipts prove that an action was accepted, but a delayed receipt
 * from a JVM that died during the turn must never move the Harness back to that
 * expired session.
 */
export function authoritativeTaskStatusSession(turn) {
  let serverSessionId = null;
  for (const { item, payload } of completedGameplayCalls(turn)) {
    if (
      item.server !== "numen" ||
      item.tool !== "task_status" ||
      payload?.success !== true
    ) {
      continue;
    }
    const candidate =
      payload?.data?.server_session_id ??
      payload?.data?.serverSessionId;
    if (
      typeof candidate === "string" &&
      OPAQUE_SESSION_ID.test(candidate)
    ) {
      serverSessionId = candidate;
    }
  }
  return serverSessionId;
}

export function acceptedNumenTaskIds(turn) {
  const accepted = new Set();
  for (const receipt of acceptedNumenTaskReceipts(turn)) {
    accepted.add(receipt.taskId);
  }
  return accepted;
}

/**
 * Build a deliberately conservative player-visible acknowledgement. Merely
 * completing an MCP call or returning success=true is insufficient: the
 * server must issue an explicit async task receipt. Synchronous `verified`
 * flags are deliberately insufficient until the wire protocol has a typed
 * postcondition receipt whose meaning cannot be confused with verified
 * perception or feasibility data.
 */
export function trustedAckForTurn(turn, { serverSessionId = null } = {}) {
  for (const { item, payload } of completedGameplayCalls(turn)) {
    const accepted = acceptedReceipt(item, payload);
    if (
      accepted != null &&
      (
        serverSessionId == null ||
        accepted.serverSessionId === serverSessionId
      )
    ) {
      return {
        kind: accepted.kind,
        tool: accepted.tool,
        taskId: accepted.taskId,
        actionId: accepted.actionId,
        message: acceptedMessage(accepted.tool),
      };
    }
  }
  return null;
}
