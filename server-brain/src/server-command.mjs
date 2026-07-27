const COMMAND_REQUEST =
  /^(?:桃桃|momo)\s*[,，:：]?\s*执行指令(?:\s*[:：]\s*|\s+)(\/.+)$/iu;

function normalizedNames(names) {
  return new Set(names.map((name) => name.trim().toLocaleLowerCase()).filter(Boolean));
}

export function parseServerCommandRequest(event, authorizedPlayers) {
  if (
    event?.type !== "player_chat" ||
    typeof event.playerName !== "string" ||
    typeof event.message !== "string"
  ) {
    return null;
  }
  const match = event.message.trim().match(COMMAND_REQUEST);
  if (match == null) return null;
  return Object.freeze({
    command: match[1].trim(),
    authorized: normalizedNames(authorizedPlayers).has(
      event.playerName.trim().toLocaleLowerCase(),
    ),
  });
}

function shortFailure(error) {
  const raw = error instanceof Error ? error.message : String(error);
  const clean = raw
    .replace(/^call failed:\s*/iu, "")
    .replace(/^java\.util\.concurrent\.ExecutionException:\s*/iu, "")
    .replace(/^java\.(?:lang|util)\.[A-Za-z]+Exception:\s*/iu, "")
    .trim();
  return clean.slice(0, 180) || "服务器拒绝了这条指令";
}

export class ServerCommandGateway {
  constructor(client, companion) {
    this.client = client;
    this.companion = companion;
  }

  async handle(event, request) {
    if (!request.authorized) {
      await this.client.sendChat(
        this.companion,
        "这类服务器指令只接受管理员 Haa258 的明确请求。",
      );
      return { ok: false, reason: "unauthorized player" };
    }
    try {
      await this.client.runCommand(this.companion, request.command);
      await this.client.sendChat(
        this.companion,
        `好，已执行 ${request.command.slice(0, 180)}。`,
      );
      return { ok: true };
    } catch (error) {
      const reason = shortFailure(error);
      await this.client.sendChat(
        this.companion,
        `这条指令没执行：${reason}`,
      );
      return { ok: false, reason };
    }
  }
}
