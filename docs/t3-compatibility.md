# T3 Code compatibility

Agentic targets [T3 Code v0.1.5-fork.1](https://github.com/chennemann/t3code/releases/tag/v0.1.5-fork.1),
commit `0161bb6f5c57c623fc4f2a77f69e45fbfd70b8f6`, based on upstream v0.1.5
(`6d450a4284852af137b2f3407fddd89eb76e7644`). The machine-readable reference is
[`t3-contracts/upstream.json`](../apps/android/t3-contracts/upstream.json).
The release tag resolves to that fork commit; both the recorded compatibility and
release workflows succeeded at that commit.

Agentic consumes the authenticated HTTP and WebSocket APIs, using orchestration protocol 2.
`apps/t3code` is an ignored, independent development checkout, not a submodule, bundled server,
or Android build dependency. Updating compatibility does not require checking it out at the
release or replacing newer work there. The composer, diff viewer, and terminal's separate
`upstream.json` files record the provenance of vendored native code; they are not server pins.

## v0.1.4 changes

- Read settings from `server.getConfig.settings`. The removed `server.getSettings` RPC is no
  longer used by server settings, guest sharing, or usage refresh. Raw settings are retained
  for project override writes so unknown server fields survive.
- Subscribe only to `orchestration.subscribeShell`. Active and archived rows share its
  snapshot, updates, removals, and resume sequence. Replacement snapshots discard stale
  archived rows. The removed archive subscription is not attempted.
- Restart a terminal by awaiting `terminal.close` and then `terminal.open`, preserving the
  terminal ID, workspace, and runtime environment. The removed `terminal.restart` RPC is
  not attempted.
- Provider instance IDs and server-advertised models remain authoritative. The fork now uses
  upstream ACP Registry/local ACP support. Existing custom-connector settings remain readable
  on the server; old custom-connector sessions are not automatically converted. Select a
  server-advertised ACP instance for new conversations.

This update preserves the existing client behavior for shell selection, image comments and
galleries, client certificates, and unknown additive activities. It does not reinstate removed
fork JJ, to-do, recency, or managed Workspace features. New upstream product features are not
implied by protocol compatibility.

Upstream v0.1.5 adds an optional project-from-thread RPC, thread-detail display settings, and
optional update-track fields. Existing Agentic RPC payloads remain valid and additive fields
remain tolerated. This compatibility pin does not add those new controls to the Android UI.

## Validation and delivery

Use JDK 25, Android SDK 37, NDK `29.0.14206865`, and CMake `3.22.1`. The Gradle wrapper resolves
dependencies independently of T3. Local SDK configuration stays in ignored `local.properties`.

```bash
./apps/android/gradlew -p apps/android :t3-contracts:test
./apps/android/gradlew -p apps/android :app:testUitestUnitTest
./apps/android/gradlew -p apps/android ktlintFormat
./apps/android/gradlew -p apps/android ktlintCheck
./apps/android/gradlew -p apps/android build
```

The transport regression covers ordered terminal close/open and the single shell subscription;
service tests cover settings and sharing, and reducer tests cover archived deletion/reconnects.
Before installing on a physical phone, launch the debug APK on an isolated Android emulator,
including startup with a registered environment. A fresh pairing screen alone does not exercise
the chat composer. Its blur regression guards against moving focus into an unfocused native
editor during Compose layout, which can crash cached-state startup.
The send lifecycle regression covers clearing a focused draft and selecting the newly created
thread. Keep the native editor attached across draft identities: disposing a focused Android
view inside the pager can re-enter Compose layout during focus search. Reset document state
and undo history when switching drafts while preserving the native view's event sequence.
`UpstreamServerCompatibilityTest` is opt-in and requires a disposable server configured through
`AGENTIC_T3_TEST_URL` and `AGENTIC_T3_TEST_TOKEN_FILE`, plus an enabled model. Never point a test
server at live `~/.t3/userdata`. Unit checks do not establish real-model or live-server behavior.

Pushing an update branch runs CI verification. Signed APK publication is the existing workflow
triggered by pushes to `main`; a compatibility update does not install or redeploy a server.
The Stor Gradle plugin and updater SDK use published `0.4.1` artifacts. Both Gradle plugin and
dependency repositories require GitHub Packages read credentials; local Maven snapshots are
not sufficient evidence that a clean CI build can resolve these dependencies.

Stor [v0.4.1](https://github.com/chennemann/stor/releases/tag/v0.4.1) is pinned to source
`571f9b46a662bb9c0c85a9e1a5613a4f4f516fa9`; its certificate APIs are unchanged from 0.4.0.
