# Streaming Markdown

Compose-specific streaming markdown rendering module for mobile.

## Scope

- Stream-safe inline code rendering based on backticks.
- Block rendering for fenced code, ordered and unordered lists, and GFM-style tables.
- Horizontally scrollable code and table content on narrow screens.
- Copy actions for fenced code blocks.
- Pluggable, theme-aware syntax highlighting with built-in lightweight lexers.
- Compose adapter that produces `AnnotatedString` output.
- Drop-in composable API via `StreamingMarkdownText`.

## Non-goals (v1)

- Full markdown specification support.
- Arbitrary nested markdown structures.
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

## Syntax highlighting plugins

`StreamingMarkdownText` accepts a `CodeLexerRegistry`. A plugin declares its fenced-code language
aliases and supplies a `CodeLexer` that returns ordered tokens. Token values must reproduce the
source exactly when concatenated; the registry validates that invariant before Compose renders the
token kinds with theme-aware colors.

```kotlin
val registry = CodeLexerRegistry.Default.withPlugins(
    CodeLexerPlugin(setOf("my-language", "my")) { source ->
        listOf(CodeToken(source, CodeTokenKind.PLAIN))
    },
)

StreamingMarkdownText(
    content = markdown,
    codeLexerRegistry = registry,
)
```

## Publish locally

From `apps/android`:

- `./gradlew :streaming-markdown:publishReleasePublicationToMavenLocal`

Publication coordinates are configured in `apps/android/gradle.properties`:

- `streamingMarkdownGroup`
- `streamingMarkdownVersion`
