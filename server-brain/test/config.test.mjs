import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import { loadConfig } from "../src/config.mjs";

test("configuration defaults to loopback and split models", () => {
  const config = loadConfig({}, "/srv/momo");

  assert.equal(config.mcpUrl, "http://127.0.0.1:8765/mcp");
  assert.equal(config.classifierModel, "gpt-5.4-mini");
  assert.equal(config.agentModel, "gpt-5.6-luna");
  assert.equal(config.classifierReasoning, "low");
  assert.equal(config.agentReasoning, "high");
  assert.equal(config.companion, "momo");
  assert.deepEqual(config.commandPlayers, []);
  assert.equal(
    config.personaFile,
    path.join(config.workingDirectory, "persona", "momo.md"),
  );
});

test("reasoning effort matches Codex-supported values", () => {
  assert.equal(
    loadConfig({ MOMO_CLASSIFIER_REASONING: "none" }).classifierReasoning,
    "none",
  );
  assert.throws(
    () => loadConfig({ MOMO_CLASSIFIER_REASONING: "minimal" }),
    /must be one of none, low, medium, high, xhigh/,
  );
});

test("command player names are explicit configuration", () => {
  assert.deepEqual(
    loadConfig({ MOMO_COMMAND_PLAYERS: " Haa258,Steve, " }).commandPlayers,
    ["Haa258", "Steve"],
  );
});

test("remote MCP endpoints need explicit opt-in", () => {
  assert.throws(
    () => loadConfig({ NUMEN_MCP_URL: "http://10.0.0.8:8765/mcp" }),
    /loopback-only/,
  );
  assert.equal(
    loadConfig({
      NUMEN_MCP_URL: "http://10.0.0.8:8765/mcp",
      MOMO_ALLOW_REMOTE_MCP: "true",
    }).mcpUrl,
    "http://10.0.0.8:8765/mcp",
  );
});
