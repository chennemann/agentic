import { createHash, randomUUID } from "node:crypto";
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
import {
	DEFAULT_SERVER_HOST,
	DEFAULT_SERVER_PORT,
	type LoadedServerSettings,
	loadServerSettings,
	type ServerSettings,
} from "./config.js";

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
	name: string;
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

interface ServerProject {
	id: string;
	cwd: string;
	name: string;
	sessionCount: number;
	lastActivityAt?: string;
}

interface ServerSession {
	id: string;
	projectId: string;
	cwd: string;
	title: string;
	parentSessionId: string | null;
	status: "idle" | "busy";
	updatedAt: string;
	archivedAt: string | null;
	messageCount: number;
	path?: string;
	createdAt?: string;
}

interface ServerMessage {
	id: string;
	sessionId: string;
	role: "user" | "assistant";
	text: string;
	createdAt: string;
	completedAt: string | null;
	raw?: unknown;
}

interface EventRecord {
	id: string;
	type: string;
	envelope: IngestedSessionEventEnvelope;
}

interface EventSubscriber {
	id: string;
	response: ServerResponse;
	sessionId?: string;
}

interface MockSessionPromptRequest {
	text?: unknown;
	mode?: unknown;
}

interface MockSessionCreateRequest {
	projectId?: unknown;
	cwd?: unknown;
	title?: unknown;
}

interface MockSessionUpdateRequest {
	title?: unknown;
	archived?: unknown;
}

function sendJson(response: ServerResponse, statusCode: number, payload: unknown): void {
	response.writeHead(statusCode, { "content-type": "application/json; charset=utf-8" });
	response.end(JSON.stringify(payload));
}

