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

test("chat stop fence does not cancel a new Minecraft server session", async () => {
  const inbox = new EventInbox({ serverSessionId: "minecraft-session-a" });
  inbox.cancelChatsThrough(22, {
    id: 22,
    type: "console_chat",
    serverSessionId: "minecraft-session-a",
    receivedAtEpochMillis: 1_000,
  });
  assert.equal(
    inbox.push({
      event: {
        id: 20,
        type: "player_chat",
        message: "旧会话",
        serverSessionId: "minecraft-session-a",
        receivedAtEpochMillis: 900,
      },
    }),
    false,
  );
  inbox.synchronizeServerSession("minecraft-session-b");
  assert.equal(
    inbox.push({
      event: {
        id: 1,
        type: "player_chat",
        message: "新会话",
        serverSessionId: "minecraft-session-b",
        receivedAtEpochMillis: 2_000,
      },
    }),
    true,
  );
});

test("observed server session change clears a restored legacy chat fence", () => {
  const inbox = new EventInbox({
    serverSessionId: "minecraft-session-a",
    restored: {
      cancelChatThroughId: 80,
      serverSessionId: "minecraft-session-a",
    },
  });
  assert.deepEqual(
    inbox.synchronizeServerSession("minecraft-session-b"),
    { changed: true, clearedChatFence: true },
  );
  assert.equal(
    inbox.push({
      event: {
        id: 1,
        type: "console_chat",
        message: "新 JVM 的第一条",
        serverSessionId: "minecraft-session-b",
      },
    }),
    true,
  );
});

test("a stale control boundary cannot move the authoritative session backward", () => {
  const inbox = new EventInbox({ serverSessionId: "minecraft-session-b" });

  assert.equal(
    inbox.cancelWorkThrough(5, {
      id: 5,
      type: "console_chat",
      serverSessionId: "minecraft-session-a",
    }),
    false,
  );
  assert.equal(inbox.snapshot().serverSessionId, "minecraft-session-b");
});

