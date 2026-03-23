import { createHash } from "node:crypto";
import { readdir } from "node:fs/promises";
import { createServer, type Server as HttpServer, type IncomingMessage, type ServerResponse } from "node:http";
import { join, resolve, sep } from "node:path";
import {
	AuthStorage,
	type CreateAgentSessionResult,
	createAgentSession,
	getAgentDir,
	ModelRegistry,
	type SessionInfo,
	SessionManager,
	SettingsManager,
} from "@mariozechner/pi-coding-agent";
import { DEFAULT_SERVER_PORT, type LoadedServerSettings, loadServerSettings, type ServerSettings } from "./config.js";

export interface OrchestratorSdkOptions {
	cwd?: string;
	agentDir?: string;
	authStorage?: AuthStorage;
	modelRegistry?: ModelRegistry;
	settingsManager?: SettingsManager;
	serverSettings?: ServerSettings;
	serverSettingsPath?: string;
}

export interface OrchestratorSdkRuntime {
	cwd: string;
	agentDir: string;
	authStorage: AuthStorage;
	modelRegistry: ModelRegistry;
	settingsManager: SettingsManager;
	serverSettings: LoadedServerSettings;
	createSession(sessionManager?: SessionManager): Promise<CreateAgentSessionResult>;
}

export interface OrchestratorServerOptions extends OrchestratorSdkOptions {
	host?: string;
	port?: number;
	logger?: Pick<Console, "error" | "info" | "warn">;
}

export interface OrchestratorServer {
	runtime: OrchestratorSdkRuntime;
	start(): Promise<void>;
	stop(): Promise<void>;
	getUrl(): string;
}

export interface ProjectInfo {
	id: string;
	cwd: string;
	sessionCount: number;
	recency?: Date;
}

export interface SessionMetadata {
	id: string;
	path: string;
	cwd: string;
	name?: string;
	parentSessionPath?: string;
	created: Date;
	modified: Date;
	messageCount: number;
	firstMessage: string;
}

export interface IngestedSessionEventEnvelope {
	sessionId: string;
	sequence: number;
	emittedAt: string;
	event: unknown;
}

interface IngestedSessionEventRecord extends IngestedSessionEventEnvelope {
	receivedAt: string;
	remoteAddress?: string;
}

function sendJson(response: ServerResponse, statusCode: number, payload: unknown): void {
	response.writeHead(statusCode, { "content-type": "application/json; charset=utf-8" });
	response.end(JSON.stringify(payload));
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}

async function readJsonRequest(request: IncomingMessage, maxBytes = 64 * 1024): Promise<unknown> {
	const chunks: Buffer[] = [];
	let totalBytes = 0;

	for await (const chunk of request) {
		const buffer = typeof chunk === "string" ? Buffer.from(chunk) : chunk;
		totalBytes += buffer.length;
		if (totalBytes > maxBytes) {
			throw new Error(`Request body too large (${totalBytes} bytes)`);
		}
		chunks.push(buffer);
	}

	if (chunks.length === 0) {
		throw new Error("Request body is required");
	}

	return JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
}

function parseIngestEnvelope(sessionId: string, payload: unknown): IngestedSessionEventEnvelope | undefined {
	if (!isRecord(payload)) {
		return undefined;
	}

	const payloadSessionId = payload.sessionId;
	const sequence = payload.sequence;
	const emittedAt = payload.emittedAt;
	const event = payload.event;

	if (payloadSessionId !== undefined && payloadSessionId !== sessionId) {
		return undefined;
	}
	if (typeof sequence !== "number" || !Number.isInteger(sequence) || sequence < 1) {
		return undefined;
	}
	if (typeof emittedAt !== "string" || emittedAt.length === 0) {
		return undefined;
	}
	if (event === undefined) {
		return undefined;
	}

	return {
		sessionId,
		sequence,
		emittedAt,
		event,
	};
}

function getEventType(event: unknown): string | undefined {
	if (!isRecord(event)) {
		return undefined;
	}

	return typeof event.type === "string" ? event.type : undefined;
}

class SessionEventIngestQueue {
	private readonly queue: IngestedSessionEventRecord[] = [];
	private scheduled = false;
	private dropped = 0;

	constructor(
		private readonly logger: Pick<Console, "info" | "warn">,
		private readonly maxSize = 10_000,
		private readonly batchSize = 100,
	) {}

