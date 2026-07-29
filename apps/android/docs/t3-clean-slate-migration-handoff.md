# T3 Code Android Client - Clean-Slate Migration Handoff

## 1. Mission

Replace Agentic's OpenCode- and Pi-specific implementation with a native Android client for the
T3 Code environment server.

This is a hard cutover:

- There are no users to migrate.
- There is no backwards-compatibility requirement.
- Existing databases, settings, cached messages, server records, and session records are disposable.
- The existing OpenCode and Pi integrations must be deleted, not adapted.
- The T3 server may be changed because the project uses a controlled T3 Code fork.
- The current Compose chat experience is the primary asset to preserve.

The result must be provider-neutral. Android may display server-provided provider and model names,
but it must not contain provider-specific routing, protocol handling, models, conditionals, or UI
behavior.

This plan supersedes `apps/android/docs/ui-navigation-intent-refactor-plan.md`. That plan assumes
the legacy session/service architecture and should be deleted during final integration.

## 2. Repository roots

Agents executing this plan should resolve these roots before making changes:

- `AGENTIC_REPO`: this repository, currently
  `D:\Development\chennemann\agentic`
- `T3_REPO`: the sibling T3 Code fork, currently
  `D:\Development\chennemann\t3code`

Do not add a build-time dependency on either absolute path. The repositories must continue to
build independently in CI.

Before working in either repository:

1. Read that repository's `AGENTS.md` completely.
2. Inspect `git status`.
3. Preserve unrelated user changes.
4. Restrict edits to the work-package ownership described below.
5. Report the exact contract version, tests, and commit produced when handing work back.

## 2.1 Execution ledger

The integration owner should update only this ledger as packages land:

- [ ] S1 - T3 portable protocol
- [ ] A1 - Android contracts, authentication, and transport
- [ ] A2 - Android projection runtime and fresh persistence
- [ ] U1 - Chat-first Compose UI
- [ ] I1 - Hard cutover and integration

Recorded S1 contract:

```text
T3 commit:
portable protocol version:
OpenAPI artifact:
fixture artifact/checksum:
```

Recorded final integration:

```text
Agentic commit:
T3 commit tested:
Android device/emulator:
quality-gate result:
```

Recommended assignment prompt:

```text
Read AGENTS.md and apps/android/docs/t3-clean-slate-migration-handoff.md completely.
Execute only work package <PACKAGE_ID>. Treat the locked decisions and portable protocol sections
as requirements. Preserve unrelated work, do not implement another package, run the package's
required checks, create the repository-required commit, and return the specified handoff report.
```

## 3. Locked decisions

These decisions are part of the product direction. An implementing agent should not reopen them
without an explicit request from the owner.

### 3.1 Product scope

The first T3-backed Agentic release contains:

- T3 environment pairing by QR/pairing link or manual URL.
- A saved environment catalog with one active environment at a time.
- Project and thread selection.
- Thread creation and first-turn creation.
- Text chat with streaming assistant output.
- T3 thread activities rendered in the conversation.
- Tool activity cards with a generic fallback for unknown activity payloads.
- Approval requests and responses.
- Structured user-input requests and responses.
- Turn interruption.
- Thread rename, archive, and unarchive.
- Provider-instance and model selection.
- T3 interaction mode selection: `default` and `plan`.
- T3 runtime mode selection when supported.
- Slash-command suggestions when advertised by the selected provider instance.
- Cached read-only shell/thread state for startup and short offline periods.

The following are not part of the first release:

- Importing OpenCode or Pi history.
- Reading or migrating the old Android database.
- Running an agent server from the Android app.
- OpenCode SDK support.
- Pi relay support.
- mDNS discovery.
- T3 Connect cloud-management UI.
- Terminal UI.
- Source-control or review UI.
- Checkpoint browsing or revert UI.
- Thread snooze/settle UI.
- Server self-update UI.
- App self-update UI.
- In-app logs screen.
- Attachments.
- Background synchronization of every saved environment.
- Compatibility with a T3 server that does not advertise the new portable client protocol.

These may be added later using T3 capabilities. They must not add scaffolding to the initial
migration.

### 3.2 Transport

The portable native-client protocol is:

- JSON over authenticated HTTP for discovery, authentication, snapshots, configuration, and
  commands.
- Server-Sent Events for live shell and focused-thread updates.
- Existing T3 orchestration commands and projection types as the domain protocol.
- Bearer authentication using T3's existing pairing-token exchange.

Android must not implement Effect RPC wire framing.

### 3.3 State ownership

- The T3 environment server is the single source of truth for projects, threads, turns, messages,
  activities, approvals, and user-input requests.
- Android stores versioned projection snapshots as a cache.
- Android reducers apply sequenced T3 stream items.
- Android does not maintain a second normalized conversation database.
- UI-only state such as draft text, expanded cards, sheet visibility, and scroll position stays in
  the UI/ViewModel layer according to its lifetime.

