const STOP_REQUEST =
  /^(?:桃桃|momo)\s*[,，:：]?\s*(?:停(?:下(?:来)?)?|停止|停手|先停(?:一下)?|别(?:砍|挖|跟|打|动)(?:了|啦)?|不要再?(?:砍|挖|跟|打|动)(?:了|啦)?|stop)\s*[。！!]*$/iu;

export function parseStopRequest(event) {
  if (event?.type !== "player_chat" || typeof event.message !== "string") {
    return null;
  }
  return STOP_REQUEST.test(event.message.trim())
    ? Object.freeze({ type: "stop" })
    : null;
}

export class ServerControlGateway {
  constructor(client, companion) {
    this.client = client;
    this.companion = companion;
  }

  async handle() {
    try {
      await this.client.stopTask(this.companion);
      await this.client.sendChat(this.companion, "好，我停下了。");
      return { ok: true };
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      if (/没有进行中的后台任务|already idle|no background task/iu.test(reason)) {
        await this.client.sendChat(this.companion, "我现在没在忙呀。");
        return { ok: true, reason: "already idle" };
      }
      await this.client.sendChat(this.companion, "我没能立刻停下，稍等一下。");
      return { ok: false, reason: reason.slice(0, 180) };
    }
  }
}
