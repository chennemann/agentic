# Agentic and the current upstream T3 API

Assessment date: 2026-10-02. The original assessment below was static. The migration and live
interoperability checks were completed on 2026-10-04 against the same pinned upstream revision;
see the implementation verification section.

## Revisions and scope

- Local T3 fork: `b5542294b91c1a1b62336eb1934afe2c5d43cdd3`.
- Fork's upstream base: `451afcb22d93f06cb24f9bc16703404564952553`.
- Upstream main verified with `git ls-remote` and fetched into `upstream/research-main`: [`54084ae1e6c32809db040e4fa571c80fdf2d8ae4`](https://github.com/pingdotgg/t3code/commit/54084ae1e6c32809db040e4fa571c80fdf2d8ae4). This was 63 commits beyond the fork base. Fetch also updated the normal upstream/main tracking ref; no checkout or application code changed.
- Six fork commits sit above the common base. Two introduced the portable-client interface and HTTP/SSE adapter: `a3a51df555` and `32ac3bf583`. Later fork changes isolate downstream features.

The existing [capability-parity inventory](capability-parity-inventory.md) explicitly describes older frozen revisions. Its portable-protocol gaps are historical evidence, not a current requirement to add server endpoints.

## Conclusion

Upstream already supplies the native API required for Agentic's core client. Agentic already contains native RPC contracts and tests; the remaining migration is principally removing assumptions about fork-only advertised capabilities, checking real upstream interoperability, and updating optional feature coverage. Removing the portable adapter from the server is distinct from removing every fork feature.

Agentic commit `8685d016` (`fix(transport): use upstream T3 WebSocket RPC`) already moved the transport to native RPC. The current [KtorT3Client](../../apps/android/app/src/main/kotlin/de/chennemann/agentic/data/t3/KtorT3Client.kt) exchanges a WebSocket ticket, connects to `/ws`, and implements Effect request, chunk, acknowledgement, cancellation and exit frames. It does not call the fork's portable client-config or SSE routes.

## Upstream surface versus the portable adapter

| Client need | Upstream-owned surface | Fork-only accommodation |
|---|---|---|
| Discovery and pairing | `/.well-known/t3/environment`, `/oauth/token`, `/api/auth/session`, `/api/auth/websocket-ticket` | None required |
| Provider and environment configuration | `server.getConfig`, `subscribeServerConfig`; provider instance IDs, models, skills and slash commands | `/api/environment/client-config` and portable version metadata |
| Initial state and commands | HTTP shell/thread snapshots and dispatch; RPC dispatch | Portable OpenAPI/JSON-schema packaging |
| Live shell/thread projection | `orchestration.subscribeShell`, `orchestration.subscribeThread`, resumable sequences and completion markers | HTTP SSE routes |
| Files, worktrees, execution sessions | `projects.*`, `vcs.*`, `terminal.*` RPCs | Extra capability flags advertising these existing APIs |
| Foreground/background activity | `server.reportClientActivity`, background-policy RPCs | `backgroundActivity` capability flag |

Sources: upstream [HTTP contracts](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/environmentHttp.ts#L409), [RPC contracts](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/rpc.ts#L293), and [provider descriptors](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/server.ts#L206). The retired portable contract and routes remain available in fork revision `b5542294` at `packages/contracts/src/downstream/portableClient.ts` and `apps/server/src/downstream/Routes.ts`.

The server's native socket endpoint is `/ws`, using Effect RPC JSON serialization. It is not a generic JSON-RPC endpoint. Native clients need request/stream/exit semantics, cancellation and flow control, ticket authentication and protocol negotiation. Upstream's shared client implements that transport with `RpcSerialization.layerJson`; this is useful executable reference material, without introducing a build-time dependency into Android. Sources: [server socket](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/apps/server/src/ws.ts), [client session](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/client-runtime/src/rpc/session.ts#L192), [compatibility negotiation](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/client-runtime/src/connection/compatibility.ts).

Upstream supports HTTP snapshot plus socket catch-up, or a socket subscription that starts with a snapshot. It can replace an oversized or invalid replay range with a fresh snapshot. Thread reads support optional pagination and reasoning messages; server config advertises those options. Therefore retiring SSE does not require giving up persisted sequence cursors or bounded catch-up. Sources: [subscription contracts](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/orchestration.ts#L987), [subscription implementation](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/apps/server/src/orchestration/Subscriptions.ts), [server config](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/server.ts#L578).

## Residual fork dependency at the assessment baseline

The fork advertises `threadWorktrees`, `workspaceFiles`, `executionSessions`, and `backgroundActivity`. These fields are absent from the upstream descriptor even though their underlying RPCs exist. Android defaults them to false, and three currently gate behavior:

- [ThreadWorkspaceService.kt](../../apps/android/app/src/main/kotlin/de/chennemann/agentic/domain/orchestration/ThreadWorkspaceService.kt): `threadWorktrees` false reduces choices to the local checkout.
- [WorkspaceFilesService.kt](../../apps/android/app/src/main/kotlin/de/chennemann/agentic/domain/orchestration/WorkspaceFilesService.kt) and [ChatViewModel.kt](../../apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/chat/ChatViewModel.kt): `workspaceFiles` false rejects file operations and hides their entry point.
- [BackgroundAwarenessService.kt](../../apps/android/app/src/main/kotlin/de/chennemann/agentic/domain/connection/BackgroundAwarenessService.kt): `backgroundActivity` false suppresses activity reports.

`executionSessions` is declared but no production gate referencing it was found. Replace these fork-specific gates with an explicit supported upstream baseline and genuine upstream optional capabilities where applicable. Do not add those fields to upstream merely to preserve existing client assumptions. Sources: [fork capability schema](../../apps/t3code/packages/contracts/src/downstream/environment.ts), [fork capability producer](../../apps/t3code/apps/server/src/downstream/Environment.ts), [upstream descriptor producer](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/apps/server/src/environment/ServerEnvironment.ts), [Android DTO](../../apps/android/t3-contracts/src/main/kotlin/de/chennemann/agentic/t3/contract/T3RpcContract.kt).

The [UpstreamRpcContractTest](../../apps/android/t3-contracts/src/test/kotlin/de/chennemann/agentic/t3/contract/UpstreamRpcContractTest.kt) configuration fixture currently includes `workspaceFiles: true`. That synthetic fixture does not prove compatibility with an actual upstream descriptor. Add fixtures from the pinned upstream revision without downstream fields, and verify those user workflows remain available.

Additional Android findings from the parallel client inspection:

- `ProviderInstance.displayName` is required by the Android DTO, whereas upstream `ServerProvider.displayName` is optional. A valid upstream omission could fail configuration decoding; this is a schema mismatch, not an observed live failure. Accept an absent display name and choose a presentation fallback from stable instance metadata. Sources: [Android DTO](../../apps/android/t3-contracts/src/main/kotlin/de/chennemann/agentic/t3/contract/T3RpcContract.kt), [upstream provider](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/server.ts#L206).
- The `threadDeletion` UI gate defaults false, but no such capability is advertised by the inspected upstream or fork descriptor. Audit it against upstream `thread.delete` support instead of preserving a stale flag. Sources: [ChatViewModel.kt](../../apps/android/app/src/main/kotlin/de/chennemann/agentic/ui/chat/ChatViewModel.kt), [upstream commands](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/orchestration.ts).
- Android reads `server.getConfig` during registration/connection but does not subscribe to `subscribeServerConfig`. Add the upstream subscription so provider catalogs and relevant settings can refresh during a connection. Android currently requests full thread subscriptions; upstream's advertised pagination provides a separate performance improvement for long conversations. Sources: [Android RPC implementation](../../apps/android/app/src/main/kotlin/de/chennemann/agentic/data/t3), [upstream config stream](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/rpc.ts), [thread pagination contract](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/orchestration.ts#L1010).

## What actually changed in latest upstream

Comparing the fork base to the fetched upstream head produces only 68 added lines across seven contract files. There are no changes to auth, environment, orchestration stream schemas or RPC transport in that interval. New opportunities are additive:

1. `projects.ensureScratch` starts a conversation without first selecting a repository. Gate the feature on `ServerConfig.scratchWorkspaceRoot`.
2. `projects.createNew({ name })` creates a new project directory and initial commit. Gate it on `ServerConfig.newProjectsRoot`; handle optional `commitError` as partial success because the project still exists.
3. `server.refreshProviders` accepts `fresh` with instance ID and workspace directory to rescan workspace skills/slash commands.
4. Optional usage `credentialFingerprint`, pull-request summary `stack`, and `sidebarWorkingShelfEnabled` add adjacent presentation opportunities.

Sources: [project inputs/results](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/project.ts#L285), [new RPCs](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/rpc.ts#L1002), [provider refresh](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/rpc.ts#L488), [config discovery fields](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/server.ts#L614), [usage](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/providerUsageLimits.ts), [pull requests](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/pullRequest.ts), [settings](https://github.com/pingdotgg/t3code/blob/54084ae1e6c32809db040e4fa571c80fdf2d8ae4/packages/contracts/src/settings.ts).

## Retirement boundary and proof

The fork still owns todo/planning commands and projections, `agentInstructions`, `recencyAnchorAt`, terminal-shell preferences, and portable artifact generation. These are separate downstream features, not prerequisites for native RPC. No Android references to `todo`, `agentInstructions`, `terminalShell`, or `recencyAnchor` were found in this assessment. Sources: [downstream orchestration](../../apps/t3code/packages/contracts/src/downstream/orchestration.ts), [downstream settings](../../apps/t3code/packages/contracts/src/downstream/settings.ts), [downstream projection mapping](../../apps/t3code/apps/server/src/downstream/Orchestration.ts).

Before deleting the server adapter, verify Agentic against an isolated, unmodified upstream server: pairing, config decoding, shell/thread subscriptions, send/interrupt, approval and question responses, reconnect and snapshot replacement, workspace file access, worktree choices, terminal lifecycle and activity reporting. Pin the upstream revision used for fixtures and compatibility checks. Keep the existing provider-neutral repository/service/Compose boundary; the implementation seam to maintain is the Android upstream transport and DTO adapter.

## Original assessment validation

Only this research document changed. The repository-required Android gate passed in order: `ktlintFormat`, `ktlintCheck`, then `build` (including existing unit tests). The existing debug APK installed and launched successfully on the connected device. These checks establish the current Android baseline; they do not establish compatibility with a running stock upstream server.

## Implementation verification (2026-10-04)

Agentic now uses the upstream descriptor without fork-only capability flags. File browsing,
worktree choices, thread deletion and activity reporting no longer depend on those flags. Provider
display names are optional. Native configuration subscriptions refresh provider catalogs and
settings and reconnect when the subscription ends.

Thread subscriptions opt into the advertised pagination and reasoning support. Older pages merge
without replacing live message text or advancing the stream cursor. A page waits for the native
thread event watermark; changed focus, snapshot replacement and history rewrites invalidate stale
requests. Shell metadata updates the conversation separately from its content stream.
Native resolution activities close approval and question prompts, including replies from other
clients and requests loaded later from history. Stale-request failures close the prompt while
transient failures retain it.

The new project dialog uses `projects.createNew` and `projects.ensureScratch` when advertised.
An initial Git commit failure preserves the newly created project and displays a notice. The
portable server configuration route, SSE routes, service wrapper, contract exports, generated
schemas and generator were removed from the fork. Its independent todo and terminal settings
features remain.

[UpstreamServerCompatibilityTest](../../apps/android/app/src/test/kotlin/de/chennemann/agentic/data/t3/UpstreamServerCompatibilityTest.kt)
passed against an isolated, unmodified checkout of `54084ae1e6c32809db040e4fa571c80fdf2d8ae4`.
It exercised discovery, pairing, authenticated config and config subscriptions, Scratch creation
and reuse, new project creation, shell and thread subscriptions and replay, windowed HTTP thread
reads, workspace files, repository refs, activity reporting, terminal open/attach/write/clear/close,
and thread creation, rename and deletion. The server used disposable state outside a Git repository
so Scratch was advertised. It never used the installed server's data.

Run this opt-in check only against disposable server state; it creates projects and directories.
Supply the reusable development bootstrap credential through a local file outside the repository:

```bash
AGENTIC_T3_TEST_URL=http://127.0.0.1:47381 \
AGENTIC_T3_TEST_TOKEN_FILE=C:/path/to/disposable-bootstrap-token \
./apps/android/gradlew -p apps/android :app:testUitestUnitTest \
  --tests de.chennemann.agentic.data.t3.UpstreamServerCompatibilityTest
```

The default suite skips this opt-in check. Deterministic Android tests cover history merge ordering,
watermark waiting, snapshot invalidation, config reconnects, metadata cache restoration, and project
partial success. Native server tests cover replay and snapshot replacement after adapter retirement.
No live provider turn, approval or question lifecycle was exercised by the stock-server check;
those retain the existing command and reducer test coverage.

The Android gate passed in order: `ktlintFormat`, `ktlintCheck`, then `build`. The resulting debug
APK installed and launched successfully on the connected Android device. T3 contracts and server
typechecks, targeted lint, environment descriptor tests, and the native replay/snapshot tests
passed. The integration policy covers all changed application paths; the pre-existing deleted
`.repos` references were preserved and excluded from the server commit.
