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
  assert.equal(
    parseServerCommandRequest(
      { ...event, message: "桃桃，现在是白天吗？" },
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
      async runCommand(companion, command) {
        calls.push(["run", companion, command]);
      },
      async sendChat(companion, message) {
        calls.push(["chat", companion, message]);
      },
    },
    "momo",
  );

  const result = await gateway.handle(
    { playerName: "Haa258" },
    { command: "/difficulty hard", authorized: true },
  );

  assert.equal(result.ok, true);
  assert.deepEqual(calls[0], ["run", "momo", "/difficulty hard"]);
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
