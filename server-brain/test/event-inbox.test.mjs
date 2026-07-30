import assert from "node:assert/strict";
import test from "node:test";

import { EventInbox } from "../src/event-inbox.mjs";

test("stop fence removes stale player and console chat but keeps task events", async () => {
  const inbox = new EventInbox();
  inbox.push({ event: { id: 19, type: "console_chat", message: "继续挖" } });
  inbox.push({ event: { id: 20, type: "player_chat", message: "跟着我" } });
  inbox.push({ event: { id: 21, type: "task_finished" } });

  inbox.cancelChatsThrough(22);
  const batch = await inbox.takeAll();
  assert.deepEqual(batch.map((item) => item.event.id), [21]);
  assert.equal(
    inbox.push({ event: { id: 19, type: "player_chat", message: "挖矿" } }),
    false,
  );
});

test("fresh test run discards prior chat, task, body, and test events", async () => {
  const inbox = new EventInbox();
  const stale = [
    { id: 30, type: "player_chat", message: "旧聊天" },
    { id: 31, type: "task_finished", status: "done" },
    { id: 900, type: "damage_received" },
    { id: 32, type: "test_instruction", runId: "old-run" },
  ];
  for (const event of stale) inbox.push({ event });

  // Simulate a worker that already took one old batch out of the queue. The
  // generation fence must still make it recognizably stale.
  const taken = await inbox.takeAll();
  for (const event of stale) inbox.push({ event });

  inbox.beginFreshTestRun(40);
  const freshTest = {
    id: 40,
    type: "test_instruction",
    runId: "new-run",
  };
  const freshBodyEvent = { id: 1, type: "damage_received" };
  inbox.push({ event: freshTest });
  inbox.push({ event: freshBodyEvent });

  assert.ok(taken.every((item) => inbox.isCancelled(item.event)));
  const batch = await inbox.takeAll();
  assert.deepEqual(
    batch.map((item) => item.event),
    [freshTest, freshBodyEvent],
  );
});

test("body telemetry queued before a same-cycle fresh test remains stale", async () => {
  const inbox = new EventInbox();
  const oldBodyEvent = {
    id: "body-before-fresh",
    type: "defense_started",
  };

  // index.mjs intentionally queues the already-polled body batch before
  // applying a fresh test boundary from the server-event batch.
  inbox.push({ event: oldBodyEvent });
  inbox.beginFreshTestRun(41);
  const freshTest = {
    id: 41,
    type: "test_instruction",
    runId: "fresh-run",
  };
  inbox.push({ event: freshTest });

  assert.equal(inbox.isCancelled(oldBodyEvent), true);
  assert.deepEqual(
    (await inbox.takeAll()).map((item) => item.event),
    [freshTest],
  );
});

test("stopped task events reach the brain worker for task-id cleanup", async () => {
  const inbox = new EventInbox();
  assert.equal(
    inbox.push({
      event: {
        id: 50,
        type: "task_finished",
        taskId: "old-task",
        status: "stopped",
      },
    }),
    true,
  );
  assert.equal((await inbox.takeAll())[0].event.status, "stopped");
});

test("closing wakes an empty inbox", async () => {
  const inbox = new EventInbox();
  const waiting = inbox.takeAll();
  inbox.close();
  assert.deepEqual(await waiting, []);
});
