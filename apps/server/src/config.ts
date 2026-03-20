import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve } from "node:path";

export interface ServerSettings {
	projectRoots: string[];
}

export interface LoadedServerSettings {
	path: string;
	settings: ServerSettings;
}

function isStringArray(value: unknown): value is string[] {
	return Array.isArray(value) && value.every((entry) => typeof entry === "string");
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

	return {
		path: resolvedPath,
		settings: {
			projectRoots: (projectRootsValue ?? []).map((projectRoot) => resolve(projectRoot)),
		},
	};
}
