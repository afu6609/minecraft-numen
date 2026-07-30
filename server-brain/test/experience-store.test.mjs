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
  precondition_verifiers: [
    { type: "body_idle" },
    { type: "health_at_least", value: 10 },
  ],
  steps: [
    {
      tool: "embodied_survey_scene",
      args: { anchor_mode: "self", radius: 24 },
      expect: "at least one reachable trunk is returned",
      verifiers: [{ type: "tool_success" }],
    },
    {
      tool: "mine",
      args: { block: "${log_tag}", count: 1 },
      expect: "the trunk is harvested and drops are collected",
      verifiers: [
        { type: "tool_success" },
        { type: "task_terminal_done" },
      ],
    },
  ],
  postconditions: [
    "the harvested log count increased",
    "a sapling occupies a valid replacement position",
  ],
  postcondition_verifiers: [
    { type: "inventory_delta", item: "${log_tag}", minimum: 1 },
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
  const second = await store.recordValidation(SAMPLE.id, {
    success: true,
    evidence: "spruce biome",
    executionId: "execution-spruce",
    expectedVersion: 1,
  });
  const skill = await store.recordValidation(SAMPLE.id, {
    success: true,
    evidence: "oak forest",
    executionId: "execution-oak",
    expectedVersion: second.version,
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
  const second = await store.recordValidation(SAMPLE.id, {
    success: true,
    evidence: "spruce biome",
    executionId: "execution-spruce",
    expectedVersion: 1,
  });
  const trusted = await store.recordValidation(SAMPLE.id, {
    success: true,
    evidence: "oak forest",
    executionId: "execution-oak",
    expectedVersion: second.version,
  });
  assert.equal(trusted.status, "trusted");

  const skill = await store.recordValidation(SAMPLE.id, {
    success: false,
    evidence: "2x2 jungle trunk needs a different traversal plan",
    executionId: "execution-jungle",
    expectedVersion: trusted.version,
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
  assert.throws(
    () =>
      normalizeSkill({
        ...SAMPLE,
        steps: [{
          tool: "run_command",
          args: { command: "op momo" },
          expect: "command ran",
          verifiers: [{ type: "tool_success" }],
        }],
      }),
    /not allowed/,
  );
});

test("candidate creation cannot overwrite a trusted or existing skill", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-experience-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(SAMPLE);

  await assert.rejects(store.saveCandidate(SAMPLE), (error) => error.code === "EEXIST");
  const revised = await store.saveRevision("chop_and_replant", 1, {
    ...SAMPLE,
    description: "A revised guarded harvesting workflow.",
  });
  assert.equal(revised.version, 2);
  assert.equal(revised.status, "candidate");
  await assert.rejects(
    store.saveRevision("chop_and_replant", 1, SAMPLE),
    /revision conflict/,
  );
});

test("validation commits are idempotent and pinned to the executed version", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "momo-experience-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const store = new ExperienceStore(root);
  await store.saveCandidate(SAMPLE);

  const validation = {
    success: true,
    evidence: "same completed macro replayed after restart",
    executionId: "skill-execution-7",
    expectedVersion: 1,
  };
  const [first, replay] = await Promise.all([
    store.recordValidation(SAMPLE.id, validation),
    store.recordValidation(SAMPLE.id, validation),
  ]);

  assert.equal(first.version, 2);
  assert.equal(replay.version, 2);
  assert.equal(replay.validations.successes, 2);
  assert.deepEqual(replay.validations.execution_results, [
    { id: "skill-execution-7", success: true },
  ]);
  await assert.rejects(
    store.recordValidation(SAMPLE.id, {
      success: true,
      evidence: "a different execution cannot validate an obsolete definition",
      executionId: "skill-execution-8",
      expectedVersion: 1,
    }),
    /definition changed during execution/,
  );
});