### 3.4 Cutover policy

- Delete old SQLDelight schemas and migration history.
- Create a fresh schema and a new database filename, `agentic-t3.db`.
- Do not add migration or deletion code for the old `app.db`.
- Development devices must uninstall the old app or clear application data before testing.
- Do not implement legacy protocol fallback or a feature flag.
- Intermediate work may be non-runnable when a package is being replaced.
- Only the final integration gate requires the complete app to build and run.

## 4. What is preserved

Preserve the look, feel, and useful interaction behavior of the existing chat UI, not its domain
types or ViewModel implementation.

### 4.1 Reusable presentation assets

Use these as the starting visual implementation:

- `apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/chat/AgentChatScreen.kt`
- `apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/chat/SessionSelectionBottomSheet.kt`
- `apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/chat/QuickSwitchProjects.kt`
- `apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/components/ConversationHeader.kt`
- `apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/components/MessageComposer.kt`
- `apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/components/ToolCallCard.kt`
- `apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/components/TurnTimer.kt`
- `apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/theme/`
- `apps/android/app/src/main/kotlin/de/chennemann/agentic/icons/`
- `apps/android/streaming-markdown/`

The following existing behaviors are product requirements:

- Conversation content remains the dominant surface.
- The composer remains visually anchored at the bottom.
- Streaming text updates do not cause unstable jumping.
- The current follow-latest behavior and follow button are retained.
- Tool/activity cards can expand without losing the user's scroll context.
- New content auto-scrolls only when the user is already following the end.
- Plan/default mode is available directly from the composer.
- Project/thread switching remains quick and reachable from chat.
- Empty, loading, reconnecting, blocked, and failed states do not replace the entire chat surface
  unnecessarily.

Before destructive UI edits, capture reference screenshots or Compose previews for:

1. Empty chat.
2. Normal user/assistant turn.
3. Streaming assistant turn.
4. Collapsed and expanded tool card.
5. Quick-switch sheet.
6. Follow-latest button.
7. Plan/default composer state.

Pixel identity is not required, but spacing, hierarchy, density, and interaction character should
remain recognizably the same.

### 4.2 Presentation code that must be rewritten

Do not preserve these merely because they are under `ui/`:

- Existing chat contracts that expose `SessionState`, `ServerState`, or `ToolCallState`.
- `AgentChatViewModel`.
- `SessionSelectionViewModel`.
- Existing quick-switch derivation logic.
- Existing message-to-turn projection logic.
- Existing Plan/Build-to-agent-name mapping.
- Existing management and logs ViewModels.

The composables should consume new provider-neutral UI models.

## 5. Target application surface

The application has two top-level destinations.

### 5.1 Onboarding

Shown when there is no usable active environment.

It supports:

- Paste pairing URL.
- Scan pairing QR code using CameraX plus the bundled ML Kit barcode scanner, restricted to QR
  codes.
- Enter a direct T3 server URL.
- Display descriptor label, platform, and version before confirmation.
- Pair and establish an authenticated environment.
- Select a previously paired environment.
- Remove an environment and its credentials/cache.
- Explain unsupported-server and invalid/expired-pairing errors.

Endpoint policy:

- Prefer HTTPS.
- Permit HTTP only for loopback, private LAN, or explicitly recognized private overlay-network
  addresses.
- Reject public cleartext endpoints.
- Require a one-time security confirmation before storing a private cleartext endpoint.
- Never silently downgrade an HTTPS pairing URL to HTTP.

This screen should be intentionally small. It replaces the old server-management screen rather
than carrying it forward.

### 5.2 Chat

The chat screen owns:

- Environment indicator/switcher.
- Project and thread picker.
- New-thread action.
- Thread title and current status.
- Conversation feed.
- Activity/tool cards.
- Approval and user-input cards.
- Provider/model selector.
- Interaction-mode selector.
- Runtime-mode selector.
- Composer.
- Interrupt action.
- Connection/synchronization status.

Environment, project, thread, provider, and mode selection should use sheets or compact menus so
chat remains the primary screen.

## 6. T3 domain mapping

| Android concept | T3 contract |
| --- | --- |
| Paired backend | `ExecutionEnvironmentDescriptor` plus endpoint and bearer credential |
| Workspace | `OrchestrationProject` |
| Conversation | `OrchestrationThread` |
| Conversation list row | `OrchestrationThreadShell` |
| Chat message | `OrchestrationMessage` |
| Agent work/tool entry | `OrchestrationThreadActivity` |
| Send text | `thread.turn.start` |
| Stop current response | `thread.turn.interrupt` |
| Rename | `thread.meta.update` |
| Archive/unarchive | `thread.archive` / `thread.unarchive` |
| Plan mode | `interactionMode = "plan"` |
| Normal mode | `interactionMode = "default"` |
| Permission level | `runtimeMode` |
| Agent/model | `ModelSelection(instanceId, model, options)` |
| Approval | `thread.approval.respond` |
| User question | `thread.user-input.respond` |
| Project/thread catalog | `OrchestrationShellSnapshot` plus shell stream |
| Focused conversation | `OrchestrationThreadDetailSnapshot` plus thread stream |

