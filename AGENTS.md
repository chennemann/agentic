# Agentic - Native T3 Code Client for Android

## Project Overview

Agentic is a native Android chat client for the T3 Code environment server. It consumes the
authenticated T3 HTTP and WebSocket RPC APIs. The Android runtime must remain
provider-neutral: provider instance IDs, model names, modes, commands, and activities come from the
server contract.

The repository is Android-only and is built with:

- Kotlin and Jetpack Compose with Material 3
- SQLDelight for versioned local projection caches
- Koin for dependency injection
- Navigation 3 with serializable routes
- KTLint for Kotlin formatting and style

## Workspace Layout

This repository is the coordinating workspace for two independent projects:

- `apps/android` is the Agentic Android application and belongs to this repository.
- `apps/t3code` is a separate T3 Code Git checkout with its own history and remotes.

Agents may read and edit both projects when a task crosses the T3 RPC contract boundary. Run Git
commands for T3 Code with `git -C apps/t3code ...`, and never stage or commit T3 Code files in the
Agentic repository. T3 Code is the source of truth for RPC contracts and server behavior;
Agentic owns the Android implementation that consumes those contracts. Do not add a build-time
dependency between the repositories.

## Quick Reference Commands

```bash
# Format and verify
./apps/android/gradlew -p apps/android ktlintFormat
./apps/android/gradlew -p apps/android ktlintCheck
./apps/android/gradlew -p apps/android build

# Focused Android tests
./apps/android/gradlew -p apps/android test

# Build, install, and launch a debug APK
./apps/android/gradlew -p apps/android :app:assembleDebug
./apps/android/gradlew -p apps/android :app:installDebug
adb shell am start -W -n de.chennemann.agentic/.MainActivity

# Generate SQLDelight interfaces
./apps/android/gradlew -p apps/android generateSqlDelightInterface
```

## Critical Rules

### T3 contract

1. T3 is the source of truth for projects, threads, messages, activities, approvals, and user input.
2. Android stores projection snapshots as a cache and applies sequenced stream items.
3. HTTP/WebSocket transport and T3 DTOs must not leak into Compose.
4. Provider routing uses stable provider instance IDs. Never branch on provider driver names.
5. Unknown additive activity payloads remain visible through a generic provider-neutral UI model.
6. Do not add legacy protocol fallbacks, server hosting, or sibling-repository build dependencies.

### Architecture

1. Repositories own observable state and expose `Flow`.
2. Services perform pairing, connection, and command orchestration.
3. ViewModels map repository/service state for display and forward user intents.
4. UI-only state is owned according to its lifetime; keep ephemeral presentation state in Compose.
5. Navigation routes are type-safe and serializable.
6. Production dependencies are wired through Koin.

### Compose

1. Keep conversation content dominant and the composer anchored at the bottom.
2. Preserve stable streaming and follow-latest behavior.
3. Use unidirectional data flow: state down, events up.
4. Use Material 3 components, theming, and adaptive layouts.

### Persistence and credentials

1. Define SQLDelight tables in `.sq` files and expose reactive queries.
2. Treat cached state as subordinate to newer live projection sequences.
3. Keep bearer credentials in the Android Keystore-backed credential store, never SQLDelight.
4. Never log credentials, pairing tokens, or authenticated URLs.

### Releases

1. Baseline tags are manual `v<major>` tags such as `v1` and `v2`.
2. CI publishes `v<major>.<commits_since_baseline>`.
3. Release APKs are signed in CI using `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, and `KEY_ALIAS`.

## Task Finalization

Run the Android-only quality gate in this order:

```bash
./apps/android/gradlew -p apps/android ktlintFormat
./apps/android/gradlew -p apps/android ktlintCheck
./apps/android/gradlew -p apps/android build
```

Run focused tests for the changed behavior before the full Android build. Fix only failures caused by
the assigned work, preserve unrelated changes, and repeat the gate until it passes.

If an Android device is connected, install and launch the debug app before completion. Report any
install or launch failure.

Create exactly one commit using:

```text
<type>(<component>): <description>

Context:
- Why the change was made

Changes:
- Bullet list of what changed
```

Once the assigned task, quality gate, commit, and any connected-device launch are complete, stop.

## Agent skills

### Issue tracker

Issues and PRDs are tracked in this repository's GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

The canonical default triage labels are used unchanged. See `docs/agents/triage-labels.md`.

### Domain docs

Domain documentation uses a single-context layout. See `docs/agents/domain.md`.
