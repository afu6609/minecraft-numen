import assert from "node:assert/strict";
import test from "node:test";

import {
  parseServerCommandRequest,
  ServerCommandGateway,
} from "../src/server-command.mjs";

test("explicit and unambiguous natural command wording is intercepted", () => {
  const event = {
    type: "player_chat",
    playerName: "Haa258",
    message: "桃桃执行指令 /time set day",
  };
  assert.deepEqual(parseServerCommandRequest(event, ["Haa258"]), {
    command: "/time set day",
    authorized: true,
  });
  assert.deepEqual(
    parseServerCommandRequest(
      { ...event, message: "桃桃，帮我把时间设为白天" },
      ["Haa258"],
    ),
    {
      command: "/time set day",
      authorized: true,
      reply: "好，时间调到白天了。",
    },
  );
  assert.deepEqual(
    parseServerCommandRequest(
      { ...event, message: "桃桃顺便把时间设为白天" },
      ["Haa258"],
    ),
    {
      command: "/time set day",
      authorized: true,
      reply: "好，时间调到白天了。",
    },
  );
  assert.deepEqual(
    parseServerCommandRequest(
      { ...event, message: "桃桃，请你把时间设为白天" },
      ["Haa258"],
    ),
    {
      command: "/time set day",
      authorized: true,
      reply: "好，时间调到白天了。",
    },
  );
  assert.deepEqual(
    parseServerCommandRequest(
      { ...event, message: "桃桃，麻烦你顺便帮我把天气调成晴天" },
      ["Haa258"],
    ),
    {
      command: "/weather clear",
      authorized: true,
      reply: "好，天气调成晴天了。",
    },
  );
  assert.deepEqual(
    parseServerCommandRequest(
      { ...event, message: "桃桃先把难度改成和平" },
      ["Haa258"],
    ),
    {
      command: "/difficulty peaceful",
      authorized: true,
      reply: "好，难度调成和平了。",
    },
  );
  assert.equal(
    parseServerCommandRequest(
      { ...event, message: "桃桃，现在是白天吗？" },
      ["Haa258"],
    ),
    null,
  );
  for (const message of [
    "桃桃顺便问一下现在是不是白天",
    "桃桃先别把时间设为白天",
    "桃桃顺便把时间设为白天然后把我切创造",
  ]) {
    assert.equal(
      parseServerCommandRequest({ ...event, message }, ["Haa258"]),
      null,
      message,
    );
  }
  assert.equal(
    parseServerCommandRequest(
      { ...event, type: "console_chat", message: "桃桃顺便把时间设为白天" },
      ["Haa258"],
    ),
    null,
  );
});

test("speaker authorization is case-insensitive but never inferred from chat", () => {
  const request = parseServerCommandRequest(
    {
      type: "player_chat",
      playerName: "Steve",
      message: "桃桃：执行指令 /weather clear",
    },
    ["haa258"],
  );
  assert.deepEqual(request, {
    command: "/weather clear",
    authorized: false,
  });
});

test("gateway calls restricted MCP tool and reports through ordinary chat", async () => {
  const calls = [];
  const gateway = new ServerCommandGateway(
    {
      async runCommand(companion, command, requestId) {
        calls.push(["run", companion, command, requestId]);
      },
      async sendChat(companion, message) {
        calls.push(["chat", companion, message]);
      },
    },
    "momo",
  );

  const result = await gateway.handle(
    {
      id: 42,
      serverSessionId: "server-session-a",
      playerName: "Haa258",
    },
    { command: "/difficulty hard", authorized: true },
  );

  assert.equal(result.ok, true);
  assert.deepEqual(calls[0], [
    "run",
    "momo",
    "/difficulty hard",
    "server-event:server-session-a:42",
  ]);
  assert.match(calls[1][2], /已执行/);
});

test("unauthorized players never reach the command tool", async () => {
  let ran = false;
  const chats = [];
  const gateway = new ServerCommandGateway(
    {
      async runCommand() {
        ran = true;
      },
      async sendChat(_companion, message) {
        chats.push(message);
      },
    },
    "momo",
  );

  const result = await gateway.handle(
    { playerName: "Steve" },
    { command: "/time set day", authorized: false },
  );

  assert.equal(result.ok, false);
  assert.equal(ran, false);
  assert.match(chats[0], /只接受管理员/);
});

test("a lost command response retries with the same idempotency key", async () => {
  const requestIds = [];
  let attempts = 0;
  const gateway = new ServerCommandGateway(
    {
      async runCommand(_companion, _command, requestId) {
        requestIds.push(requestId);
        attempts += 1;
        if (attempts === 1) throw new Error("response lost");
      },
      async sendChat() {},
    },
    "momo",
  );

  const result = await gateway.handle(
    { id: "77", server_session_id: "server-session-b" },
    { command: "/weather clear", authorized: true },
  );

  assert.equal(result.ok, true);
  assert.equal(attempts, 2);
  assert.deepEqual(requestIds, [
    "server-event:server-session-b:77",
    "server-event:server-session-b:77",
  ]);
});

test("chat notification failure never replays a successful command", async () => {
  let runs = 0;
  const gateway = new ServerCommandGateway(
    {
      async runCommand() {
        runs += 1;
      },
      async sendChat() {
        throw new Error("chat unavailable");
      },
    },
    "momo",
  );

  const result = await gateway.handle(
    { id: 88, serverSessionId: "server-session-c" },
    { command: "/time set day", authorized: true },
  );

  assert.equal(result.ok, true);
  assert.equal(result.executed, true);
  assert.equal(result.notified, false);
  assert.equal(runs, 1);
  assert.match(result.reason, /notification failed/);
});

test("authorized commands fail closed without a stable server event id", async () => {
  let ran = false;
  const gateway = new ServerCommandGateway(
    {
      async runCommand() {
        ran = true;
      },
      async sendChat() {},
    },
    "momo",
  );

  const result = await gateway.handle(
    { playerName: "Haa258" },
    { command: "/time set day", authorized: true },
  );

  assert.equal(result.ok, false);
  assert.equal(ran, false);
  assert.match(result.reason, /稳定的会话事件编号/);
});