Provider routing always uses the stable provider `instanceId`. The provider `driver` field is
display/capability metadata only and must never be used as an Android routing key.

## 7. Portable T3 protocol v1

This section is the source of truth for the T3 server work package.

### 7.1 Existing HTTP endpoints to retain

The Android client uses the existing endpoints:

- `GET /.well-known/t3/environment`
- `GET /api/auth/session`
- `POST /oauth/token`
- `GET /api/orchestration/shell`
- `GET /api/orchestration/threads/:threadId`
- `POST /api/orchestration/dispatch`

The WebSocket-ticket endpoint is not required by the SSE client.

### 7.2 New environment-client configuration

Add:

```text
GET /api/environment/client-config
```

Define a portable `EnvironmentClientConfig` contract containing only cross-client data:

- `environment: ExecutionEnvironmentDescriptor`
- `auth: ServerAuthDescriptor`
- `providers: ServerProviders`
- `shellResumeCompletionMarker: boolean`
- `threadResumeCompletionMarker: boolean`
- `protocolVersion: 1`

Do not expose desktop-local keybinding paths, editor integrations, or other shell-specific
configuration through this endpoint.

The endpoint requires `orchestration:read`.

### 7.3 New SSE endpoints

Add:

```text
GET /api/orchestration/shell/stream
GET /api/orchestration/threads/:threadId/stream
```

Query parameters:

```text
afterSequence=<non-negative integer>
requestCompletionMarker=<true|false>
```

`afterSequence` is required for resumption after an initial snapshot.

Stream payloads:

- Shell stream: `OrchestrationShellStreamItem`.
- Thread stream: `OrchestrationThreadStreamItem`.

SSE framing:

```text
id: <sequence when the item is sequenced>
event: message
data: <one compact JSON object>

```

Requirements:

- Authenticate the request using the normal bearer header.
- Require `orchestration:read`.
- Set `Content-Type: text/event-stream`.
- Disable intermediary buffering/caching.
- Emit SSE comments as keepalives at least every 15 seconds.
- Cancel upstream subscriptions when the HTTP client disconnects.
- Bound per-client buffering and terminate a client that cannot keep up.
- Reuse the existing WebSocket subscription projection/replay semantics.
- Do not duplicate shell/thread projection algorithms inside the HTTP layer.
- Preserve fresh-snapshot fallback when a cursor is ahead of the head or too far behind.
- Emit the existing opt-in synchronization/completion marker.
- Keep sequence values monotonic.
- Never serialize Effect RPC request/response envelopes into SSE.

The implementation should extract a shared subscription service from `apps/server/src/ws.ts` if
necessary. Both Effect RPC and SSE must call the same service.

### 7.4 Capability advertisement

Extend `ExecutionEnvironmentCapabilities` with optional:

```text
portableClientProtocol: 1
```

Android accepts the environment only when this value is `1`.

Do not infer protocol support from `serverVersion`.

### 7.5 Pairing flow

Android pairing performs:

1. Normalize the pairing URL and extract host, expected environment ID, and bootstrap credential.
2. Fetch the environment descriptor.
3. Reject an environment-ID mismatch.
4. Exchange the credential at `/oauth/token` using:
   - `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`
   - `subject_token=<bootstrap credential>`
   - `subject_token_type=urn:t3:params:oauth:token-type:environment-bootstrap`
   - `requested_token_type=urn:ietf:params:oauth:token-type:access_token`
   - scopes `orchestration:read orchestration:operate`
   - Android client label/device/OS metadata
5. Validate the resulting session with `/api/auth/session`.
6. Store the bearer token securely.
7. Fetch client configuration and shell snapshot.

The bootstrap credential is one-time input and must not be retained after successful exchange.

### 7.6 Contract artifacts

The T3 repository must produce:

- An OpenAPI document for the portable HTTP endpoints.
- JSON schema or canonical JSON fixtures for each SSE union.
- Golden payload fixtures covering all Android-supported command and projection variants.
- A machine-readable protocol version.

The Android repository must pin:

- T3 contract package/version or source commit.
- Protocol version.
- Fixture checksum or equivalent provenance metadata.

Short-term vendoring of generated contract artifacts is acceptable. Referencing the sibling T3
checkout from Gradle is not.

## 8. Android architecture