	enqueue(record: IngestedSessionEventRecord): boolean {
		if (this.queue.length >= this.maxSize) {
			this.dropped++;
			if (this.dropped === 1 || this.dropped % 100 === 0) {
				this.logger.warn(
					`Dropping ingested session events (queue full, queued=${this.queue.length}, dropped=${this.dropped})`,
				);
			}
			return false;
		}

		this.queue.push(record);
		this.scheduleDrain();
		return true;
	}

	private scheduleDrain(): void {
		if (this.scheduled) {
			return;
		}

		this.scheduled = true;
		setImmediate(() => {
			this.scheduled = false;
			this.drain();
		});
	}

	private drain(): void {
		const batch = this.queue.splice(0, this.batchSize);
		for (const record of batch) {
			const eventType = getEventType(record.event) ?? "unknown";
			this.logger.info(
				JSON.stringify({
					type: "session_event_ingest",
					sessionId: record.sessionId,
					sequence: record.sequence,
					emittedAt: record.emittedAt,
					receivedAt: record.receivedAt,
					remoteAddress: record.remoteAddress,
					eventType,
					event: record.event,
				}),
			);
		}

		if (this.queue.length > 0) {
			this.scheduleDrain();
		}
	}
}

function normalizeProjectCwd(cwd: string): string {
	let normalized = resolve(cwd);
	if (normalized.length > 1 && normalized.endsWith(sep)) {
		normalized = normalized.slice(0, -sep.length);
	}
	if (process.platform === "win32") {
		normalized = normalized.toLowerCase();
	}
	return normalized;
}

function projectIdFromCwd(cwd: string): string {
	const normalizedCwd = normalizeProjectCwd(cwd);
	const hashPrefix = createHash("sha256").update(normalizedCwd).digest("hex").slice(0, 20);
	return BigInt(`0x${hashPrefix}`).toString(32).padStart(16, "0").slice(0, 16);
}

function toSessionMetadata(session: SessionInfo): SessionMetadata {
	return {
		id: session.id,
		path: session.path,
		cwd: session.cwd,
		name: session.name,
		parentSessionPath: session.parentSessionPath,
		created: session.created,
		modified: session.modified,
		messageCount: session.messageCount,
		firstMessage: session.firstMessage,
	};
}

async function listProjectSessions(cwd: string): Promise<SessionMetadata[]> {
	const sessions = await SessionManager.list(cwd);
	return sessions.map(toSessionMetadata);
}

async function listProjects(projectRoots: string[]): Promise<ProjectInfo[]> {
	const projectCwds = new Set<string>();

	for (const projectRoot of projectRoots) {
		try {
			const entries = await readdir(projectRoot, { withFileTypes: true });
			for (const entry of entries) {
				if (!entry.isDirectory()) {
					continue;
				}
				projectCwds.add(normalizeProjectCwd(join(projectRoot, entry.name)));
			}
		} catch {}
	}

	const projects = await Promise.all(
		Array.from(projectCwds, async (cwd): Promise<ProjectInfo> => {
			const sessions = await listProjectSessions(cwd);
			return {
				id: projectIdFromCwd(cwd),
				cwd,
				sessionCount: sessions.length,
				recency: sessions[0]?.modified,
			};
		}),
	);

	return projects.sort((left, right) => {
		const leftRecency = left.recency?.getTime() ?? 0;
		const rightRecency = right.recency?.getTime() ?? 0;
		if (rightRecency !== leftRecency) {
			return rightRecency - leftRecency;
		}
		if (right.sessionCount !== left.sessionCount) {
			return right.sessionCount - left.sessionCount;
		}
		return left.cwd.localeCompare(right.cwd);
	});
}

async function getProjectById(projectRoots: string[], projectId: string): Promise<ProjectInfo | undefined> {
	const projects = await listProjects(projectRoots);
	return projects.find((project) => project.id === projectId);
}

