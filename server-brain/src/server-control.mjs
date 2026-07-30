const STOP_REQUEST =
  /^(?:桃桃|momo)\s*[,，:：]?\s*(?:停(?:下(?:来)?)?|停止|停手|先停(?:一下)?|别(?:砍|挖|跟|打|动)(?:了|啦)?|不要再?(?:砍|挖|跟|打|动)(?:了|啦)?|stop)\s*[。！!]*$/iu;
const CONSOLE_STOP_REQUEST =
  /^(?:(?:桃桃|momo)\s*[,，:：]?\s*)?(?:停(?:下(?:来)?)?|停止|停手|先停(?:一下)?|别(?:砍|挖|跟|打|动)(?:了|啦)?|不要再?(?:砍|挖|跟|打|动)(?:了|啦)?|stop)\s*[。！!]*$/iu;
const ALREADY_IDLE =
  /没有进行中的后台任务|already idle|no background task|no active native navigation task/iu;

function failureReason(result) {
  if (result.status !== "rejected") return null;
  return result.reason instanceof Error
    ? result.reason.message
    : String(result.reason);
}

export async function ensureBodyStopped(client, companion) {
  const results = await Promise.allSettled([
    client.stopTask(companion),
    client.stopNativeNavigation(companion),
  ]);
  const reasons = results
    .map((result) => failureReason(result))
    .filter((reason) => reason != null);
  const lanesSafe = results.every(
    (result) =>
      result.status === "fulfilled" ||
      ALREADY_IDLE.test(failureReason(result) ?? ""),
  );
  const allAlreadyIdle =
    reasons.length === results.length &&
    reasons.every((reason) => ALREADY_IDLE.test(reason));

  if (typeof client.inspectBodyWork === "function") {
    try {
      const live = await client.inspectBodyWork(companion);
      if (!Array.isArray(live?.activeTaskIds)) {
        throw new Error("body inspection returned no active task set");
      }
      if (live.activeTaskIds.length === 0) {
        return {
          ok: true,
          alreadyIdle: allAlreadyIdle,
          liveBody: live,
        };
      }
      return {
        ok: false,
        reason: `body still has active work: ${live.activeTaskIds.join(", ")}`.slice(
          0,
          180,
        ),
      };
    } catch (error) {
      if (!lanesSafe) {
        reasons.push(
          `idle verification failed: ${
            error instanceof Error ? error.message : String(error)
          }`,
        );
      }
    }
  }

  if (lanesSafe) return { ok: true, alreadyIdle: allAlreadyIdle };
  return {
    ok: false,
    reason: reasons.join("; ").slice(0, 180),
  };
}

export function parseStopRequest(event) {
  if (
    !["player_chat", "console_chat"].includes(event?.type) ||
    typeof event.message !== "string"
  ) {
    return null;
  }
  const pattern =
    event.type === "console_chat" ? CONSOLE_STOP_REQUEST : STOP_REQUEST;
  return pattern.test(event.message.trim())
    ? Object.freeze({ type: "stop" })
    : null;
}

export class ServerControlGateway {
  constructor(client, companion) {
    this.client = client;
    this.companion = companion;
  }

  async handle() {
    const result = await ensureBodyStopped(this.client, this.companion);
    if (result.ok) {
      try {
        await this.client.sendChat(
          this.companion,
          result.alreadyIdle ? "我现在没在忙呀。" : "好，我停下了。",
        );
      } catch {
        // The stop fence is already authoritative. A chat transport failure
        // must not replay a successfully completed destructive control.
      }
      if (result.alreadyIdle) {
        return {
          ok: true,
          reason: "already idle",
          ...(result.liveBody == null ? {} : { liveBody: result.liveBody }),
        };
      }
      return {
        ok: true,
        ...(result.liveBody == null ? {} : { liveBody: result.liveBody }),
      };
    }
    try {
      await this.client.sendChat(this.companion, "我没能立刻停下，稍等一下。");
    } catch {
      // The durable caller retains the stop request and applies backoff.
    }
    return { ok: false, reason: result.reason };
  }
}
