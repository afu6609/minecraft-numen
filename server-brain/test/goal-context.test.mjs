import assert from "node:assert/strict";
import test from "node:test";

import {
  continueGoalContext,
  createGoalContext,
} from "../src/goal-context.mjs";

test("goal context keeps the original objective while tracking corrections", () => {
  const started = createGoalContext({
    id: 41,
    type: "player_chat",
    playerName: "Alex",
    playerUuid: "uuid-alex",
    message: "帮我建一个小屋",
  });
  const continued = continueGoalContext(started, {
    id: 42,
    type: "player_chat",
    playerName: "Alex",
    message: "门改到东边",
  });

  assert.equal(started.goal_id, "player_chat:41");
  assert.equal(continued.goal_id, started.goal_id);
  assert.equal(continued.objective, "帮我建一个小屋");
  assert.equal(continued.latest_instruction, "门改到东边");
  assert.equal(continued.latest_event_id, 42);
});

test("test goals use the trusted run id when available", () => {
  const goal = createGoalContext(
    {
      id: 7,
      type: "test_instruction",
      runId: "arena-3",
      message: "抵御一只骷髅",
    },
    { kind: "test" },
  );

  assert.equal(goal.goal_id, "test_instruction:arena-3");
  assert.equal(goal.kind, "test");
});

test("a cross-player correction keeps both identities explicit", () => {
  const goal = continueGoalContext(
    createGoalContext({
      id: 9,
      type: "player_chat",
      playerName: "Alex",
      playerUuid: "uuid-alex",
      message: "建个小屋",
    }),
    {
      id: 10,
      type: "player_chat",
      playerName: "Steve",
      playerUuid: "uuid-steve",
      message: "先停一下",
    },
  );

  assert.equal(goal.speaker, "Alex");
  assert.equal(goal.speaker_uuid, "uuid-alex");
  assert.equal(goal.latest_speaker, "Steve");
  assert.equal(goal.latest_speaker_uuid, "uuid-steve");
  assert.equal(goal.latest_instruction, "先停一下");
});