function createRequestHandler(runtime: OrchestratorSdkRuntime, logger: Pick<Console, "error" | "info" | "warn">) {
	const ingestQueue = new SessionEventIngestQueue(logger);

	return async (request: IncomingMessage, response: ServerResponse) => {
		const requestUrl = new URL(request.url ?? "/", "http://localhost");

		if (request.method === "GET" && requestUrl.pathname === "/up") {
			sendJson(response, 200, {
				status: "ok",
				server: {
					name: "pi-server",
					package: "@mariozechner/pi-server",
					protocolVersion: 1,
					capabilities: {
						sessionEventIngest: true,
					},
				},
				sdk: {
					cwd: runtime.cwd,
					agentDir: runtime.agentDir,
				},
				settings: {
					path: runtime.serverSettings.path,
					projectRoots: runtime.serverSettings.settings.projectRoots,
				},
			});
			return;
		}

		if (request.method === "GET" && requestUrl.pathname === "/projects") {
			const projects = await listProjects(runtime.serverSettings.settings.projectRoots);
			sendJson(response, 200, { projects });
			return;
		}

		const projectSessionsMatch = requestUrl.pathname.match(/^\/projects\/([0-9a-v]{16})\/sessions$/);
		if (request.method === "GET" && projectSessionsMatch) {
			const projectId = projectSessionsMatch[1];
			const project = await getProjectById(runtime.serverSettings.settings.projectRoots, projectId);
			if (!project) {
				sendJson(response, 404, { error: `Project not found: ${projectId}` });
				return;
			}

			const sessions = await listProjectSessions(project.cwd);
			sendJson(response, 200, {
				project,
				sessions,
			});
			return;
		}

		const ingestMatch = requestUrl.pathname.match(/^\/api\/session\/([^/]+)\/ingest$/);
		if (request.method === "POST" && ingestMatch) {
			const sessionId = decodeURIComponent(ingestMatch[1]);

			try {
				const payload = await readJsonRequest(request);
				const envelope = parseIngestEnvelope(sessionId, payload);
				if (!envelope) {
					sendJson(response, 400, { error: "Invalid ingest payload" });
					return;
				}

				const accepted = ingestQueue.enqueue({
					...envelope,
					receivedAt: new Date().toISOString(),
					remoteAddress: request.socket.remoteAddress,
				});

				if (!accepted) {
					sendJson(response, 503, { error: "Ingest queue full" });
					return;
				}

				sendJson(response, 202, { ok: true });
				return;
			} catch (error) {
				const message = error instanceof Error ? error.message : String(error);
				sendJson(response, 400, { error: message });
				return;
			}
		}

		sendJson(response, 404, { error: "Not found" });
	};
}

export function createOrchestratorSdkRuntime(options: OrchestratorSdkOptions = {}): OrchestratorSdkRuntime {
	const cwd = resolve(options.cwd ?? process.cwd());
	const agentDir = options.agentDir ? resolve(options.agentDir) : getAgentDir();
	const authStorage = options.authStorage ?? AuthStorage.create(join(agentDir, "auth.json"));
	const modelRegistry = options.modelRegistry ?? new ModelRegistry(authStorage, join(agentDir, "models.json"));
	const settingsManager = options.settingsManager ?? SettingsManager.create(cwd, agentDir);
	const serverSettings = options.serverSettings
		? {
				path: resolve(options.serverSettingsPath ?? "settings.json"),
				settings: {
					projectRoots: options.serverSettings.projectRoots.map((projectRoot) => resolve(projectRoot)),
					port: options.serverSettings.port,
				},
			}
		: loadServerSettings(options.serverSettingsPath);

	return {
		cwd,
		agentDir,
		authStorage,
		modelRegistry,
		settingsManager,
		serverSettings,
		createSession(sessionManager = SessionManager.inMemory()) {
			return createAgentSession({
				cwd,
				agentDir,
				authStorage,
				modelRegistry,
				settingsManager,
				sessionManager,
			});
		},
	};
}

export function createOrchestratorServer(options: OrchestratorServerOptions = {}): OrchestratorServer {
	const host = options.host ?? "0.0.0.0";
	const logger = options.logger ?? console;
	const runtime = createOrchestratorSdkRuntime(options);
	const port = options.port ?? runtime.serverSettings.settings.port ?? DEFAULT_SERVER_PORT;
	const server = createServer(createRequestHandler(runtime, logger));
	let started = false;

	return {
		runtime,
		async start(): Promise<void> {
			if (started) {
				return;
			}

			await new Promise<void>((resolveStart, rejectStart) => {
				const onError = (error: Error) => {
					server.off("listening", onListening);
					rejectStart(error);
				};
				const onListening = () => {
					server.off("error", onError);
					resolveStart();
				};

				server.once("error", onError);
				server.once("listening", onListening);
				server.listen(port, host);
			});

			started = true;
			logger.info(`pi-server listening on ${host}:${port}`);
		},
		async stop(): Promise<void> {
			if (!started) {
				return;
			}

			await new Promise<void>((resolveStop, rejectStop) => {
				server.close((error) => {
					if (error) {
						rejectStop(error);
						return;
					}
					resolveStop();
				});
			});

			started = false;
		},
		getUrl(): string {
			return `http://${host}:${port}`;
		},
	};
}

export { getServerSettingsPath, loadServerSettings } from "./config.js";
export type { HttpServer };
