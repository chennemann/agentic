import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve } from "node:path";

export const DEFAULT_SERVER_PORT = 8787;

export interface ServerSettings {
	projectRoots: string[];
	port: number;
}

export interface LoadedServerSettings {
	path: string;
	settings: ServerSettings;
}

function isStringArray(value: unknown): value is string[] {
	return Array.isArray(value) && value.every((entry) => typeof entry === "string");
}

function parsePort(value: unknown, settingsPath: string): number {
	if (value === undefined) {
		return DEFAULT_SERVER_PORT;
	}
	if (typeof value !== "number" || !Number.isInteger(value) || value < 1 || value > 65535) {
		throw new Error(`Invalid server settings in ${settingsPath}: port must be an integer between 1 and 65535`);
	}
	return value;
}

export function getServerSettingsPath(): string {
	return join(homedir(), ".pi", "server", "settings.json");
}

export function loadServerSettings(settingsPath = getServerSettingsPath()): LoadedServerSettings {
	const resolvedPath = resolve(settingsPath);
	if (!existsSync(resolvedPath)) {
		return {
			path: resolvedPath,
			settings: {
				projectRoots: [],
				port: DEFAULT_SERVER_PORT,
			},
		};
	}

	const content = readFileSync(resolvedPath, "utf8");
	const parsed = JSON.parse(content) as unknown;

	if (!parsed || typeof parsed !== "object") {
		throw new Error(`Invalid server settings in ${resolvedPath}: expected JSON object`);
	}

	const projectRootsValue = (parsed as { projectRoots?: unknown }).projectRoots;
	if (projectRootsValue !== undefined && !isStringArray(projectRootsValue)) {
		throw new Error(`Invalid server settings in ${resolvedPath}: projectRoots must be an array of strings`);
	}
	const portValue = (parsed as { port?: unknown }).port;

	return {
		path: resolvedPath,
		settings: {
			projectRoots: (projectRootsValue ?? []).map((projectRoot) => resolve(projectRoot)),
			port: parsePort(portValue, resolvedPath),
		},
	};
}
