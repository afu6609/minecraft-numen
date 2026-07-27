import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  ExperienceStore,
  executionPolicy,
  normalizeSkill,
} from "../src/experience-store.mjs";

const SAMPLE = {
  id: "chop_and_replant",
  intent: "Harvest one nearby tree and replace it with a sapling.",
  description: "A reusable, verified tree-harvesting workflow.",
  parameters: {
    log_tag: "minecraft:logs",
  },
  preconditions: [
    "an axe or an empty hand is available",
    "at least one reachable tree is nearby",
  ],
  steps: [
    {
      tool: "scan_blocks",
      args: { tag: "${log_tag}", radius: 24 },
      expect: "at least one reachable trunk is returned",
    },
    {
      tool: "auto_mine",
      args: { block: "${selected_log}", count: 1 },
      expect: "the trunk is harvested and drops are collected",
    },
  ],
  postconditions: [
    "the harvested log count increased",
    "a sapling occupies a valid replacement position",
  ],
  recovery: [
    "if no sapling dropped, report that replanting could not be completed",
    "if pathing fails, rescan once from the current position",
  ],
  provenance: {
    source_task: "player asked to cut one oak and replant it",
  },
};

test("one success creates a cautious candidate", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-experience-"));
  t.after(() => rm(root, { recursive: true, force: true }));

  const store = new ExperienceStore(root);
  const skill = await store.saveCandidate(SAMPLE);

  assert.equal(skill.status, "candidate");
  assert.equal(skill.validations.successes, 1);
  assert.equal(executionPolicy(skill), "verify_each_step");
  assert.deepEqual((await store.list()).map((item) => item.id), ["chop_and_replant"]);
});

test("three clean successes promote a candidate", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-experience-"));
  t.after(() => rm(root, { recursive: true, force: true }));

  const store = new ExperienceStore(root);
  await store.saveCandidate(SAMPLE);
  await store.recordValidation(SAMPLE.id, { success: true, evidence: "spruce biome" });
  const skill = await store.recordValidation(SAMPLE.id, {
    success: true,
    evidence: "oak forest",
  });

  assert.equal(skill.status, "trusted");
  assert.equal(skill.validations.successes, 3);
  assert.equal(executionPolicy(skill), "verify_final");
});

test("a failure demotes and records repair evidence", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-experience-"));
  t.after(() => rm(root, { recursive: true, force: true }));

  const store = new ExperienceStore(root);
  await store.saveCandidate(SAMPLE);
  await store.recordValidation(SAMPLE.id, { success: true, evidence: "spruce biome" });
  const trusted = await store.recordValidation(SAMPLE.id, {
    success: true,
    evidence: "oak forest",
  });
  assert.equal(trusted.status, "trusted");

  const skill = await store.recordValidation(SAMPLE.id, {
    success: false,
    evidence: "2x2 jungle trunk needs a different traversal plan",
  });

  assert.equal(skill.status, "candidate");
  assert.equal(skill.validations.failures, 1);
  assert.match(skill.provenance.last_evidence, /jungle trunk/);
});

test("unsafe ids and malformed steps are rejected", () => {
  assert.throws(
    () => normalizeSkill({ ...SAMPLE, id: "../escape" }),
    /id must contain/,
  );
  assert.throws(
    () => normalizeSkill({ ...SAMPLE, steps: [] }),
    /at least one/,
  );
});
