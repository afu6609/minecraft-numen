const COMMAND_REQUEST =
  /^(?:桃桃|momo)\s*[,，:：]?\s*执行指令(?:\s*[:：]\s*|\s+)(\/.+)$/iu;
const ADDRESSED = /^(?:桃桃|momo)\s*[,，:：]?\s*(.+?)\s*[。！!？?]*$/iu;

const TIME_VALUES = new Map([
  ["白天", "day"],
  ["天亮", "day"],
  ["夜晚", "night"],
  ["晚上", "night"],
  ["正午", "noon"],
  ["中午", "noon"],
  ["午夜", "midnight"],
]);
const WEATHER_VALUES = new Map([
  ["晴天", "clear"],
  ["晴朗", "clear"],
  ["下雨", "rain"],
  ["雨天", "rain"],
  ["雷雨", "thunder"],
  ["雷暴", "thunder"],
]);
const DIFFICULTY_VALUES = new Map([
  ["和平", "peaceful"],
  ["简单", "easy"],
  ["普通", "normal"],
  ["困难", "hard"],
]);
const GAME_MODE_VALUES = new Map([
  ["生存", "survival"],
  ["创造", "creative"],
  ["冒险", "adventure"],
  ["旁观", "spectator"],
]);

function normalizedNames(names) {
  return new Set(names.map((name) => name.trim().toLocaleLowerCase()).filter(Boolean));
}

function naturalCommand(message, playerName) {
  const addressed = message.trim().match(ADDRESSED);
  if (addressed == null) return null;
  const body = addressed[1].replace(/\s+/gu, "");

  let match = body.match(
    /^(?:帮我)?(?:把)?(?:时间|天色)(?:设(?:置)?|调(?:整)?|改|切换?)(?:成|为|到)?(白天|天亮|夜晚|晚上|正午|中午|午夜)$/u,
  );
  if (match != null) {
    const value = TIME_VALUES.get(match[1]);
    return {
      command: `/time set ${value}`,
      reply: `好，时间调到${match[1]}了。`,
    };
  }

  match = body.match(
    /^(?:帮我)?(?:把)?天气(?:设(?:置)?|调(?:整)?|改|切换?)(?:成|为|到)?(晴天|晴朗|下雨|雨天|雷雨|雷暴)$/u,
  );
  if (match != null) {
    const value = WEATHER_VALUES.get(match[1]);
    return {
      command: `/weather ${value}`,
      reply: `好，天气调成${match[1]}了。`,
    };
  }
  if (/^(?:别下雨了?|让雨停(?:一下)?|雨停(?:一下)?)$/u.test(body)) {
    return { command: "/weather clear", reply: "好，雨停了。" };
  }

  match = body.match(
    /^(?:帮我)?(?:把)?难度(?:设(?:置)?|调(?:整)?|改|切换?)(?:成|为|到)?(和平|简单|普通|困难)(?:模式)?$/u,
  );
  if (match != null) {
    const value = DIFFICULTY_VALUES.get(match[1]);
    return {
      command: `/difficulty ${value}`,
      reply: `好，难度调成${match[1]}了。`,
    };
  }

  match = body.match(
    /^(?:帮我)?(?:让)?(?:你|自己)?(?:切换?|改)(?:成|为|到)?(生存|创造|冒险|旁观)(?:模式)?$/u,
  );
  if (match != null) {
    const value = GAME_MODE_VALUES.get(match[1]);
    return {
      command: `/gamemode ${value}`,
      reply: `好，我切到${match[1]}模式了。`,
    };
  }

  if (
    /^(?:过来|来我这(?:里|儿)|到我这(?:里|儿)|传送(?:到)?我这(?:里|儿)|传送到我身边)$/u.test(
      body,
    )
  ) {
    return {
      command: `/tp ${playerName}`,
      reply: "好，我过来了。",
    };
  }
  return null;
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
  const natural =
    match == null ? naturalCommand(event.message, event.playerName.trim()) : null;
  if (match == null && natural == null) return null;
  return Object.freeze({
    command: match == null ? natural.command : match[1].trim(),
    authorized: normalizedNames(authorizedPlayers).has(
      event.playerName.trim().toLocaleLowerCase(),
    ),
    ...(natural?.reply ? { reply: natural.reply } : {}),
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

function stableRequestId(event) {
  const serverSessionId =
    event?.serverSessionId ?? event?.server_session_id ?? null;
  const eventId = event?.id ?? null;
  if (
    typeof serverSessionId !== "string" ||
    serverSessionId.trim() === "" ||
    (typeof eventId !== "string" && typeof eventId !== "number")
  ) {
    throw new Error("服务器指令缺少稳定的会话事件编号，已拒绝执行");
  }
  return `server-event:${serverSessionId.trim()}:${String(eventId)}`;
}

async function trySendChat(client, companion, message) {
  try {
    await client.sendChat(companion, message);
    return true;
  } catch {
    return false;
  }
}

export class ServerCommandGateway {
  constructor(client, companion) {
    this.client = client;
    this.companion = companion;
  }

  async handle(event, request) {
    if (!request.authorized) {
      const notified = await trySendChat(
        this.client,
        this.companion,
        "这类服务器指令只接受管理员 Haa258 的明确请求。",
      );
      return { ok: false, reason: "unauthorized player", notified };
    }

    let requestId;
    try {
      requestId = stableRequestId(event);
    } catch (error) {
      const reason = shortFailure(error);
      const notified = await trySendChat(
        this.client,
        this.companion,
        `这条指令没执行：${reason}`,
      );
      return { ok: false, reason, notified };
    }

    let commandError = null;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        await this.client.runCommand(
          this.companion,
          request.command,
          requestId,
        );
        commandError = null;
        break;
      } catch (error) {
        commandError = error;
      }
    }
    if (commandError != null) {
      const reason = shortFailure(commandError);
      const uncertain =
        /outcome is unknown|refusing to replay|执行结果不确定/iu.test(reason);
      const notified = await trySendChat(
        this.client,
        this.companion,
        uncertain
          ? `这条指令的执行结果不确定，我没有重复执行：${reason}`
          : `这条指令没执行：${reason}`,
      );
      return { ok: false, reason, notified, requestId };
    }

    const notified = await trySendChat(
      this.client,
      this.companion,
      request.reply ?? `好，已执行 ${request.command.slice(0, 180)}。`,
    );
    return {
      ok: true,
      executed: true,
      notified,
      requestId,
      ...(!notified
        ? { reason: "command executed; chat notification failed" }
        : {}),
    };
  }
}
