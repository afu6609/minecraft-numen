# Momo server brain

This directory contains the private, event-driven process that connects Codex
to the dedicated-server Numen MCP endpoint.

Every human chat message enters a bounded server queue. A small Codex model
classifies a batch as `ignore`, `reply`, or `act`; only `reply` and `act` wake
the persistent gameplay agent. Player replies are sent through the companion
body and appear to vanilla clients as ordinary `<momo> text` chat.

## Authentication and startup

Install Node.js 18 or newer plus the Codex CLI on the same Ubuntu host. Sign in
once with the ChatGPT account whose Codex subscription should be used:

```shell
codex login
codex login status
```

Then install and start the sidecar:

```shell
cp .env.example .env
npm install
set -a
. ./.env
set +a
npm start
```

The official Codex SDK reuses the CLI's cached ChatGPT login. No API key is
needed for this mode. `NUMEN_MCP_URL` is rejected unless it is loopback by
default. The classifier and gameplay model are separate, configurable values;
the defaults are `gpt-5.4-mini` and `gpt-5.4`.

Run one polling cycle for deployment checks with:

```shell
node src/index.mjs --once
```

## Experience policy

A successful task may be summarized into a candidate skill immediately.
Candidates are reusable, but every step is verified. The default promotion
threshold is three successful validations with no recorded failure. Trusted
skills only verify their final postconditions; any failure demotes them.

This is deliberately different from generating a new MCP server for every
success:

- MCP tools are stable capabilities such as `goto`, `auto_mine`, and
  `get_player_status`.
- Skills are parameterized workflows composed from those tools.
- A future generic `run_skill` MCP tool executes a stored workflow.
- Only a repeatedly useful workflow that cannot be expressed reliably through
  primitives should become a new first-class MCP tool.

Run the dependency-free experience tests with:

```shell
npm test
```