function sendError(response: ServerResponse, statusCode: number, code: string, message: string): void {
	sendJson(response, statusCode, {
		error: {
			code,
			message,
		},
	});
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

function getEventType(event: unknown): string {
	if (!isRecord(event)) {
		return "unknown";
	}

	return typeof event.type === "string" && event.type.length > 0 ? event.type : "unknown";
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
				name: folderName(cwd),
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

function folderName(path: string): string {
	const value = path.trim().replace(/[\\/]+$/, "");
	if (value.length === 0) {
		return path;
	}
	const index = Math.max(value.lastIndexOf("/"), value.lastIndexOf("\\"));
	return index >= 0 ? value.slice(index + 1) || value : value;
}

class EventHub {
	private readonly records: EventRecord[] = [];
	private readonly subscribers = new Map<string, EventSubscriber>();
	private nextId = 1;

	constructor(private readonly logger: Pick<Console, "info" | "warn">, private readonly maxSize = 1000) {}

	publish(envelope: IngestedSessionEventEnvelope): EventRecord {
		const record: EventRecord = {
			id: String(this.nextId++),
			type: getEventType(envelope.event),
			envelope,
		};
		this.records.push(record);
		if (this.records.length > this.maxSize) {
			this.records.splice(0, this.records.length - this.maxSize);
		}
		for (const subscriber of this.subscribers.values()) {
			if (subscriber.sessionId && subscriber.sessionId !== envelope.sessionId) {
				continue;
			}
			writeSseEvent(subscriber.response, record);
		}
		return record;
	}

	subscribe(response: ServerResponse, options: { lastEventId?: string | null; sessionId?: string }): () => void {
		response.writeHead(200, {
			"content-type": "text/event-stream; charset=utf-8",
			"cache-control": "no-cache, no-transform",
			connection: "keep-alive",
		});
		response.write(": connected\n\n");
		const subscriber: EventSubscriber = {
			id: randomUUID(),
			response,
			sessionId: options.sessionId,
		};
		this.subscribers.set(subscriber.id, subscriber);
		const replay = this.replay(options.lastEventId ?? undefined, options.sessionId);
		for (const record of replay) {
			writeSseEvent(response, record);
		}
		const heartbeat = setInterval(() => {
			response.write(": keepalive\n\n");
		}, 15_000);
		return () => {
			clearInterval(heartbeat);
			this.subscribers.delete(subscriber.id);
		};
	}

	private replay(lastEventId: string | undefined, sessionId: string | undefined): EventRecord[] {
		const lastId = lastEventId ? Number.parseInt(lastEventId, 10) : Number.NaN;
		return this.records.filter((record) => {
			if (sessionId && record.envelope.sessionId !== sessionId) {
				return false;
			}
			if (Number.isNaN(lastId)) {
				return true;
			}
			const value = Number.parseInt(record.id, 10);
			return value > lastId;
		});
	}
}

function writeSseEvent(response: ServerResponse, record: EventRecord): void {
	response.write(`id: ${record.id}\n`);
	response.write(`event: ${record.type}\n`);
	const json = JSON.stringify(record.envelope);
	for (const line of json.split(/\r?\n/)) {
		response.write(`data: ${line}\n`);
	}
	response.write("\n");
}

class SessionEventIngestQueue {
	private readonly queue: IngestedSessionEventRecord[] = [];
	private scheduled = false;
	private dropped = 0;

	constructor(
		private readonly logger: Pick<Console, "info" | "warn">,
		private readonly onEvent: (record: IngestedSessionEventRecord) => void,
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
			this.logger.info(
				JSON.stringify({
					type: "session_event_ingest",
					sessionId: record.sessionId,
					sequence: record.sequence,
					emittedAt: record.emittedAt,
					receivedAt: record.receivedAt,
					remoteAddress: record.remoteAddress,
					eventType: getEventType(record.event),
					event: record.event,
				}),
			);
			this.onEvent(record);
		}
		if (this.queue.length > 0) {
			this.scheduleDrain();
		}
	}
}

class MockState {
	private readonly projects = new Map<string, ServerProject>();
	private readonly sessions = new Map<string, ServerSession>();
	private readonly sessionMessages = new Map<string, ServerMessage[]>();
	private readonly sequenceBySessionId = new Map<string, number>();

	constructor(
		private readonly logger: Pick<Console, "info" | "warn">,
		private readonly eventHub: EventHub,
	) {}

	async refreshFromDiscovery(projectRoots: string[]): Promise<void> {
		const discoveredProjects = await listProjects(projectRoots);
		const knownProjectIds = new Set<string>();
		for (const project of discoveredProjects) {
			knownProjectIds.add(project.id);
			this.projects.set(project.id, {
				id: project.id,
				cwd: project.cwd,
				name: project.name,
				sessionCount: project.sessionCount,
				lastActivityAt: project.recency?.toISOString(),
			});
			const sessions = await listProjectSessions(project.cwd);
			for (const session of sessions) {
				if (this.sessions.has(session.id)) {
					continue;
				}
				this.sessions.set(session.id, {
					id: session.id,
					projectId: project.id,
					cwd: project.cwd,
					title: session.name || session.firstMessage || folderName(project.cwd),
					parentSessionId: session.parentSessionPath ?? null,
					status: "idle",
					updatedAt: session.modified.toISOString(),
					archivedAt: null,
					messageCount: session.messageCount,
					path: session.path,
					createdAt: session.created.toISOString(),
				});
				if (!this.sessionMessages.has(session.id)) {
					this.sessionMessages.set(session.id, []);
				}
			}
		}
		for (const [projectId, project] of this.projects) {
			if (!knownProjectIds.has(projectId)) {
				this.projects.set(projectId, project);
			}
		}
		this.recomputeProjectCounters();
	}

	listProjects(): ServerProject[] {
		return Array.from(this.projects.values()).sort((left, right) => {
			const leftActivity = left.lastActivityAt ? Date.parse(left.lastActivityAt) : 0;
			const rightActivity = right.lastActivityAt ? Date.parse(right.lastActivityAt) : 0;
			if (rightActivity !== leftActivity) {
				return rightActivity - leftActivity;
			}
			return left.cwd.localeCompare(right.cwd);
		});
	}

	getProject(projectId: string): ServerProject | undefined {
		return this.projects.get(projectId);
	}

	listSessions(projectId: string): ServerSession[] {
		return Array.from(this.sessions.values())
			.filter((session) => session.projectId === projectId)
			.sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
	}

	getSession(sessionId: string): ServerSession | undefined {
		return this.sessions.get(sessionId);
	}

	listMessages(sessionId: string): ServerMessage[] {
		return [...(this.sessionMessages.get(sessionId) ?? [])].sort(
			(left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt),
		);
	}

	createSession(input: { projectId?: string; cwd?: string; title?: string }): ServerSession | undefined {
		const project = input.projectId ? this.projects.get(input.projectId) : this.findProjectByCwd(input.cwd);
		if (!project) {
			return undefined;
		}
		const now = new Date().toISOString();
		const session: ServerSession = {
			id: `mock-session-${randomUUID()}`,
			projectId: project.id,
			cwd: project.cwd,
			title: input.title?.trim() || `Session ${new Date().toLocaleString()}`,
			parentSessionId: null,
			status: "idle",
			updatedAt: now,
			archivedAt: null,
			messageCount: 0,
			createdAt: now,
		};
		this.sessions.set(session.id, session);
		this.sessionMessages.set(session.id, []);
		this.recomputeProjectCounters();
		return session;
	}

	updateSession(sessionId: string, input: { title?: string; archived?: boolean }): ServerSession | undefined {
		const existing = this.sessions.get(sessionId);
		if (!existing) {
			return undefined;
		}
		const title = input.title?.trim();
		const updated: ServerSession = {
			...existing,
			title: title && title.length > 0 ? title : existing.title,
			archivedAt: input.archived === undefined ? existing.archivedAt : input.archived ? new Date().toISOString() : null,
			updatedAt: new Date().toISOString(),
		};
		this.sessions.set(updated.id, updated);
		this.recomputeProjectCounters();
		return updated;
	}

	async appendPrompt(sessionId: string, text: string, mode?: string): Promise<{ userMessage: ServerMessage }> {
		const session = this.sessions.get(sessionId);
		if (!session) {
			throw new Error(`Unknown session: ${sessionId}`);
		}
		const now = new Date().toISOString();
		const userMessage: ServerMessage = {
			id: `mock-message-${randomUUID()}`,
			sessionId,
			role: "user",
			text,
			createdAt: now,
			completedAt: now,
			raw: {
				id: `entry-${randomUUID()}`,
				role: "user",
				content: [{ type: "text", text }],
			},
		};
		this.pushMessage(sessionId, userMessage);
		this.publishRawEvent(sessionId, {
			type: "message_start",
			message: userMessage.raw,
		});
		this.publishRawEvent(sessionId, {
			type: "message_end",
			message: userMessage.raw,
		});
		this.setSessionStatus(sessionId, "busy");
		await this.streamAssistantResponse(session, text, mode);
		return { userMessage };
	}

	ingestEnvelope(envelope: IngestedSessionEventEnvelope): void {
		const current = this.sequenceBySessionId.get(envelope.sessionId) ?? 0;
		if (envelope.sequence > current) {
			this.sequenceBySessionId.set(envelope.sessionId, envelope.sequence);
		}
		this.eventHub.publish(envelope);
	}

	private async streamAssistantResponse(session: ServerSession, text: string, mode?: string): Promise<void> {
		const assistantText = buildMockAssistantReply(text, mode);
		const now = new Date().toISOString();
		const assistantMessage: ServerMessage = {
			id: `mock-message-${randomUUID()}`,
			sessionId: session.id,
			role: "assistant",
			text: assistantText,
			createdAt: now,
			completedAt: null,
			raw: {
				id: `entry-${randomUUID()}`,
				role: "assistant",
				content: [{ type: "text", text: "" }],
			},
		};
		this.pushMessage(session.id, assistantMessage);
		this.publishRawEvent(session.id, {
			type: "agent_start",
		});
		this.publishRawEvent(session.id, {
			type: "message_start",
			message: assistantMessage.raw,
		});
		for (const chunk of chunkText(assistantText, 24)) {
			const raw = isRecord(assistantMessage.raw) ? assistantMessage.raw : {};
			const content = Array.isArray(raw.content) ? [...raw.content] : [];
			const first = isRecord(content[0]) ? { ...content[0] } : { type: "text", text: "" };
			first.text = `${typeof first.text === "string" ? first.text : ""}${chunk}`;
			assistantMessage.raw = {
				...raw,
				content: [first],
			};
			assistantMessage.text += "";
			this.publishRawEvent(session.id, {
				type: "message_update",
				message: assistantMessage.raw,
				assistantMessageEvent: {
					type: "delta",
					delta: chunk,
				},
			});
			await delay(40);
		}
		assistantMessage.completedAt = new Date().toISOString();
		this.publishRawEvent(session.id, {
			type: "message_end",
			message: assistantMessage.raw,
		});
		this.publishRawEvent(session.id, {
			type: "agent_end",
			messages: [assistantMessage.raw],
		});
		this.setSessionStatus(session.id, "idle");
		this.touchSession(session.id);
	}

	private pushMessage(sessionId: string, message: ServerMessage): void {
		const list = this.sessionMessages.get(sessionId) ?? [];
		list.push(message);
		this.sessionMessages.set(sessionId, list);
		this.touchSession(sessionId);
	}

	private setSessionStatus(sessionId: string, status: "idle" | "busy"): void {
		const session = this.sessions.get(sessionId);
		if (!session) {
			return;
		}
		this.sessions.set(sessionId, {
			...session,
			status,
			updatedAt: new Date().toISOString(),
		});
		this.recomputeProjectCounters();
	}

	private touchSession(sessionId: string): void {
		const session = this.sessions.get(sessionId);
		if (!session) {
			return;
		}
		const messages = this.sessionMessages.get(sessionId) ?? [];
		this.sessions.set(sessionId, {
			...session,
			updatedAt: new Date().toISOString(),
			messageCount: messages.length,
		});
		this.recomputeProjectCounters();
	}

	private publishRawEvent(sessionId: string, event: unknown): void {
		const envelope: IngestedSessionEventEnvelope = {
			sessionId,
			sequence: (this.sequenceBySessionId.get(sessionId) ?? 0) + 1,
			emittedAt: new Date().toISOString(),
			event,
		};
		this.sequenceBySessionId.set(sessionId, envelope.sequence);
		this.eventHub.publish(envelope);
	}

	private findProjectByCwd(cwd: string | undefined): ServerProject | undefined {
		if (!cwd) {
			return undefined;
		}
		const normalized = normalizeProjectCwd(cwd);
		return Array.from(this.projects.values()).find((project) => normalizeProjectCwd(project.cwd) === normalized);
	}

	private recomputeProjectCounters(): void {
		for (const project of this.projects.values()) {
			const sessions = Array.from(this.sessions.values()).filter((session) => session.projectId === project.id);
			const lastActivity = sessions
				.map((session) => Date.parse(session.updatedAt))
				.filter((value) => Number.isFinite(value))
				.sort((left, right) => right - left)[0];
			this.projects.set(project.id, {
				...project,
				sessionCount: sessions.length,
				lastActivityAt: lastActivity ? new Date(lastActivity).toISOString() : project.lastActivityAt,
			});
		}
	}
}

function buildMockAssistantReply(text: string, mode: string | undefined): string {
	const trimmed = text.trim();
	const prefix = mode ? `Mock ${mode} response` : "Mock response";
	if (trimmed.length === 0) {
		return `${prefix}: ready.`;
	}
	return `${prefix}: ${trimmed}`;
}

function chunkText(value: string, size: number): string[] {
	const chunks: string[] = [];
	for (let index = 0; index < value.length; index += size) {
		chunks.push(value.slice(index, index + size));
	}
	return chunks.length > 0 ? chunks : [value];
}

function delay(ms: number): Promise<void> {
	return new Promise((resolveDelay) => {
		setTimeout(resolveDelay, ms);
	});
}

function serializeProject(project: ServerProject): ServerProject {
	return { ...project };
}

function serializeSession(session: ServerSession): ServerSession {
	return { ...session };
}

function serializeMessage(message: ServerMessage): ServerMessage {
	return { ...message };
}

function createRequestHandler(runtime: OrchestratorSdkRuntime, logger: Pick<Console, "error" | "info" | "warn">) {
	const eventHub = new EventHub(logger);
	const state = new MockState(logger, eventHub);
	const ingestQueue = new SessionEventIngestQueue(logger, (record) => {
		state.ingestEnvelope({
			sessionId: record.sessionId,
			sequence: record.sequence,
			emittedAt: record.emittedAt,
			event: record.event,
		});
	});

	return async (request: IncomingMessage, response: ServerResponse) => {
		await state.refreshFromDiscovery(runtime.serverSettings.settings.projectRoots);
		const requestUrl = new URL(request.url ?? "/", "http://localhost");

		if (request.method === "GET" && requestUrl.pathname === "/up") {
			sendJson(response, 200, {
				status: "ok",
				server: {
					name: "pi-server",
					package: "@mariozechner/pi-server",
					protocolVersion: 1,
					implementation: "mock",
					version: "0.1.0",
					capabilities: {
						sessionEventIngest: true,
					},
				},
				capabilities: {
					"projects.list": true,
					"sessions.list": true,
					"sessions.get": true,
					"sessions.create": true,
					"sessions.update": true,
					"messages.list": true,
					"messages.create": true,
					"events.stream": true,
					"events.replay": true,
					"events.ingest": true,
				},
				sdk: {
					cwd: runtime.cwd,
					agentDir: runtime.agentDir,
				},
				settings: {
					path: runtime.serverSettings.path,
					host: runtime.serverSettings.settings.host,
					projectRoots: runtime.serverSettings.settings.projectRoots,
					port: runtime.serverSettings.settings.port,
				},
			});
			return;
		}

		if (request.method === "GET" && requestUrl.pathname === "/projects") {
			sendJson(response, 200, {
				projects: state.listProjects().map(serializeProject),
			});
			return;
		}

		const projectSessionsMatch = requestUrl.pathname.match(/^\/projects\/([0-9a-v]{16})\/sessions$/);
		if (request.method === "GET" && projectSessionsMatch) {
			const projectId = projectSessionsMatch[1];
			const project = state.getProject(projectId);
			if (!project) {
				sendError(response, 404, "not_found", `Project not found: ${projectId}`);
				return;
			}
			sendJson(response, 200, {
				project: serializeProject(project),
				sessions: state.listSessions(projectId).map(serializeSession),
			});
			return;
		}

		if (request.method === "GET" && requestUrl.pathname === "/events") {
			const release = eventHub.subscribe(response, {
				lastEventId: request.headers["last-event-id"]?.toString(),
				sessionId: requestUrl.searchParams.get("sessionId") ?? undefined,
			});
			request.on("close", release);
			response.on("close", release);
			return;
		}

		const sessionMatch = requestUrl.pathname.match(/^\/sessions\/([^/]+)$/);
		if (request.method === "GET" && sessionMatch) {
			const sessionId = decodeURIComponent(sessionMatch[1]);
			const session = state.getSession(sessionId);
			if (!session) {
				sendError(response, 404, "not_found", `Session not found: ${sessionId}`);
				return;
			}
			sendJson(response, 200, { session: serializeSession(session) });
			return;
		}

		const sessionMessagesMatch = requestUrl.pathname.match(/^\/sessions\/([^/]+)\/messages$/);
		if (request.method === "GET" && sessionMessagesMatch) {
			const sessionId = decodeURIComponent(sessionMessagesMatch[1]);
			const session = state.getSession(sessionId);
			if (!session) {
				sendError(response, 404, "not_found", `Session not found: ${sessionId}`);
				return;
			}
			sendJson(response, 200, {
				session: serializeSession(session),
				messages: state.listMessages(sessionId).map(serializeMessage),
			});
			return;
		}

		if (request.method === "POST" && requestUrl.pathname === "/sessions") {
			try {
				const payload = (await readJsonRequest(request)) as MockSessionCreateRequest;
				const projectId = typeof payload.projectId === "string" ? payload.projectId : undefined;
				const cwd = typeof payload.cwd === "string" ? payload.cwd : undefined;
				const title = typeof payload.title === "string" ? payload.title : undefined;
				const session = state.createSession({ projectId, cwd, title });
				if (!session) {
					sendError(response, 404, "not_found", "Project not found for session creation");
					return;
				}
				sendJson(response, 201, { session: serializeSession(session) });
				return;
			} catch (error) {
				sendError(response, 400, "bad_request", error instanceof Error ? error.message : String(error));
				return;
			}
		}

		if (request.method === "PATCH" && sessionMatch) {
			const sessionId = decodeURIComponent(sessionMatch[1]);
			try {
				const payload = (await readJsonRequest(request)) as MockSessionUpdateRequest;
				const title = typeof payload.title === "string" ? payload.title : undefined;
				const archived = typeof payload.archived === "boolean" ? payload.archived : undefined;
				const session = state.updateSession(sessionId, { title, archived });
				if (!session) {
					sendError(response, 404, "not_found", `Session not found: ${sessionId}`);
					return;
				}
				sendJson(response, 200, { session: serializeSession(session) });
				return;
			} catch (error) {
				sendError(response, 400, "bad_request", error instanceof Error ? error.message : String(error));
				return;
			}
		}

		if (request.method === "POST" && sessionMessagesMatch) {
			const sessionId = decodeURIComponent(sessionMessagesMatch[1]);
			try {
				const payload = (await readJsonRequest(request)) as MockSessionPromptRequest;
				const text = typeof payload.text === "string" ? payload.text.trim() : "";
				const mode = typeof payload.mode === "string" ? payload.mode.trim() : undefined;
				if (text.length === 0) {
					sendError(response, 400, "bad_request", "text must not be blank");
					return;
				}
				const result = await state.appendPrompt(sessionId, text, mode);
				sendJson(response, 202, {
					accepted: true,
					userMessage: serializeMessage(result.userMessage),
				});
				return;
			} catch (error) {
				const message = error instanceof Error ? error.message : String(error);
				const code = message.startsWith("Unknown session:") ? 404 : 400;
				sendError(response, code, code === 404 ? "not_found" : "bad_request", message);
				return;
			}
		}

		const ingestMatch = requestUrl.pathname.match(/^\/api\/session\/([^/]+)\/ingest$/);
		if (request.method === "POST" && ingestMatch) {
			const sessionId = decodeURIComponent(ingestMatch[1]);
			try {
				const payload = await readJsonRequest(request);
				const envelope = parseIngestEnvelope(sessionId, payload);
				if (!envelope) {
					sendError(response, 400, "bad_request", "Invalid ingest payload");
					return;
				}
				const accepted = ingestQueue.enqueue({
					...envelope,
					receivedAt: new Date().toISOString(),
					remoteAddress: request.socket.remoteAddress,
				});
				if (!accepted) {
					sendError(response, 503, "conflict", "Ingest queue full");
					return;
				}
				sendJson(response, 202, { ok: true });
				return;
			} catch (error) {
				sendError(response, 400, "bad_request", error instanceof Error ? error.message : String(error));
				return;
			}
		}

		sendError(response, 404, "not_found", "Not found");
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
					host: options.serverSettings.host,
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
	const logger = options.logger ?? console;
	const runtime = createOrchestratorSdkRuntime(options);
	const host = options.host ?? runtime.serverSettings.settings.host ?? DEFAULT_SERVER_HOST;
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
