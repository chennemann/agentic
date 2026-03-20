/**
 * Agentic Relay Extension
 *
 * Forwards extension lifecycle events to pi-server via HTTP.
 * The target server must already be running and expose the relay handshake via /up.
 *
 * Usage:
 *   pi --extension packages/agentic-relay --server 127.0.0.1:8787
 */

import type { ExtensionAPI, ExtensionContext, ExtensionEvent } from "@mariozechner/pi-coding-agent";

interface SessionEventEnvelope {
	sessionId: string;
	sequence: number;
	emittedAt: string;
	event: ExtensionEvent;
}

interface RelayState {
	serverUrl?: string;
	queue: SessionEventEnvelope[];
	activeRequests: number;
	closed: boolean;
	droppedEvents: number;
	sequenceBySessionId: Map<string, number>;
	verified: boolean;
}

interface ServerHandshake {
	status: string;
	server?: {
		name?: string;
		package?: string;
		protocolVersion?: number;
		capabilities?: {
			sessionEventIngest?: boolean;
		};
	};
}

const RELAY_STATUS_KEY = "agentic-relay";
const REQUEST_TIMEOUT_MS = 5000;
const MAX_QUEUE_SIZE = 1000;
const CONCURRENCY = 4;
const SERVER_PROTOCOL_VERSION = 1;
const SERVER_PACKAGE_NAME = "@mariozechner/pi-server";

function normalizeServerUrl(server: string): string {
	const trimmed = server.trim();
	if (!trimmed) {
		throw new Error("--server requires a non-empty <ip:port> value");
	}

	const urlString = trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : `http://${trimmed}`;
	const url = new URL(urlString);
	return url.origin;
}

function getNextSequence(state: RelayState, sessionId: string): number {
	const next = (state.sequenceBySessionId.get(sessionId) ?? 0) + 1;
	state.sequenceBySessionId.set(sessionId, next);
	return next;
}

function updateStatus(ctx: ExtensionContext, serverUrl: string | undefined): void {
	if (!ctx.hasUI) {
		return;
	}

	if (!serverUrl) {
		ctx.ui.setStatus(RELAY_STATUS_KEY, undefined);
		return;
	}

	ctx.ui.setStatus(RELAY_STATUS_KEY, ctx.ui.theme.fg("accent", `relay:${serverUrl}`));
}

function flush(state: RelayState): void {
	while (
		!state.closed &&
		state.verified &&
		state.serverUrl &&
		state.activeRequests < CONCURRENCY &&
		state.queue.length > 0
	) {
		const envelope = state.queue.shift();
		if (!envelope) {
			return;
		}

		state.activeRequests++;
		void sendEnvelope(state.serverUrl, envelope)
			.catch((error: unknown) => {
				const message = error instanceof Error ? error.message : String(error);
				console.warn(`Failed to forward session event to ${state.serverUrl}: ${message}`);
			})
			.finally(() => {
				state.activeRequests--;
				flush(state);
			});
	}
}

async function sendEnvelope(serverUrl: string, envelope: SessionEventEnvelope): Promise<void> {
	const endpoint = `${serverUrl}/api/session/${encodeURIComponent(envelope.sessionId)}/ingest`;
	const response = await fetch(endpoint, {
		method: "POST",
		headers: {
			"content-type": "application/json; charset=utf-8",
		},
		body: JSON.stringify(envelope),
		signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
	});

	if (!response.ok) {
		throw new Error(`HTTP ${response.status} ${response.statusText}`);
	}
}

function enqueueEvent(state: RelayState, event: ExtensionEvent, ctx: ExtensionContext): void {
	const serverUrl = state.serverUrl;
	if (!serverUrl || state.closed || !state.verified) {
		return;
	}

	if (state.queue.length >= MAX_QUEUE_SIZE) {
		state.droppedEvents++;
		if (state.droppedEvents === 1 || state.droppedEvents % 100 === 0) {
			console.warn(
				`Dropping relayed session events for ${serverUrl} (queue full, dropped=${state.droppedEvents}, session=${ctx.sessionManager.getSessionId()})`,
			);
		}
		return;
	}

	const sessionId = ctx.sessionManager.getSessionId();
	state.queue.push({
		sessionId,
		sequence: getNextSequence(state, sessionId),
		emittedAt: new Date().toISOString(),
		event,
	});
	flush(state);
}

function isValidServerHandshake(payload: unknown): payload is ServerHandshake {
	if (!payload || typeof payload !== "object") {
		return false;
	}

	const handshake = payload as ServerHandshake;
	return (
		handshake.status === "ok" &&
		handshake.server?.package === SERVER_PACKAGE_NAME &&
		handshake.server.protocolVersion === SERVER_PROTOCOL_VERSION &&
		handshake.server.capabilities?.sessionEventIngest === true
	);
}

async function fetchServerHandshake(serverUrl: string): Promise<ServerHandshake> {
	const response = await fetch(`${serverUrl}/up`, {
		headers: {
			accept: "application/json",
		},
		signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
	});

	if (!response.ok) {
		throw new Error(`HTTP ${response.status} ${response.statusText}`);
	}

	const payload = (await response.json()) as unknown;
	if (!isValidServerHandshake(payload)) {
		throw new Error(`Server at ${serverUrl} does not expose the required pi-server relay capabilities`);
	}

	return payload;
}

export default function agenticRelayExtension(pi: ExtensionAPI) {
	pi.registerFlag("server", {
		description: "Forward extension event stream to pi-server ingest endpoint",
		type: "string",
	});

	const state: RelayState = {
		queue: [],
		activeRequests: 0,
		closed: false,
		droppedEvents: 0,
		sequenceBySessionId: new Map<string, number>(),
		verified: false,
	};

	async function configureServer(ctx: ExtensionContext): Promise<void> {
		const flag = pi.getFlag("server");
		state.serverUrl = typeof flag === "string" && flag.trim().length > 0 ? normalizeServerUrl(flag) : undefined;
		state.verified = false;
		updateStatus(ctx, state.serverUrl);

		if (!state.serverUrl) {
			return;
		}

		await fetchServerHandshake(state.serverUrl);
		state.verified = true;

		const message = `Forwarding session events to ${state.serverUrl}`;
		if (ctx.hasUI) {
			ctx.ui.notify(message, "info");
		} else {
			console.error(message);
		}
		flush(state);
	}

	pi.on("session_start", async (_event, ctx) => {
		await configureServer(ctx);
	});

	pi.on("session_switch", async (_event, ctx) => {
		updateStatus(ctx, state.serverUrl);
	});

	pi.on("session_shutdown", async () => {
		state.closed = true;
		state.queue.length = 0;
	});

	pi.on("agent_start", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
	pi.on("agent_end", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
	pi.on("turn_start", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
	pi.on("turn_end", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
	pi.on("message_start", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
	pi.on("message_update", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
	pi.on("message_end", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
	pi.on("tool_execution_start", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
	pi.on("tool_execution_update", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
	pi.on("tool_execution_end", async (event, ctx) => {
		enqueueEvent(state, event, ctx);
	});
}