Keep the existing `:app` and `:streaming-markdown` modules. Replace `:api` with a focused
`:t3-contracts` Kotlin/JVM module.

Suggested package layout:

```text
de.chennemann.agentic
├── data
│   ├── auth
│   ├── cache
│   ├── environment
│   └── t3
├── domain
│   ├── auth
│   ├── connection
│   ├── environment
│   └── orchestration
├── ui
│   ├── onboarding
│   ├── chat
│   ├── components
│   └── theme
└── navigation
```

### 8.1 Contract module

`:t3-contracts` owns:

- Serializable IDs and DTOs.
- HTTP request/response DTOs.
- Discriminated shell/thread stream items.
- Client orchestration commands used by Android.
- Provider/model configuration DTOs.
- Golden-fixture decoding tests.

Serialization configuration:

- `ignoreUnknownKeys = true`
- `explicitNulls = false`
- Discriminate unions by T3's `type` field.
- Preserve unknown activity payloads as `JsonElement`.
- Reject unknown top-level protocol versions.
- Tolerate unknown additive enum/data fields where a safe fallback exists.

The module does not own Ktor, Android classes, persistence, retries, or UI models.

### 8.2 Transport

The transport layer owns:

- URL normalization.
- Bearer-header injection.
- JSON HTTP requests.
- SSE parsing.
- HTTP status-to-domain error mapping.
- Cancellation.
- Redacted request diagnostics.

Use a streaming HTTP client configuration without a finite response-body read timeout. Liveness is
provided by the server keepalive and connection supervisor, not a normal request timeout.

Interfaces:

```text
EnvironmentMetadataClient
EnvironmentAuthClient
EnvironmentConfigClient
OrchestrationSnapshotClient
OrchestrationCommandClient
OrchestrationStreamClient
```

No transport DTO should escape directly into Compose code.

### 8.3 Secure credentials

Create `CredentialStore` with an Android Keystore-backed implementation.

Store:

- Bearer access token keyed by environment ID.

Do not store:

- Bootstrap pairing token after exchange.
- Credentials in SQLDelight.
- Credentials in `SavedStateHandle`.
- Credentials in logs or exception messages.

Removing an environment deletes its token, cache, and local preferences.

### 8.4 Persistence

Use a new SQLDelight database named `agentic-t3.db`.

Create a fresh schema with no migration files from the old app.

Recommended tables:

```text
environment
    environment_id TEXT PRIMARY KEY
    label TEXT NOT NULL
    base_url TEXT NOT NULL
    platform_os TEXT NOT NULL
    platform_arch TEXT NOT NULL
    server_version TEXT NOT NULL
    last_connected_at INTEGER

projection_cache
    environment_id TEXT NOT NULL
    cache_kind TEXT NOT NULL
    cache_key TEXT NOT NULL
    schema_version INTEGER NOT NULL
    sequence INTEGER
    payload_json TEXT NOT NULL
    updated_at INTEGER NOT NULL
    PRIMARY KEY(environment_id, cache_kind, cache_key)

local_preference
    environment_id TEXT NOT NULL
    preference_key TEXT NOT NULL
    payload_json TEXT NOT NULL
    PRIMARY KEY(environment_id, preference_key)
```

Cache kinds for v1:

- `client-config`
- `shell`
- `thread`

Repositories expose `Flow`; the server remains authoritative.

### 8.5 Reducers

Implement pure reducers:

```text
ShellProjectionReducer
ThreadProjectionReducer
ClientConfigReducer
```

Reducer rules:

- Snapshot replaces the matching projection.
- Ignore a sequenced item at or below the applied sequence.
- Apply events in sequence order only.
- A sequence gap invalidates the live projection and triggers snapshot recovery.
- A synchronized/completion marker marks catch-up complete but does not alter domain content.
- Unknown additive activity payloads remain renderable through a generic card.
- A deleted thread is removed from shell and focused-thread state.
- Cached state never overwrites a newer in-memory sequence.

All reducers require fixture-backed unit tests.

### 8.6 Connection supervisor

There is exactly one retry owner per active environment.

Connection state:

```text
NoEnvironment
Cached
Connecting
Synchronizing
Live
Backoff
BlockedAuthentication
UnsupportedProtocol
Error
```

Responsibilities:

- Load the cached client configuration and shell immediately.
- Verify the environment descriptor/capability.
- Fetch fresh configuration and shell snapshot.
- Subscribe from snapshot sequence.
- Subscribe to the focused thread from its snapshot sequence.
- Persist applied projections with debounce.
- Restart the focused-thread subscription when selection changes.
- Stop streams when environment changes.
- Wake on app foreground and network restoration.
- Use exponential backoff capped at 16 seconds.
- Do not consume retry attempts while Android reports no network.
- Block on 401/403 until credentials or selected environment change.
- Snapshot and resume after an unrecoverable sequence gap.

