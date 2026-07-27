import assert from "node:assert/strict";
import test from "node:test";

import {
  parseStopRequest,
  ServerControlGateway,
} from "../src/server-control.mjs";

test("direct stop phrases are intercepted without catching unrelated chat", () => {
  const event = { type: "player_chat", message: "桃桃别砍了" };
  assert.deepEqual(parseStopRequest(event), { type: "stop" });
  assert.deepEqual(parseStopRequest({ ...event, message: "桃桃停" }), {
    type: "stop",
  });
  assert.equal(
    parseStopRequest({ ...event, message: "桃桃停止下雨" }),
    null,
  );
});

test("stop gateway interrupts the active task before acknowledging", async () => {
  const calls = [];
  const gateway = new ServerControlGateway(
    {
      async stopTask(companion) {
        calls.push(["stop", companion]);
      },
      async sendChat(companion, message) {
        calls.push(["chat", companion, message]);
      },
    },
    "momo",
  );

  assert.deepEqual(await gateway.handle(), { ok: true });
  assert.deepEqual(calls, [
    ["stop", "momo"],
    ["chat", "momo", "好，我停下了。"],
  ]);
});
