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
  assert.deepEqual(
    parseStopRequest({ type: "console_chat", message: "停下" }),
    { type: "stop" },
  );
});

test("stop gateway interrupts the active task before acknowledging", async () => {
  const calls = [];
  const gateway = new ServerControlGateway(
    {
      async stopTask(companion) {
        calls.push(["stop", companion]);
      },
      async stopNativeNavigation(companion) {
        calls.push(["stop-nav", companion]);
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
    ["stop-nav", "momo"],
    ["chat", "momo", "好，我停下了。"],
  ]);
});

test("one failed stop lane is not reported as a successful hard stop", async () => {
  const messages = [];
  const gateway = new ServerControlGateway(
    {
      async stopTask() {},
      async stopNativeNavigation() {
        throw new Error("navigation endpoint unavailable");
      },
      async sendChat(_companion, message) {
        messages.push(message);
      },
    },
    "momo",
  );

  const result = await gateway.handle();
  assert.equal(result.ok, false);
  assert.match(result.reason, /navigation endpoint unavailable/);
  assert.deepEqual(messages, ["我没能立刻停下，稍等一下。"]);
});

test("live idle inspection can authoritatively close ambiguous stop lanes", async () => {
  const gateway = new ServerControlGateway(
    {
      async stopTask() {},
      async stopNativeNavigation() {
        throw new Error("temporary transport failure");
      },
      async inspectBodyWork() {
        return { activeTaskIds: [] };
      },
      async sendChat() {},
    },
    "momo",
  );

  assert.deepEqual(await gateway.handle(), {
    ok: true,
    liveBody: { activeTaskIds: [] },
  });
});