Inactive saved environments are not kept live in v1.

### 8.7 Services and repositories

Use these boundaries:

```text
EnvironmentRepository
    observable saved/active environment catalog

OrchestrationRepository
    observable client config, shell, and focused-thread projections

EnvironmentService
    pairing, selection, removal, and connection orchestration

ThreadService
    thread selection and T3 command orchestration

ChatService
    start turn, interrupt, approval, user input, and mode/model commands
```

Repositories are the observable state owners. Services perform business orchestration. ViewModels
only map repository/service state into display state and forward user intents.

### 8.8 UI models

Create UI-owned models rather than exposing T3 DTOs:

```text
ChatUiState
ChatMessageUi
ChatActivityUi
PendingApprovalUi
PendingUserInputUi
ThreadPickerUiState
EnvironmentPickerUiState
ComposerUiState
```

At minimum, activity UI supports:

- Informational activity.
- Tool-like activity.
- Approval activity.
- User-input activity.
- Error activity.
- Unknown/generic activity.

The generic activity card displays server summary and safe formatted JSON/detail content. It must
not disappear simply because Android does not recognize a provider's payload.

## 9. Command behavior

### 9.1 Create and start

Starting from no selected thread should dispatch `thread.turn.start` with the command's
create-thread/bootstrap input, project ID, message, model selection, interaction mode, and runtime
mode.

Do not perform a fragile client-generated sequence of:

1. create thread;
2. wait for thread;
3. send message.

Use the server's atomic/bootstrap command shape.

### 9.2 Existing thread

For an existing thread, `thread.turn.start` includes:

- Thread ID.
- User text.
- Selected `ModelSelection`.
- Selected interaction mode.
- Selected runtime mode.
- Unique command ID.

The UI clears the draft only after command acceptance. Message/turn content comes back through the
server projection.

For a new thread, generate unique command, thread, and message IDs client-side using UUIDs. Populate
`bootstrap.createThread` with the selected project/model/modes, a title derived from the first
non-empty prompt line, and `branch = null` / `worktreePath = null`. Worktree preparation is out of
scope for v1.

### 9.3 Interrupt

Show interrupt when the thread session/turn projection is active. Dispatch
`thread.turn.interrupt`. Do not locally mark the turn stopped before the projected event arrives.

### 9.4 Approval

Render the oldest active unresolved approval for the focused thread.

Support server-advertised decisions:

- `accept`
- `acceptForSession`
- `decline`
- `cancel`

Disable duplicate input while a response command is in flight. Resolution comes from the thread
projection.

### 9.5 User input

Render T3's structured prompts and options. Preserve draft answers while the card is visible.
Dispatch `thread.user-input.respond` once. Clear drafts only after acceptance or projected
resolution.

### 9.6 Modes and models

- `default` and `plan` are interaction modes.
- Runtime modes are permission/sandbox choices.
- Provider instance and model are a `ModelSelection`.
- Persist last selection locally per environment/project if still advertised.
- Fall back to the server/project default when a saved model disappears.
- Never map modes to hard-coded agent names.

## 10. Deletion manifest

The final Agentic change deletes the following.

### 10.1 Repository server code

- `apps/server/`
- `apps/agentic-relay/`
- `third_party/opencode/`
- `.gitmodules`
- `script/setup-opencode-submodule.sh`

### 10.2 Android OpenCode API

- `apps/android/api/`
- `include(":api")` from Android settings.
- `implementation(project(":api"))`.
- OpenCode OpenAPI generator plugins, configuration, workarounds, and smoke tests.

### 10.3 Android legacy data/domain

Delete rather than adapt:

- `data/ServerService.kt`
- `data/ServerRepository.kt`
- `data/MdnsService.kt`
- `data/SessionCacheRepository.kt`
- Existing `data/v2/` server/project/session/message stores.
- Existing `domain/session/` orchestration implementation.
- Existing `domain/message/` parsing/decorating implementation.
- Existing physical `domain/v2/` server/project/session/message services.
- OpenCode server adapters.
- Coroutine rollout flags for the legacy execution path.
- Corresponding unit tests and fixtures.

Generic network observation may be retained only if the new connection supervisor uses it.

### 10.4 Old SQLDelight state

Delete:

- `AppLog.sq`
- `Message.sq`
- `MessageCache.sq`
- `MessageToolCall.sq`
- `Projects.sq`
- `Servers.sq`
- `SessionCache.sq`
- `Sessions.sq`
- `Settings.sq`
- Existing schema snapshots.
- Migrations `1.sqm` through `9.sqm`.
- Old settings/log schemas if the new app does not actively use them.

Create the new schema from zero.

### 10.5 Non-chat UI

Delete:

- Existing `ui/manage/`.
- Existing `ui/logs/`.
- Routes and navigation events for those screens.
- Local log screen/storage machinery if it has no new runtime consumer.
- App updater integration and dependencies.