test("operator stop cancels an already queued test instruction", async () => {
  const inbox = new EventInbox({ serverSessionId: "minecraft-session-a" });
  assert.equal(
    inbox.push({
      event: {
        id: 6,
        type: "test_instruction",
        runId: "queued-test",
        serverSessionId: "minecraft-session-a",
      },
    }),
    true,
  );
  assert.equal(
    inbox.cancelWorkThrough(7, {
      id: 7,
      type: "console_chat",
      serverSessionId: "minecraft-session-a",
    }),
    true,
  );
  inbox.close();
  assert.deepEqual(await inbox.takeAll(), []);
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

test("claimed events replay after restart until explicitly completed", async () => {
  const inbox = new EventInbox();
  inbox.push({
    event: { id: 101, type: "player_chat", message: "桃桃过来" },
    decision: null,
  });
  const [claimed] = await inbox.takeAll();
  const restored = new EventInbox({ restored: inbox.snapshot() });

  const [replayed] = await restored.takeAll();
  assert.equal(replayed.event.id, 101);
  assert.equal(restored.complete(replayed), true);
  assert.equal(restored.snapshot().inFlight.length, 0);
  assert.equal(inbox.complete(claimed), true);
});

test("retry returns an in-flight event to the durable queue", async () => {
  const inbox = new EventInbox();
  inbox.push({ event: { id: 102, type: "console_chat" } });
  const [claimed] = await inbox.takeAll();
  assert.equal(inbox.retry(claimed), true);
  const [retried] = await inbox.takeAll();
  assert.equal(retried.event.id, 102);
});

test("completed source events stay deduplicated across inbox restart", async () => {
  const inbox = new EventInbox();
  const original = {
    event: {
      id: 103,
      type: "task_finished",
      taskId: "task-reused-by-producer",
      status: "done",
    },
  };
  assert.equal(inbox.push(original), true);
  const [claimed] = await inbox.takeAll();
  assert.equal(inbox.complete(claimed), true);
  assert.equal(inbox.push(structuredClone(original)), false);

  const restored = new EventInbox({ restored: inbox.snapshot() });
  assert.equal(restored.push(structuredClone(original)), false);
  assert.equal(
    restored.push({
      event: {
        ...original.event,
        id: 104,
      },
    }),
    true,
  );
});

test("producer sequence reuse after a Minecraft restart is not mis-deduplicated", async () => {
  const inbox = new EventInbox();
  const oldSessionEvent = {
    event: {
      id: 1,
      type: "player_chat",
      playerName: "Alex",
      message: "旧会话消息",
      receivedAtEpochMillis: 1_000,
    },
  };
  assert.equal(inbox.push(oldSessionEvent), true);
  const [claimed] = await inbox.takeAll();
  assert.equal(inbox.complete(claimed), true);

  assert.equal(inbox.push(structuredClone(oldSessionEvent)), false);
  assert.equal(
    inbox.push({
      event: {
        ...oldSessionEvent.event,
        message: "新会话消息",
        receivedAtEpochMillis: 2_000,
      },
    }),
    true,
  );
});

test("server session id scopes otherwise colliding producer event ids", async () => {
  const inbox = new EventInbox();
  const first = {
    event: {
      id: 1,
      type: "task_finished",
      taskId: "colliding-task",
      status: "done",
      serverSessionId: "minecraft-session-a",
    },
  };
  assert.equal(inbox.push(first), true);
  const [claimed] = await inbox.takeAll();
  assert.equal(inbox.complete(claimed), true);
  assert.equal(inbox.push(structuredClone(first)), false);
  assert.equal(
    inbox.push({
      event: {
        ...first.event,
        serverSessionId: "minecraft-session-b",
      },
    }),
    true,
  );
});

test("session synchronization drops restored events from a dead JVM", async () => {
  const inbox = new EventInbox({ serverSessionId: "minecraft-session-a" });
  const oldEvent = {
    id: 1,
    type: "player_chat",
    message: "旧会话消息",
    serverSessionId: "minecraft-session-a",
  };
  assert.equal(inbox.push({ event: oldEvent }), true);

  inbox.synchronizeServerSession("minecraft-session-b");

  assert.equal(inbox.isCancelled(oldEvent), true);
  assert.equal(
    inbox.push({
      event: {
        ...oldEvent,
        message: "迟到的旧会话消息",
      },
    }),
    false,
  );
  inbox.close();
  assert.deepEqual(await inbox.takeAll(), []);
});

test("a staged pre-fence generation remains stale when committed later", async () => {
  const inbox = new EventInbox({ serverSessionId: "minecraft-session-a" });
  const oldGeneration = inbox.currentGeneration();
  inbox.beginFreshTestRun(8, {
    id: 8,
    type: "test_instruction",
    serverSessionId: "minecraft-session-a",
  });

  assert.equal(
    inbox.push(
      {
        event: {
          id: 7,
          type: "task_finished",
          taskId: "old-task",
          serverSessionId: "minecraft-session-a",
        },
      },
      { generation: oldGeneration },
    ),
    false,
  );
});

test("replaying the same fresh-test fence does not advance generation twice", () => {
  const inbox = new EventInbox({ serverSessionId: "minecraft-session-a" });
  const boundary = {
    id: 9,
    type: "test_instruction",
    runId: "fresh-9",
    serverSessionId: "minecraft-session-a",
  };
  assert.equal(inbox.beginFreshTestRun(9, boundary), true);
  const generation = inbox.currentGeneration();
  assert.equal(inbox.beginFreshTestRun(9, structuredClone(boundary)), true);
  assert.equal(inbox.currentGeneration(), generation);
});

test("server restart invalidates no-session body telemetry by generation", async () => {
  const inbox = new EventInbox({ serverSessionId: "minecraft-session-a" });
  const oldBodyEvent = {
    id: "body-old-session",
    type: "defense_started",
  };
  assert.equal(inbox.push({ event: oldBodyEvent }), true);

  inbox.synchronizeServerSession("minecraft-session-b");

  assert.equal(inbox.isCancelled(oldBodyEvent), true);
  inbox.close();
  assert.deepEqual(await inbox.takeAll(), []);
});

test("weak player identity never collapses distinct id-less chat messages", async () => {
  const inbox = new EventInbox();
  assert.equal(
    inbox.push({
      event: {
        type: "player_chat",
        playerUuid: "same-player",
        message: "第一条",
      },
    }),
    true,
  );
  assert.equal(
    inbox.push({
      event: {
        type: "player_chat",
        playerUuid: "same-player",
        message: "第二条",
      },
    }),
    true,
  );

  assert.deepEqual(
    (await inbox.takeAll()).map((item) => item.event.message),
    ["第一条", "第二条"],
  );
});
