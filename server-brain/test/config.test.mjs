import assert from "node:assert/strict";
import test from "node:test";

import { loadConfig } from "../src/config.mjs";

test("configuration defaults to loopback and split models", () => {
  const config = loadConfig({}, "/srv/momo");

  assert.equal(config.mcpUrl, "http://127.0.0.1:8765/mcp");
  assert.equal(config.classifierModel, "gpt-5.4-mini");
  assert.equal(config.agentModel, "gpt-5.4");
  assert.equal(config.companion, "momo");
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