Keep basic redacted Android logging where useful, without a persisted log database or user-facing
screen.

### 10.6 Branding and tooling

- Rename `opencode` launcher resources to `agentic`.
- Remove the `opencode.local` default.
- Remove `opencode-` mDNS assumptions.
- Remove OpenCode/Pi language from `AGENTS.md`, workflow names, scripts, and docs.
- Remove Pi/OpenCode root npm workspaces and lockfile.
- Remove root `package.json`, `package-lock.json`, and TypeScript configuration.
- Remove Node setup/build/check steps from Agentic workflows.
- Remove Node/Pi recipes from `justfile`.
- Remove `script/add-icon.ts`; the initial clean Android repository has no Node/Bun tooling.

## 11. Agent work packages

Each work package is intended to be handed to one responsible agent. Packages with dependencies
must not start implementation against guessed contracts.

### Package S1 - T3 portable protocol

Repository: `T3_REPO`

Owns:

- `packages/contracts/src/environment.ts`
- `packages/contracts/src/environmentHttp.ts`
- Any new portable contract modules.
- `apps/server/src/orchestration/http.ts`
- Relevant HTTP route/layer assembly.
- Shared subscription extraction from `apps/server/src/ws.ts`.
- T3 server and contract tests.
- Generated OpenAPI/schema/fixture artifacts.

Must not:

- Change provider adapters.
- Create Android-specific orchestration domain types.
- Duplicate the orchestration projector.
- Modify Agentic.

Deliverables:

- Portable protocol capability.
- Client-config endpoint.
- Two SSE streams.
- Shared stream implementation used by RPC and SSE.
- Contract artifacts and fixtures.
- Protocol documentation.
- Passing targeted T3 checks.

Exit criteria:

- A `curl`-style bearer-authenticated client can fetch config/snapshots and observe SSE.
- Resume from a known sequence is gapless and duplicate-safe.
- Large/invalid cursors return a fresh snapshot using existing semantics.
- Disconnect cancels upstream work.
- Contract fixtures decode independently of Effect RPC.

Handoff report:

- T3 commit SHA.
- Protocol version.
- Changed endpoints.
- Capability field.
- Artifact locations/checksums.
- Commands run and results.
- Known limitations.

### Package A1 - Android contracts, auth, and transport

Repository: `AGENTIC_REPO`

Depends on: S1 contract and fixtures frozen.

Owns:

- New `:t3-contracts` module.
- Android Gradle wiring required for that module.
- `data/t3/`.
- `data/auth/`.
- Transport/auth tests and fixture decoding.

Must not:

- Implement ViewModels or Compose UI.
- Implement projection ownership.
- Retain or wrap OpenCode `:api`.
- Reference the sibling T3 checkout from Gradle.

Deliverables:

- Pinned T3 portable contract.
- Kotlin DTOs.
- HTTP client.
- SSE parser.
- Pairing parser and token exchange.
- Keystore-backed credential store.
- Golden-fixture tests.

Exit criteria:

- Tests pair against a fake server.
- Every S1 Android-supported fixture decodes.
- HTTP auth headers are correct.
- Stream cancellation closes the response.
- Credentials and pairing tokens never appear in captured logs.

Handoff report:

- Contract version/commit.
- Vendored/generated artifact locations.
- Public Kotlin interfaces.
- Tests and results.
- Integration notes for A2.

### Package A2 - Android projection runtime and fresh persistence

Repository: `AGENTIC_REPO`

Depends on: A1 interfaces and fixtures.

Owns:

- `domain/environment/`.
- `domain/connection/`.
- `domain/orchestration/`.
- `data/cache/`.
- Fresh SQLDelight schema.
- Reducer, repository, service, and connection-supervisor tests.

Must not:

- Edit Compose screens.
- Retain the old `SessionService`.
- Add migration compatibility.
- Invent state not projected by T3 when the server owns it.

Deliverables:

- Environment catalog repository.
- Client-config, shell, and thread projection repositories.
- Pure reducers.
- Fresh cache database.
- Environment, thread, and chat services.
- Single-owner connection supervisor.
- Fake transport for UI tests.

Exit criteria:

- Cached shell/thread appears before the network completes.
- Live data supersedes cache.
- Snapshot-to-stream handoff loses no event.
- Duplicates are ignored.
- Gaps recover through a new snapshot.
- Environment switching cancels old streams.
- Authentication blocks rather than loops.
- Repository state is observable through `Flow`.

Handoff report:

- Public service/repository APIs.
- State diagrams or noteworthy invariants.
- Tests and results.
- UI integration fixture/fake instructions.

### Package U1 - Chat-first Compose UI

Repository: `AGENTIC_REPO`

Depends on:

