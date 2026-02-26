# Streaming Markdown

Compose-specific streaming markdown rendering module for mobile.

## Scope (v1)

- Stream-safe inline code rendering based on backticks.
- Compose adapter that produces `AnnotatedString` output.
- Drop-in composable API via `StreamingMarkdownText`.

## Non-goals (v1)

- Full markdown specification support.
- Tables and nested markdown structures.
- Rich text editor behavior.

## Planned API shape

- Parser lifecycle methods for chunked input (`start`, `write`, `end`, `reset`).
- Parser output runs for plain text and inline code segments.
- Compose adapter from runs to `AnnotatedString`.
- `StreamingMarkdownText` for direct UI usage.

## API stability rules (v1)

- Keep `MarkdownRun` and `MarkdownKind` backward compatible.
- Add new token kinds in an additive way only.
- Keep parser lifecycle methods stable (`start`, `write`, `end`, `reset`).
- Keep default inline-code behavior line-scoped until a versioned parser mode is introduced.

## Publish locally

From `packages/mobile`:

- `./gradlew :streaming-markdown:publishReleasePublicationToMavenLocal`

Publication coordinates are configured in `packages/mobile/gradle.properties`:

- `streamingMarkdownGroup`
- `streamingMarkdownVersion`
