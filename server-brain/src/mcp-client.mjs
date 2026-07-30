const MCP_PROTOCOL_VERSION = "2025-06-18";

function firstText(result) {
  const item = result?.content?.find((entry) => entry?.type === "text");
  if (typeof item?.text !== "string") {
    throw new Error("Numen MCP returned no text content");
  }
  if (result.isError) throw new Error(item.text);
  return item.text;
}

export class NumenMcpClient {
  constructor(url, { token = "", timeoutMs = 10_000, fetchImpl = fetch } = {}) {
    this.url = new URL(url).toString();
    this.token = token;
    this.timeoutMs = timeoutMs;
    this.fetchImpl = fetchImpl;
    this.nextId = 1;
  }

  async initialize() {
    return this.#request("initialize", {
      protocolVersion: MCP_PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "momo-server-brain", version: "0.1.0" },
    });
  }

  async listTools() {
    const result = await this.#request("tools/list", {});
    return Array.isArray(result.tools) ? result.tools : [];
  }

  async callTool(name, args = {}) {
    const result = await this.#request("tools/call", {
      name,
      arguments: args,
    });
    return firstText(result);
  }

  async pollServerEvents(limit = 16) {
    const text = await this.callTool("poll_server_events", { limit });
    const events = JSON.parse(text);
    if (!Array.isArray(events)) {
      throw new Error("poll_server_events did not return an array");
    }
    return events;
  }

  async pollCompanionEvents(companion, limit = 16) {
    const text = await this.callTool("poll_companion_events", {
      companion,
      limit,
    });
    const events = JSON.parse(text);
    if (!Array.isArray(events)) {
      throw new Error("poll_companion_events did not return an array");
    }
    return events;
  }

  async sendChat(companion, message) {
    return this.callTool("send_chat", { companion, message });
  }

  async runCommand(companion, command) {
    return this.callTool("run_command", { companion, command });
  }

  async stopTask(companion) {
    return this.callTool("task_stop", { companion });
  }

  async stopNativeNavigation(companion) {
    return this.callTool("embodied_nav_stop", { companion });
  }

  async hasActiveBodyWork(companion) {
    const task = JSON.parse(
      await this.callTool("task_status", { companion }),
    );
    if (task?.data?.task_id != null) return true;

    try {
      const navigation = JSON.parse(
        await this.callTool("embodied_nav_status", { companion }),
      );
      const state = navigation?.data?.state;
      return ["planning", "moving", "waiting"].includes(state);
    } catch {
      return false;
    }
  }

  async #request(method, params) {
    const id = this.nextId++;
    const headers = {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
    };
    if (this.token !== "") headers.Authorization = `Bearer ${this.token}`;

    const response = await this.fetchImpl(this.url, {
      method: "POST",
      headers,
      body: JSON.stringify({ jsonrpc: "2.0", id, method, params }),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!response.ok) {
      throw new Error(`Numen MCP HTTP ${response.status}`);
    }
    const payload = await response.json();
    if (payload.error) {
      throw new Error(payload.error.message ?? `Numen MCP JSON-RPC ${payload.error.code}`);
    }
    if (!Object.hasOwn(payload, "result")) {
      throw new Error("Numen MCP response has no result");
    }
    return payload.result;
  }
}
