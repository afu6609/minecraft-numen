const STOP_REQUEST =
  /^(?:桃桃|momo)\s*[,，:：]?\s*(?:停(?:下(?:来)?)?|停止|停手|先停(?:一下)?|别(?:砍|挖|跟|打|动)(?:了|啦)?|不要再?(?:砍|挖|跟|打|动)(?:了|啦)?|stop)\s*[。！!]*$/iu;
const CONSOLE_STOP_REQUEST =
  /^(?:(?:桃桃|momo)\s*[,，:：]?\s*)?(?:停(?:下(?:来)?)?|停止|停手|先停(?:一下)?|别(?:砍|挖|跟|打|动)(?:了|啦)?|不要再?(?:砍|挖|跟|打|动)(?:了|啦)?|stop)\s*[。！!]*$/iu;

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
    const results = await Promise.allSettled([
      this.client.stopTask(this.companion),
      this.client.stopNativeNavigation(this.companion),
    ]);
    const stopped = results.some((result) => result.status === "fulfilled");
    if (stopped) {
      await this.client.sendChat(this.companion, "好，我停下了。");
      return { ok: true };
    }
    const reasons = results
      .filter((result) => result.status === "rejected")
      .map((result) =>
        result.reason instanceof Error
          ? result.reason.message
          : String(result.reason),
      );
    if (
      reasons.length > 0 &&
      reasons.every((reason) =>
        /没有进行中的后台任务|already idle|no background task|no active native navigation task/iu.test(
          reason,
        )
      )
    ) {
      await this.client.sendChat(this.companion, "我现在没在忙呀。");
      return { ok: true, reason: "already idle" };
    }
    await this.client.sendChat(this.companion, "我没能立刻停下，稍等一下。");
    return { ok: false, reason: reasons.join("; ").slice(0, 180) };
  }
}