- A2 UI-facing repository/service interfaces.
- Existing chat reference screenshots/previews.

Owns:

- `ui/onboarding/`.
- `ui/chat/`.
- `ui/components/`.
- `navigation/`.
- UI and ViewModel tests.

May retain/adapt:

- Theme.
- Icons.
- Streaming-markdown.
- Existing chat composable layout/scroll behavior.

Must not:

- Call Ktor or SQLDelight.
- Decode T3 DTOs.
- Branch on provider driver names.
- Reintroduce manage/log screens.
- Put retry or command orchestration in ViewModels.

Deliverables:

- Onboarding/pairing UI.
- Chat screen using new UI models.
- Environment/project/thread sheets.
- Provider/model/runtime/interaction selectors.
- Activity/tool cards.
- Approval card.
- User-input card.
- Interrupt state.
- Empty/loading/cached/reconnecting/error presentations.
- Compose previews and tests.

Exit criteria:

- The chat retains the established visual hierarchy and scroll behavior.
- Unknown activity payloads render through a generic card.
- Approval and input requests are actionable.
- The UI remains usable while showing cached/reconnecting state.
- All actions are intent-based ViewModel events.

Handoff report:

- Screens and states implemented.
- Screenshots/previews.
- Tests and results.
- Any deliberate visual differences.

### Package I1 - Hard cutover and integration

Repository: `AGENTIC_REPO`

Depends on: A1, A2, and U1 complete.

Owns:

- Koin wiring.
- Application startup.
- Final Gradle/module structure.
- Deletion manifest.
- Root tooling and CI cleanup.
- Branding.
- Documentation.
- End-to-end tests and device validation.

Must not:

- Add compatibility layers to make old tests pass.
- Restore legacy server/session abstractions.
- Modify T3 protocol without returning work to S1.

Deliverables:

- Only new T3 runtime wired in production.
- All legacy files removed.
- Updated `AGENTS.md`.
- Android-only Agentic CI/tooling.
- Real T3 integration test evidence.
- Installed and launched debug app when a device is connected.

Exit criteria:

- No production OpenCode/Pi dependency remains.
- App starts at onboarding with clean data.
- App pairs with the forked T3 server.
- A thread can be created and used from Android.
- Streaming, interruption, approvals, and user input work.
- Two different provider instances work without Android code changes.
- Final quality gate passes.

Handoff report:

- Agentic commit SHA.
- T3 server commit/version tested.
- Device/emulator used.
- Exact test commands and results.
- Remaining intentionally deferred features.

## 12. Dependency graph

```text
S1 T3 portable protocol
        │
        ▼
A1 Android contracts/auth/transport
        │
        ▼
A2 Android reducers/runtime/persistence
        │
        ▼
U1 Chat-first UI
        │
        ▼
I1 Hard cutover/integration
```

U1 may capture UI references and prepare stateless visual components while S1/A1/A2 run, but final
ViewModel integration must use A2's real interfaces. It must not create temporary domain
abstractions that become a second contract.

## 13. Integration scenarios

The final implementation must demonstrate these scenarios.

### 13.1 First launch

1. Start with no database and no credentials.
2. Show onboarding.
3. Parse a pairing link.
4. Show the discovered environment identity.
5. Pair successfully.
6. Load provider configuration, projects, and thread shells.
7. Enter chat.

### 13.2 New task

1. Select a project.
2. Select provider/model, interaction mode, and runtime mode.
3. Enter text with no existing thread selected.
4. Submit.
5. Server atomically creates the thread and starts the turn.
6. Shell projection reveals the new thread.
7. Focused-thread projection streams user, activity, and assistant content.

### 13.3 Existing thread

1. Open a cached thread while disconnected.
2. Restore connectivity.
3. Replace cache with fresh snapshot.
4. Resume from snapshot sequence.
5. Receive no duplicated messages or activities.
6. Submit another turn.

### 13.4 Interrupt

1. Start a long-running turn.
2. Show running status/timer.
3. Interrupt.
4. Wait for projected interrupted/stopped state.
5. Leave existing partial assistant content visible.

### 13.5 Approval

1. Receive an approval request from any provider adapter.
2. Display a provider-neutral approval card.
3. Accept or decline.
4. Prevent duplicate response.
5. Remove/resolve the card from projected state.
6. Continue streaming the same turn.

### 13.6 User input

1. Receive a structured user-input request.
2. Fill requested answers.
3. Submit once.
4. Resolve through projected state.
5. Continue the turn.

### 13.7 Reconnect

1. Lose network during streaming.
2. Keep last projected content visible.
3. Show reconnecting state without replacing chat.
4. Resume after the last applied sequence.
5. Apply missed events exactly once.

### 13.8 Environment switch

1. Pair two environments.
2. Switch the active environment.
3. Cancel old streams.
4. Load cached then live state for the new environment.
5. Ensure project/thread IDs do not collide across environments.

