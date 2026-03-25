# @mariozechner/pi-server

Minimal orchestrator server package for remote control of pi agents.

Current scope:
- start an HTTP server bound to the network
- initialize shared pi SDK state
- load server settings from `%HOME%/.pi/server/settings.json`
- expose uptime, project discovery, session, and message endpoints
- provide a mock prompt flow for Android integration
- ingest forwarded pi session events over HTTP and replay them over SSE without changing the streamed envelope shape

Not implemented yet:
- authentication/authorization
- autostart/service installation
- persistent mock storage

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

The server host and port can be configured in `%HOME%/.pi/server/settings.json`.
Environment variables `PI_SERVER_HOST` and `PI_SERVER_PORT` still override the configured values.

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
  "host": "0.0.0.0",
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
  "host": "0.0.0.0",
  "port": 8787,
  "projectRoots": []
}
```

`host` configures the HTTP bind address used by `pi-server`. Set it to your machine's LAN IP (for example `192.168.1.10`) if you want other devices on the network to connect using that address.

`port` configures the HTTP port used by `pi-server` and is also the default port used by the Agentic relay extension when `--server` is not passed.

`projectRoots` is used to discover projects. Each immediate subdirectory of each configured root is returned as a project with:
- `cwd`: normalized absolute path
- `id`: deterministic 16-character identifier derived from the cwd
- `sessionCount`: number of existing sessions for that project
- `recency`: modified timestamp of the most recent session for that project, if any

## Endpoints

- `GET /up` - server status, capabilities, SDK runtime metadata, loaded server settings, and relay capability fingerprint
- `GET /projects` - discovered projects and mock project metadata
- `GET /projects/:id/sessions` - sessions for the project identified by `id`
- `GET /sessions/:id` - one session by id
- `GET /sessions/:id/messages` - messages for a session
- `POST /sessions` - create a mock session for a discovered project (`projectId` or `cwd`)
- `PATCH /sessions/:id` - rename or archive a mock session
- `POST /sessions/:id/messages` - append a user message and emit a mock assistant response
- `GET /events` - server-sent events; replays buffered session event envelopes and streams new ones as-is
- `POST /api/session/:id/ingest` - accepts forwarded pi session events and pushes them into the live/replay event stream

## Forwarding from pi

Start `pi-server`, then run `pi` with the relay extension package:

```bash
pi --extension apps/agentic-relay
```

To override the configured/default server address explicitly:

```bash
pi --extension apps/agentic-relay --server 127.0.0.1:8787
```

The extension registers the `--server <ip:port>` flag, starts the bundled server automatically for local targets when needed, verifies the relay capability fingerprint via `/up`, and forwards each session event to the configured host and port. If the configured host is `0.0.0.0`, the relay uses `127.0.0.1` when connecting from the same machine.

```text
http://<configured-host>:<configured-port>/api/session/<sessionId>/ingest
```

The client forwarding is fire-and-forget. The server accepts events, pushes them into an in-memory queue, logs them as JSON lines, and republishes the same `{ sessionId, sequence, emittedAt, event }` envelope over `GET /events` using SSE.
