# @mariozechner/pi-server

Minimal orchestrator server package for remote control of pi agents.

Current scope:
- start an HTTP server bound to the network
- initialize shared pi SDK state
- load server settings from `%HOME%/.pi/server/settings.json`
- expose uptime and project discovery endpoints
- ingest forwarded pi session events over HTTP and log them asynchronously

Not implemented yet:
- remote prompt/session control
- authentication/authorization
- autostart/service installation

## Run

Development with live reload:

```bash
cd apps/server
npm run dev
```

Build once:

```bash
cd apps/server
npm run build
```

Default bind address:
- host: `0.0.0.0`
- port: `8787`

`npm run dev` restarts the server when files imported by `src/main.ts` change.

The server port can be configured in `%HOME%/.pi/server/settings.json`.
Environment variable `PI_SERVER_PORT` still overrides the configured port.

Override with environment variables:

```bash
PI_SERVER_HOST=0.0.0.0
PI_SERVER_PORT=8787
```

## Configuration

Settings file:
- `%HOME%/.pi/server/settings.json`

Current supported settings:

```json
{
  "port": 8787,
  "projectRoots": [
    "/path/to/projects",
    "/another/path/to/projects"
  ]
}
```

If the file does not exist, the server uses:

```json
{
  "port": 8787,
  "projectRoots": []
}
```

`port` configures the HTTP port used by `pi-server` and is also the default port used by the Agentic relay extension when `--server` is not passed.

`projectRoots` is used to discover projects. Each immediate subdirectory of each configured root is returned as a project with:
- `cwd`: normalized absolute path
- `id`: deterministic 16-character identifier derived from the cwd
- `sessionCount`: number of existing sessions for that project
- `recency`: modified timestamp of the most recent session for that project, if any

## Endpoints

- `GET /up` - server status, SDK runtime metadata, loaded server settings, and relay capability fingerprint
- `GET /projects` - `{ id, cwd, sessionCount }` for each immediate subdirectory in each configured project root
- `GET /projects/:id/sessions` - session metadata for the project identified by `id`
- `POST /api/session/:id/ingest` - accepts forwarded session events and queues them for asynchronous logging

## Forwarding from pi

Start `pi-server`, then run `pi` with the relay extension package:

```bash
pi --extension apps/agentic-relay
```

To override the configured/default server address explicitly:

```bash
pi --extension apps/agentic-relay --server 127.0.0.1:8787
```

The extension registers the `--server <ip:port>` flag, starts the bundled server automatically for local targets when needed, verifies the relay capability fingerprint via `/up`, and forwards each session event to:

```text
http://127.0.0.1:8787/api/session/<sessionId>/ingest
```

The client forwarding is fire-and-forget. The server accepts events, pushes them into an in-memory queue, and currently logs them as JSON lines.
