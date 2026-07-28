import assert from "node:assert/strict";
import test from "node:test";

import { EventInbox } from "../src/event-inbox.mjs";

test("stop fence removes stale player chat but keeps task events", async () => {
  const inbox = new EventInbox();
  inbox.push({ event: { id: 20, type: "player_chat", message: "跟着我" } });
  inbox.push({ event: { id: 21, type: "task_finished" } });

  inbox.cancelPlayerChatsThrough(22);
  const batch = await inbox.takeAll();
  assert.deepEqual(batch.map((item) => item.event.id), [21]);
  assert.equal(
    inbox.push({ event: { id: 19, type: "player_chat", message: "挖矿" } }),
    false,
  );
});

test("closing wakes an empty inbox", async () => {
  const inbox = new EventInbox();
  const waiting = inbox.takeAll();
  inbox.close();
  assert.deepEqual(await waiting, []);
});
