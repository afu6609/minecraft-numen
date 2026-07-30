# Momo server brain

This directory contains the private, event-driven process that connects Codex
to the dedicated-server Numen MCP endpoint.

Every human chat message enters a bounded server queue. A small Codex model
classifies a batch as `ignore`, `reply`, or `act`; only `reply` and `act` wake
the persistent gameplay agent. Player replies are sent through the companion
body and appear to vanilla clients as ordinary `<momo> text` chat.

Trusted arena mods can also publish a `test_instruction` event through the
server-only Java `ServerBrainAdminEvents` API. It bypasses the chat classifier
and reaches the high-level Momo brain directly while retaining the same
Numen-only survival tools and server safety rules. Test events default to a
fresh reasoning context and carry both a run id and an explicit
dimension/x/y/z arena anchor. Privileged fixture mutation remains inside the
server mod and is never exposed to the model.

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

## Hot model selection

The gameplay model and reasoning effort can be changed without restarting the
sidecar or interrupting an active turn. The Forge control plane publishes a
trusted `brain_config_request`; the sidecar validates the complete
model/reasoning pair, atomically persists it, and only then acknowledges it
through `report_brain_config_state`. The next logical gameplay event resumes
the existing Codex thread id with the new options, preserving Minecraft task
context. If that id is unavailable, a new thread is started.
Because the SDK validates persisted history on the resumed thread's first
`run`, the sidecar retries that same prompt once on a new thread only when the
error explicitly says the thread or rollout is missing. Model, capacity, tool,
and ordinary turn failures are never retried by this fallback.

Phase one intentionally exposes only combinations verified for this host:

- `gpt-5.6-luna`: `low`, `medium`, `high`, `xhigh`
- `gpt-5.3-codex-spark`: `low`, `medium`, `high`, `xhigh`

The authoritative selection lives in
`runtime/model-selection.json` by default. `MOMO_AGENT_MODEL` and
`MOMO_AGENT_REASONING` are bootstrap values used only when that state file does
not exist; hot switching never edits `.env`. Every request has a short expiry,
and `set` requires both fields so a model/reasoning combination cannot tear.
Startup and MCP recovery both re-announce the current selection and catalog.

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

## Autonomous player-action loop

The gameplay model is the planner. It observes the live world, chooses a small
bounded action, lets the normal fake-player body execute it, then observes the
task result and replans in the same persistent Codex thread. It is not limited
to selecting one opaque task macro.

The low-level surface includes:

- `survey_scene` for a compact server-derived scene graph with stable ids for
  trees, building candidates, entrances, pits/depressions, and ground rises;
- `inspect_object` for the chosen object's compact exact geometry, current
  material histogram, relations, provenance confidence, and protection policy;
- `observe_volume` for a precise, bounded 3D block snapshot;
- `break_block` for one visible cell guarded by its freshly observed block id;
- `build` for an explicit list of placements or `minecraft:air` removals;
- `structure_plan` for a persistent exact blueprint and material ledger;
- `structure_status` for live reconciliation after every checkpoint;
- `placement_feasibility` for a read-only check of exact requested state,
  support, footprint, entities, line of sight, and real standable positions;
- `structure_patch` for an atomic local blueprint revision using
  `expected_revision`, `upsert`, and `remove_positions`;
- `structure_execute` for local 1-128-cell build or demolition batches;
- movement, interaction, combat, inventory, crafting, and entity perception
  primitives.

`mine` remains a resource-gathering macro and has no coordinate boundary. The
agent is explicitly forbidden from using it to demolish or edit structures.
Unfamiliar destructive edits run in small verified checkpoints.

The planner now uses semantic perception in layers: `survey_scene` finds and
names bounded objects, `inspect_object` expands only the selected id, and
`observe_volume` is reserved for exact cavities or block states. Old chunks do
not contain a trustworthy per-block creator ledger, so likely player-made
structures are explicitly marked as an inference and protected by default.

Larger construction goals use a persisted workflow:

1. survey the site and inventory;
2. save a complete blueprint and calculate exact material shortfalls;
3. gather/craft only those shortfalls;
4. execute one normal-player checkpoint batch;
5. reconcile the blueprint with the live world;
6. for a failed state-sensitive cell, run `structure_status →`
   `placement_feasibility`; only when preflight recommends a blueprint change,
   continue with `structure_patch → placement_feasibility → structure_execute`.

`structure_plan` is only for the initial complete blueprint or an intentional
complete redesign. A local correction never resends the whole manifest:
`structure_patch` changes only affected cells and rejects stale revisions.
Placement preflight returns at most one directly usable patch per call; apply
it atomically and preflight the new revision before requesting another repair.

The recovery loop tracks the exact target coordinate, requested block state,
and failure reason. A move by itself does not refresh that evidence, and a
second identical failure exhausts the unchanged-placement retry budget. A
successful structure patch refreshes the budget. Body-defense interruptions
retain unfinished task context for `defense_finished` or `body_available`;
an explicit player stop discards that pending recovery.

Combat is split into two latency layers. The server-side survival director
observes authoritative entity state and makes immediate fight/retreat,
shield/cover, equipment, and certified-shelter decisions from health,
absorption, hunger, armor, inventory capabilities, effects, threat count and
local terrain. The Codex sidecar may inspect a bounded historical trace with
`get_combat_trace` and save an exact entity/adapter/schema-bound declarative
policy. Policies contain only bounded combat verbs; every tick still passes the
server supervisor. Candidates are capped, require three server-confirmed
successes to become trusted, and automatically deactivate on failure.

The manifest is stored in the Minecraft world's `data` directory, so the plan
can be resumed after a sidecar or server restart. Demolition reuses exact saved
coordinates and skips cells whose block no longer matches the blueprint. It
therefore removes work in player-like sequence without either one model turn
per block or an instant server-side `/fill`.

Server chat polling runs independently from Codex turns. A direct stop phrase
aborts the current SDK turn, cancels stale queued action chat, and calls
`task_stop` on the companion body before acknowledging the player.

At startup the sidecar verifies that the server exposes the complete workflow
surface: `structure_plan`, `structure_status`, `structure_execute`,
`structure_patch`, and `placement_feasibility`, plus combat observation and
policy tools. A mismatched old mod therefore fails visibly instead of starting
with a recovery path it cannot execute.

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