### 13.9 Provider neutrality

1. Run a turn through provider instance A.
2. Run a turn through provider instance B with a different driver.
3. Verify the same Android paths, reducers, and cards are used.
4. Verify unknown activity payloads remain visible.

## 14. Test matrix

### 14.1 T3 server

- Descriptor advertises protocol v1.
- Client config requires read scope.
- SSE endpoints reject missing/invalid bearer credentials.
- Shell and thread streams enforce read scope.
- Initial snapshot sequence resumes correctly.
- Completion markers are opt-in and ordered.
- Cursor-ahead behavior returns authoritative state.
- Large-gap behavior returns authoritative state.
- Live bursts preserve final shell/thread state.
- Project/thread removal is not lost during coalescing.
- Client disconnect cancels source stream.
- Slow consumer is bounded.
- Additive unknown fields remain schema-compatible.
- OpenAPI and fixture artifacts are deterministic.

### 14.2 Android contracts/transport

- Every golden payload decodes.
- Unknown JSON fields are ignored.
- Unknown activity payload is retained.
- Unsupported protocol version is rejected.
- Pairing environment-ID mismatch is rejected.
- Expired pairing credential produces a user-actionable error.
- Token exchange scopes are exact.
- Bearer token is attached only to the intended environment.
- SSE handles CRLF/LF, comments, multiline data, IDs, and reconnect cancellation.
- HTTP error bodies do not leak credentials.

### 14.3 Android runtime

- Reducers are pure and deterministic.
- Duplicate sequence is ignored.
- Sequence gap triggers resnapshot.
- Stale cache cannot replace live state.
- Focus change cancels old thread subscription.
- Environment change cancels all old environment work.
- Offline state does not spin retries.
- Retry delay is bounded.
- Auth failure blocks retries.
- Cache corruption is discarded safely.
- Removing environment clears cache and token.
- Command failure leaves projected state authoritative.

### 14.4 UI

- Empty chat.
- Cached chat.
- Streaming chat.
- Follow-latest on/off.
- Expanded activity card.
- Generic unknown activity.
- Pending approval.
- Pending user input.
- Running/interrupt state.
- Reconnecting banner/state.
- Environment picker.
- Project/thread picker.
- Provider/model picker.
- Interaction/runtime picker.
- Rename/archive/unarchive.
- New-thread bootstrap.

### 14.5 End to end

Use a real T3 fork server and Android device/emulator for:

- Pairing.
- Thread creation.
- Streaming.
- Reconnect.
- Interrupt.
- Approval.
- User input.
- Provider switch.

## 15. Final quality gate

After the hard cutover, Agentic is an Android-only repository unless the standalone icon helper is
retained. Update repository instructions before running the final gate so they describe the new
reality.

Run in order:

```bash
./apps/android/gradlew -p apps/android ktlintFormat
./apps/android/gradlew -p apps/android ktlintCheck
./apps/android/gradlew -p apps/android build
```

Also:

1. Run the focused fake-server/integration tests.
2. Search active source, docs, workflows, and build files for OpenCode/Pi-specific identifiers.
3. Verify no `:api` module, Node server workspace, relay, or submodule remains.
4. Check for a connected Android device.
5. If connected, install and launch the debug app.
6. Pair it with the exact T3 commit reported by S1.
7. Execute the end-to-end scenarios.

T3 work must independently pass the targeted checks required by `T3_REPO/AGENTS.md`.

## 16. Definition of done

The migration is complete only when all statements are true:

- Agentic contains one production backend integration: T3 portable protocol v1.
- Android does not implement Effect RPC.
- T3 exposes authenticated portable config, shell-stream, and thread-stream endpoints.
- Android starts from a fresh database and has no migration compatibility code.
- OpenCode SDK/submodule/API generation is gone.
- Pi server and relay are gone.
- Legacy server/session/message orchestration code is gone.
- The old manage and logs product surfaces are gone.
- Onboarding and chat are the only top-level product destinations.
- The existing chat UI character and scroll behavior are retained.
- Projects, threads, messages, activities, and statuses come from T3 projections.
- Commands use T3's orchestration command endpoint.
- Provider and model routing uses provider instance IDs.
- No Android code path branches on provider driver.
- Streaming resumes by sequence without duplication.
- Approvals and user-input requests are usable.
- Credentials are stored securely and redacted.
- The app works with at least two different T3 provider instances.
- Agentic lint, tests, build, and connected-device launch requirements pass.
- Each responsible agent has supplied its handoff report.

## 17. Non-goals after completion

Completion of this plan does not imply parity with the T3 desktop or React Native clients. The
Android product is intentionally chat-first. Additional T3 capabilities should be added later only
when they improve the mobile chat workflow and can be discovered through provider-neutral
capabilities.
