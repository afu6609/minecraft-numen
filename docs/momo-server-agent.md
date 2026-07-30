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

## Runtime model control

Operators and the dedicated-server console can inspect and atomically change
the sidecar's active execution model/reasoning pair:

```text
momo brain status
momo brain list
momo brain set <model> <reasoning>
```

The command requires permission level 4 and uses the global
`poll_server_events` transport, so it works without a live companion body. A
command only queues a `brain_config_request` with a 30-second apply deadline;
Forge never claims that its own local state switched. The sidecar applies or
rejects the complete pair, then calls the companion-independent
`report_brain_config_state` MCP control tool. Forge keeps a further 30-second
acknowledgement grace period for a result that was durably applied before the
deadline but whose report is being retried. Only a matching, non-stale, applied
acknowledgement updates the cached authoritative state and produces a success
response for the originating console or player UUID. Startup reports populate
the model/effort catalog, and `status`/`list` still request a fresh report every
time.

## Optional client compatibility

The dedicated-server build accepts clients that do not advertise Numen's custom
network channel. The server skips Numen-only S2C payloads for those connections
instead of disconnecting them.

An unmodified Java client (including a Java mobile launcher such as Pojav) can:

- join the server normally;
- see the Momo fake-player body, skin/name, movement, mining, building, and
  combat;
- read Momo's `<momo> text` replies in ordinary chat form;
- be observed by server-side tools, including current health, carried
  inventory, position, and nearby terrain.

It does not receive Numen's client UI, roster/HUD, inventory screen, death and
respawn widgets, or path/debug overlays. A desktop client with the matching
Numen protocol keeps those features. This optional channel applies only to
Numen; another installed mod may still require its own client counterpart.
Bedrock clients are a separate protocol and still need a bridge such as
Geyser/Floodgate.

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
3. Ordinary server-chat ingress with small-model routing.
4. Full online-player status/inventory and nearby-terrain perception.
5. Codex SDK sidecar reusing ChatGPT subscription login.
6. Experience capture, candidate replay, validation, and promotion.
7. Optional-client networking and mobile-client compatibility.
8. Long-run tests, systemd deployment, monitoring, and rollback.
