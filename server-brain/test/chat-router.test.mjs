import assert from "node:assert/strict";
import test from "node:test";

import { ChatRouter, normalizeDecisions } from "../src/chat-router.mjs";

const EVENTS = [
  { id: 7, type: "player_chat", playerName: "Alex", message: "今天天气不错" },
  { id: 8, type: "player_chat", playerName: "Steve", message: "桃桃跟我来" },
];

test("classifier decisions preserve event order", () => {
  const decisions = normalizeDecisions(EVENTS, {
    decisions: [
      { id: 8, route: "act", reason: "follow request" },
      { id: 7, route: "ignore", reason: "human conversation" },
    ],
  });

  assert.deepEqual(decisions.map((item) => item.id), [7, 8]);
  assert.deepEqual(decisions.map((item) => item.route), ["ignore", "act"]);
});

test("classifier must return one unique decision per event", () => {
  assert.throws(
    () =>
      normalizeDecisions(EVENTS, {
        decisions: [{ id: 7, route: "ignore", reason: "only one" }],
      }),
    /every event/,
  );
});

test("router passes a strict output schema to the small-model thread", async () => {
  let receivedOptions;
  const router = new ChatRouter(() => ({
    async run(_prompt, options) {
      receivedOptions = options;
      return {
        finalResponse: JSON.stringify({
          decisions: EVENTS.map((event) => ({
            id: event.id,
            route: "ignore",
            reason: "test",
          })),
        }),
      };
    },
  }));

  const decisions = await router.classify(EVENTS);

  assert.equal(receivedOptions.outputSchema.type, "object");
  assert.deepEqual(decisions.map((item) => item.route), ["ignore", "ignore"]);
});
