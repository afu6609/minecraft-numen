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
the defaults are `gpt-5.4-mini` at low reasoning and `gpt-5.6-luna` at high
reasoning. The gameplay persona lives in `persona/momo.md` and is injected into
every handled event.

## Restricted operator commands

The command bridge is disabled until `MOMO_COMMAND_PLAYERS` contains an exact
Minecraft player name. For this private server:

```dotenv
MOMO_COMMAND_PLAYERS=Haa258
```

An authorized player must use an explicit line such as:

```text
桃桃执行指令 /time set day
```

The Node sidecar checks the event's `playerName` before calling the command
tool. The Forge server then independently parses and rebuilds the command from
a semantic allowlist. It permits only:

- `/time set|add ...`
- `/weather clear|rain|thunder [duration]`
- `/tp <player>` or `/tp <x> <y> <z>` for Momo herself
- `/gamemode <mode>` for Momo herself
- `/difficulty <level>`

Selectors, extra targets, nested commands, and destructive or privilege
commands are rejected. The gameplay model cannot see or call `run_command`;
only the deterministic command gateway can use it.

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
