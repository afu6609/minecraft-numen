const OPAQUE_ACTION_ID = /^[A-Za-z][A-Za-z0-9._:-]{0,127}$/u;

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
  return payloads;
}

function completedNumenCalls(turn) {
  const calls = [];
  for (const item of Array.isArray(turn?.items) ? turn.items : []) {
    if (
      item?.type !== "mcp_tool_call" ||
      item.server !== "numen" ||
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
    !OPAQUE_ACTION_ID.test(taskId)
  ) {
    return null;
  }
  const actionId = data?.action_id ?? data?.actionId;
  return {
    kind: "accepted_task",
    tool: item.tool,
    taskId,
    actionId:
      typeof actionId === "string" && OPAQUE_ACTION_ID.test(actionId)
        ? actionId
        : null,
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

export function acceptedNumenTaskIds(turn) {
  const accepted = new Set();
  for (const { item, payload } of completedNumenCalls(turn)) {
    const receipt = acceptedReceipt(item, payload);
    if (receipt != null) accepted.add(receipt.taskId);
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
export function trustedAckForTurn(turn) {
  for (const { item, payload } of completedNumenCalls(turn)) {
    const accepted = acceptedReceipt(item, payload);
    if (accepted != null) {
      return { ...accepted, message: acceptedMessage(accepted.tool) };
    }
  }
  return null;
}
