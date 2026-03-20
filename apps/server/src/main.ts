#!/usr/bin/env node

import { createOrchestratorServer, getServerSettingsPath } from "./index.js";

function parsePort(value: string | undefined): number {
	if (!value) {
		return 8787;
	}

	const port = Number.parseInt(value, 10);
	if (!Number.isInteger(port) || port < 1 || port > 65535) {
		throw new Error(`Invalid port: ${value}`);
	}

	return port;
}

const server = createOrchestratorServer({
	host: process.env.PI_SERVER_HOST ?? "0.0.0.0",
	port: parsePort(process.env.PI_SERVER_PORT),
});

let stopping = false;

async function shutdown(signal: string): Promise<void> {
	if (stopping) {
		return;
	}

	stopping = true;
	console.info(`Received ${signal}, shutting down pi-server`);
	await server.stop();
	process.exit(0);
}

process.on("SIGINT", () => {
	void shutdown("SIGINT");
});

process.on("SIGTERM", () => {
	void shutdown("SIGTERM");
});

await server.start();
console.info(`Using server settings from ${getServerSettingsPath()}`);
