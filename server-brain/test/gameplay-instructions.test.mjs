import assert from "node:assert/strict";
import test from "node:test";

import { gameplayDeveloperInstructions } from "../src/gameplay-instructions.mjs";

test("gameplay developer instructions contain the stable persona and action contract", () => {
  const instructions = gameplayDeveloperInstructions(
    "momo",
    "你是游戏玩家桃桃。",
  );

  assert.match(instructions, /你是游戏玩家桃桃/);
  assert.match(instructions, /structure_plan once/);
  assert.match(instructions, /placement_feasibility/);
  assert.match(instructions, /server survival director/);
  assert.match(instructions, /accepted task_id/);
  assert.match(instructions, /playerName and playerUuid/);
});
