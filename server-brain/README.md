# Momo server brain

This directory will host the private, event-driven process that connects Codex
to the dedicated-server Numen MCP endpoint. It intentionally starts with the
experience layer before adding model credentials.

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
