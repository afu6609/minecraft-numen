import http from "node:http";

const PROTOCOL_VERSION = "2025-06-18";
const MAX_REQUEST_BYTES = 128 * 1024;

export const HARNESS_MCP_TOOLS = Object.freeze([
  "list_skills",
  "run_skill",
  "save_skill_candidate",
]);

function toolDefinitions() {
  return [
    {
      name: "list_skills",
      description:
        "List bounded learned Minecraft workflows and their parameter defaults. The same compact catalog is normally present in the Harness capsule.",
      inputSchema: {
        type: "object",
        additionalProperties: false,
        properties: {},
      },
    },
    {
      name: "run_skill",
      description:
        "Run one stored candidate/trusted workflow through the verified Harness state machine. It serializes Numen tools, pauses on exact task_id terminals, and always verifies typed postconditions.",
      inputSchema: {
        type: "object",
        additionalProperties: false,
        properties: {
          skill_id: { type: "string", pattern: "^[a-z0-9]+(?:_[a-z0-9]+)*$" },
          parameters: { type: "object" },
        },
        required: ["skill_id", "parameters"],
      },
    },
    {
      name: "save_skill_candidate",
      description:
        "Save a bounded parameterized workflow draft. The Harness forces candidate status, typed verifiers, an execution allowlist, create-only/CAS revisions, and zero validations until it succeeds under the runner.",
      inputSchema: {
        type: "object",
        additionalProperties: false,
        properties: {
          draft: { type: "object" },
          expected_version: { type: "integer", minimum: 1 },
        },
        required: ["draft"],
      },
    },
  ];
}

function rpcResult(id, result) {
  return { jsonrpc: "2.0", id, result };
}

function rpcError(id, code, message) {
  return {
    jsonrpc: "2.0",
    id,
    error: { code, message: String(message).slice(0, 500) },
  };
}

function toolResult(payload, isError = false) {
  return {
    content: [{ type: "text", text: JSON.stringify(payload) }],
    structuredContent: payload,
    ...(isError ? { isError: true } : {}),
  };
}

export class HarnessMcpServer {
  constructor(
    runner,
    {
      host = "127.0.0.1",
      port = 8766,
      token,
      onError = null,
    } = {},
  ) {
    if (typeof token !== "string" || token.length < 24) {
      throw new TypeError("Harness MCP token must contain at least 24 characters");
    }
    this.runner = runner;
    this.host = host;
    this.port = port;
    this.token = token;
    this.onError = typeof onError === "function" ? onError : null;
    this.server = http.createServer((request, response) => {
      this.#handle(request, response).catch((error) => {
        this.onError?.(error);
        if (!response.headersSent) {
          response.writeHead(500, { "Content-Type": "application/json" });
        }
        response.end(
          JSON.stringify(rpcError(null, -32603, "Harness MCP internal error")),
        );
      });
    });
  }

  async start() {
    await new Promise((resolve, reject) => {
      const onError = (error) => {
        this.server.off("listening", onListening);
        reject(error);
      };
      const onListening = () => {
        this.server.off("error", onError);
        resolve();
      };
      this.server.once("error", onError);
      this.server.once("listening", onListening);
      this.server.listen(this.port, this.host);
    });
    const address = this.server.address();
    const port = typeof address === "object" ? address.port : this.port;
    return `http://${this.host}:${port}/mcp`;
  }

  async close() {
    if (!this.server.listening) return;
    await new Promise((resolve, reject) =>
      this.server.close((error) => (error ? reject(error) : resolve())),
    );
  }

  async #handle(request, response) {
    if (
      request.method !== "POST" ||
      request.url == null ||
      new URL(request.url, "http://localhost").pathname !== "/mcp"
    ) {
      response.writeHead(404).end();
      return;
    }
    if (request.headers.authorization !== `Bearer ${this.token}`) {
      response.writeHead(401, { "Content-Type": "application/json" });
      response.end(JSON.stringify(rpcError(null, -32001, "Unauthorized")));
      return;
    }
    const chunks = [];
    let size = 0;
    for await (const chunk of request) {
      size += chunk.length;
      if (size > MAX_REQUEST_BYTES) {
        response.writeHead(413).end();
        return;
      }
      chunks.push(chunk);
    }
    let message;
    try {
      message = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      response.writeHead(400, { "Content-Type": "application/json" });
      response.end(JSON.stringify(rpcError(null, -32700, "Parse error")));
      return;
    }
    if (
      message?.jsonrpc !== "2.0" ||
      typeof message.method !== "string"
    ) {
      response.writeHead(400, { "Content-Type": "application/json" });
      response.end(
        JSON.stringify(rpcError(message?.id ?? null, -32600, "Invalid request")),
      );
      return;
    }
    if (message.id == null) {
      response.writeHead(202).end();
      return;
    }

    let result;
    try {
      if (message.method === "initialize") {
        result = {
          protocolVersion: PROTOCOL_VERSION,
          capabilities: { tools: {} },
          serverInfo: { name: "momo-harness", version: "0.2.0" },
        };
      } else if (message.method === "ping") {
        result = {};
      } else if (message.method === "tools/list") {
        result = { tools: toolDefinitions() };
      } else if (message.method === "tools/call") {
        result = await this.#callTool(
          message.params?.name,
          message.params?.arguments ?? {},
        );
      } else {
        response.writeHead(200, { "Content-Type": "application/json" });
        response.end(
          JSON.stringify(rpcError(message.id, -32601, "Method not found")),
        );
        return;
      }
    } catch (error) {
      result = toolResult(
        {
          success: false,
          message: error instanceof Error ? error.message : String(error),
        },
        true,
      );
    }
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify(rpcResult(message.id, result)));
  }

  async #callTool(name, args) {
    if (name === "list_skills") {
      return toolResult({
        success: true,
        data: { skills: await this.runner.refreshCatalog() },
      });
    }
    if (name === "run_skill") {
      if (
        typeof args?.skill_id !== "string" ||
        args.parameters == null ||
        typeof args.parameters !== "object" ||
        Array.isArray(args.parameters)
      ) {
        throw new TypeError("run_skill requires skill_id and parameters");
      }
      return toolResult(
        await this.runner.run(args.skill_id, args.parameters),
      );
    }
    if (name === "save_skill_candidate") {
      if (
        args?.draft == null ||
        typeof args.draft !== "object" ||
        Array.isArray(args.draft)
      ) {
        throw new TypeError("save_skill_candidate requires a draft object");
      }
      const skill = await this.runner.saveCandidate(args.draft, {
        expectedVersion: args.expected_version ?? null,
      });
      return toolResult({
        success: true,
        message: `Saved candidate skill ${skill.id} version ${skill.version}.`,
        data: {
          async: false,
          skill_id: skill.id,
          skill_version: skill.version,
          status: skill.status,
          validations: skill.validations,
        },
      });
    }
    throw new Error(`Unknown Harness tool ${String(name)}`);
  }
}
