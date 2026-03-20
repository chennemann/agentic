# pi agentic relay extension

Forwards pi extension lifecycle events to `pi-server` over HTTP.
The target server must already be running, and the extension verifies a relay capability fingerprint via `/up` before sending events.

## Usage

Start the server:

```bash
npm run build:server
node packages/server/dist/main.js
# or
npx pi-server
```

Run pi with the extension package and server flag:

```bash
pi --extension packages/agentic-relay --server 127.0.0.1:8787
```

The extension registers `--server <ip:port>` and forwards events to:

```text
GET /up
```

It first verifies that the server responds with the expected `@mariozechner/pi-server` fingerprint and `sessionEventIngest` capability.

Then it forwards events to:

```text
http://127.0.0.1:8787/api/session/<sessionId>/ingest
```

The relay is fire-and-forget:
- bounded in-memory queue
- limited request concurrency
- request timeout
- drop-on-overflow warnings

Currently the server just logs ingested events.
