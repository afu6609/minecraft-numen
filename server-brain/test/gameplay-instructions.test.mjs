import assert from "node:assert/strict";
import test from "node:test";

import { gameplayDeveloperInstructions } from "../src/gameplay-instructions.mjs";

test("gameplay developer instructions contain the stable persona and action contract", () => {
  const common = gameplayDeveloperInstructions(
    "momo",
    "你是游戏玩家桃桃。",
  );
  const structure = gameplayDeveloperInstructions("momo", "", "structure");
  const survival = gameplayDeveloperInstructions("momo", "", "survival");
  const reconcile = gameplayDeveloperInstructions("momo", "", "reconcile");

  assert.match(common, /你是游戏玩家桃桃/);
  assert.match(common, /accepted task_id/);
  assert.match(common, /playerName\/playerUuid/);
  assert.doesNotMatch(common, /structure_plan once/);
  assert.match(structure, /structure_plan once/);
  assert.match(structure, /placement_feasibility/);
  assert.match(structure, /Workflow drafting is kept outside ordinary player-driven model turns/);
  assert.doesNotMatch(structure, /save_skill_candidate/);
  assert.match(survival, /server survival director/);
  assert.match(reconcile, /read-only recovery phase/);
  assert.match(reconcile, /no action tools/);
  assert.match(reconcile, /Do not retry, continue, repair, move/);
});
