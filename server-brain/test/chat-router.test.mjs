import assert from "node:assert/strict";
import test from "node:test";

import {
  ChatRouter,
  deterministicDecision,
  normalizeDecisions,
} from "../src/chat-router.mjs";

const EVENTS = [
  { id: 7, type: "player_chat", playerName: "Alex", message: "今天天气不错" },
  { id: 8, type: "player_chat", playerName: "Steve", message: "桃桃跟我来" },
];

test("classifier decisions preserve event order", () => {
  const decisions = normalizeDecisions(EVENTS, {
    decisions: [
      {
        id: 8,
        route: "act",
        reason: "follow request",
        reply: "",
        continues_goal: false,
        capability_hint: "orient",
      },
      {
        id: 7,
        route: "ignore",
        reason: "human conversation",
        reply: "",
        continues_goal: false,
        capability_hint: "conversation",
      },
    ],
  });

  assert.deepEqual(decisions.map((item) => item.id), [7, 8]);
  assert.deepEqual(decisions.map((item) => item.route), ["ignore", "act"]);
});

test("classifier must return one unique decision per event", () => {
  assert.throws(
    () =>
      normalizeDecisions(EVENTS, {
        decisions: [
          {
            id: 7,
            route: "ignore",
            reason: "only one",
            reply: "",
            continues_goal: false,
            capability_hint: "conversation",
          },
        ],
      }),
    /every event/,
  );
});

test("router passes a strict output schema to the small-model thread", async () => {
  let receivedOptions;
  let receivedPrompt;
  const router = new ChatRouter(() => ({
    async run(prompt, options) {
      receivedPrompt = prompt;
      receivedOptions = options;
      return {
        finalResponse: JSON.stringify({
          decisions: [{
            id: 7,
            route: "ignore",
            reason: "test",
            reply: "",
            continues_goal: false,
            capability_hint: "conversation",
          }],
        }),
      };
    },
  }));

  const decisions = await router.classify(EVENTS);

  assert.equal(receivedOptions.outputSchema.type, "object");
  assert.deepEqual(decisions.map((item) => item.route), ["ignore", "act"]);
  assert.match(receivedPrompt, /current location\/status\/progress/);
  assert.match(receivedPrompt, /stuck\/not moving\/doing the wrong thing/);
  assert.match(receivedPrompt, /must never promise a future world change/);
  assert.match(receivedPrompt, /console_chat comes from the authenticated server panel/);
  assert.match(receivedPrompt, /classify it as reply or act, never ignore/);
  assert.match(receivedPrompt, /exact short player-visible answer/);
});

test("direct simple social chat is answered without starting a model thread", async () => {
  let starts = 0;
  const router = new ChatRouter(() => {
    starts += 1;
    throw new Error("deterministic greeting must not start the classifier");
  });

  const [decision] = await router.classify([
    {
      id: 9,
      type: "player_chat",
      playerName: "Alex",
      message: "桃桃，早上好！",
    },
  ]);

  assert.equal(starts, 0);
  assert.equal(decision.route, "reply");
  assert.equal(decision.reply, "早上好呀，Alex～");
  assert.equal(decision.fastReply, true);
});

test("clear direct gameplay requests bypass the classifier but still reach the agent", async () => {
  const player = deterministicDecision({
    id: 10,
    type: "player_chat",
    playerName: "Alex",
    message: "momo 帮我砍一棵树",
  });
  const console = deterministicDecision({
    id: 11,
    type: "console_chat",
    playerName: "Server",
    message: "回家并关上门",
  });

  assert.equal(player.route, "act");
  assert.equal(player.reply, "");
  assert.equal(player.continues_goal, false);
  assert.equal(player.capability_hint, "gather");
  assert.equal(console.route, "act");
  assert.equal(console.capability_hint, "direct_action");
  assert.match(console.reason, /console/);
  assert.equal(
    deterministicDecision({
      id: 13,
      type: "player_chat",
      playerName: "Alex",
      message: "桃桃，可以告诉我熔炉怎么合成吗？",
    }),
    null,
  );
});

test("Chinese requests choose the narrowest safe capability phase", () => {
  for (const [id, message, expected] of [
    [20, "桃桃，清理木屋周围的多余土块", "regional_edit"],
    [21, "桃桃，建一个安全的小屋", "structure"],
    [22, "桃桃，观察苦力怕接下来的攻击动作", "combat_learning"],
  ]) {
    const decision = deterministicDecision({
      id,
      type: "player_chat",
      playerName: "Alex",
      message,
    });
    assert.equal(decision?.route, "act", message);
    assert.equal(decision?.capability_hint, expected, message);
  }
});

test("explicit progress and correction messages continue the active goal", () => {
  for (const message of [
    "桃桃你卡住了",
    "桃桃继续刚才的工作",
    "桃桃换个办法",
  ]) {
    const decision = deterministicDecision({
      id: 14,
      type: "player_chat",
      playerName: "Alex",
      message,
    });
    assert.equal(decision.route, "act");
    assert.equal(decision.continues_goal, true, message);
  }
});

test("small-model static replies are returned ready for the direct chat sink", async () => {
  let calls = 0;
  const router = new ChatRouter(() => ({
    async run() {
      calls += 1;
      return {
        finalResponse: JSON.stringify({
          decisions: [
            {
              id: 12,
              route: "reply",
              reason: "static Minecraft question",
              reply: "熔炉需要八个圆石，中间留空。",
              continues_goal: false,
              capability_hint: "conversation",
            },
          ],
        }),
      };
    },
  }));

  const [decision] = await router.classify([
    {
      id: 12,
      type: "player_chat",
      playerName: "Alex",
      message: "熔炉怎么合成？",
    },
  ]);

  assert.equal(calls, 1);
  assert.equal(decision.route, "reply");
  assert.equal(decision.reply, "熔炉需要八个圆石，中间留空。");
  assert.equal(decision.fastReply, true);
});
