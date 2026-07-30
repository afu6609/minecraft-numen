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

  async callTool(name, args = {}, { timeoutMs = this.timeoutMs } = {}) {
    const result = await this.#request("tools/call", {
      name,
      arguments: args,
    }, timeoutMs);
    return firstText(result);
  }

  async callToolJson(name, args = {}, options = {}) {
    const text = await this.callTool(name, args, options);
    const payload = JSON.parse(text);
    if (payload == null || typeof payload !== "object" || Array.isArray(payload)) {
      throw new Error(`${name} did not return a JSON object`);
    }
    return payload;
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

  async reportBrainConfigState(report) {
    return this.callTool("report_brain_config_state", report);
  }

  async runCommand(companion, command, requestId) {
    return this.callTool("run_command", {
      companion,
      command,
      request_id: requestId,
    });
  }

  async stopTask(companion) {
    return this.callTool("task_stop", { companion });
  }

  async stopNativeNavigation(companion) {
    return this.callTool("embodied_nav_stop", { companion });
  }

  async hasActiveBodyWork(companion) {
    return (await this.inspectBodyWork(companion)).activeTaskIds.length > 0;
  }

  async inspectServerSession(companion) {
    const task = await this.callToolJson("task_status", { companion });
    if (
      task?.success !== true ||
      typeof task?.data?.server_session_id !== "string" ||
      task.data.server_session_id.trim() === ""
    ) {
      throw new Error(
        "task_status did not return an authoritative server session",
      );
    }
    return {
      serverSessionId: task.data.server_session_id.trim(),
      task,
    };
  }

  async inspectBodyWork(companion, { jobIds = [] } = {}) {
    const { serverSessionId, task } =
      await this.inspectServerSession(companion);
    const activeTaskIds = new Set();
    if (
      typeof task?.data?.task_id === "string" &&
      ["running", "queued"].includes(task?.data?.state)
    ) {
      activeTaskIds.add(task.data.task_id);
    }

    const navigation = await this.callToolJson(
      "embodied_nav_status",
      { companion },
    );
    if (
      navigation?.success !== true ||
      typeof navigation?.data?.state !== "string"
    ) {
      throw new Error("embodied_nav_status was not authoritative");
    }
    const state = navigation?.data?.state;
    if (
      typeof navigation?.data?.task_id === "string" &&
      ["planning", "moving", "waiting"].includes(state)
    ) {
      activeTaskIds.add(navigation.data.task_id);
    }

    const jobs = [];
    for (const jobId of [...new Set(jobIds)].slice(0, 16)) {
      if (typeof jobId !== "string" || jobId.trim() === "") continue;
      const job = await this.callToolJson("embodied_job_status", {
        companion,
        job_id: jobId,
      });
      jobs.push(job);
      if (job?.success !== true) {
        // A typed negative response means the old job is no longer live. A
        // malformed envelope is different: it cannot safely prove idleness.
        if (job?.success === false) continue;
        throw new Error("embodied_job_status was not authoritative");
      }
      const jobSnapshot = job?.data?.job ?? job?.data;
      if (jobSnapshot == null || typeof jobSnapshot !== "object") {
        throw new Error("embodied_job_status returned no job snapshot");
      }
      if (
        typeof jobSnapshot?.external_task_id === "string" &&
        ["queued", "running"].includes(jobSnapshot?.state)
      ) {
        activeTaskIds.add(jobSnapshot.external_task_id);
      }
    }

    return {
      capturedAt: new Date().toISOString(),
      serverSessionId,
      task,
      navigation,
      jobs,
      activeTaskIds: [...activeTaskIds],
    };
  }

  async #request(method, params, timeoutMs = this.timeoutMs) {
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
      signal: AbortSignal.timeout(timeoutMs),
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
