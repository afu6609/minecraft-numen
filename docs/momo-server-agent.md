# Momo dedicated-server agent

This fork keeps Numen's fake-player body, survival reflexes, tools, and task
scheduler, while moving the high-level agent loop from the owner's Minecraft
client to a private process beside the dedicated server.

## Repository relationship

- `upstream` remains `Dwinovo/minecraft-numen`.
- Work for the private server lives on `momo/server-agent`.
- Existing copyright, LGPL source licensing, MIT public-API licensing, and
  reserved asset licensing remain intact.
- New behavior should be introduced through public seams where practical so
  upstream fixes remain mergeable.

## Runtime boundary

```text
Minecraft clients (no Numen client mod required)
                 |
             normal chat
                 v
Forge dedicated server
  NumenPlayer + tools + task scheduler + reflexes
                 |
         loopback MCP (127.0.0.1)
                 v
server-brain
  Codex SDK + memory + experience store + policy
```

The Minecraft tick loop never waits for a model response. Combat cooldowns,
path execution, digging progress, drowning protection, eating, and other
real-time behavior stay deterministic in the Forge process. The model is called
only for player messages, planning boundaries, significant failures, and task
completion.

## Harness, skills, and MCP

The whole assembly is an **agent harness**:

- the model reasons about a goal;
- MCP exposes the current world and safe actions;
- the task scheduler performs real-time gameplay;
- memory preserves people, places, and unfinished work;
- policy controls permissions, rate limits, and promotion of learned behavior;
- evaluations decide whether a learned workflow is actually reliable.

MCP is the capability boundary, not the learned behavior itself. Creating a new
MCP server after every successful task would create an unstable, ever-growing
tool surface. Instead, the harness exposes stable primitive tools plus one
generic `run_skill` capability. Learned workflows live in the experience store.

### Promotion lifecycle

1. A task succeeds and postconditions are observed.
2. The model summarizes the trace into a parameterized candidate skill.
3. The candidate is immediately reusable in `verify_each_step` mode.
4. Independent successful replays increase its validation count.
5. After the configured threshold, with no recorded failures, it becomes
   trusted and runs in `verify_final` mode.
6. A failure demotes a trusted skill and records evidence for repair.
7. Only stable, high-frequency workflows that need a stronger implementation
   are promoted into a dedicated Java/TypeScript MCP tool with tests.

One success is therefore enough to remember a procedure, but never enough to
claim it will always succeed. Minecraft world state, inventory, mods, hostile
mobs, unloaded chunks, and terrain can all change. Preconditions,
postconditions, timeouts, and recovery steps are part of every learned skill.

## Initial delivery slices

1. Dedicated-server actuator and direct result routing in `numen-api`.
2. Dedicated-server MCP lifecycle and loopback-only configuration.
3. Ordinary server-chat ingress and family allowlist.
4. Full player-status/inventory perception.
5. Codex SDK sidecar with ChatGPT device login.
6. Experience capture, candidate replay, validation, and promotion.
7. Optional-client networking and mobile-client compatibility.
8. Long-run tests, systemd deployment, monitoring, and rollback.
