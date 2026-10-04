# Android execution sessions

The terminal uses ConnectBot termlib 0.3.10 (libvterm) for screen emulation, rendering,
selection, scrolling, temporary pinch magnification, hardware keys, and Android IME input. The pinned source
and necessary native-lifetime patch are documented in
[`apps/android/terminal-emulator`](../apps/android/terminal-emulator/README.md).

T3 continues to own the shell process. Android opens/attaches ordinary execution sessions,
passes output directly into the emulator, forwards raw input without adding Enter, and
sends measured rows/columns through `terminal.resize`. Compose receives presentation
models rather than RPC DTOs. The ViewModel retains the current emulator across Compose
recreation; changing sessions or reconnecting replaces it from the server's attach snapshot.
Leaving the screen detaches the client; **Close terminal** explicitly stops the remote shell.
Navigation scopes the terminal ViewModel to its back-stack entry: rotation retains the
session, while leaving releases the native screen and stream subscriptions.

Input writes are ordered and bounded. Oversized writes are split without dividing UTF-16
surrogate pairs. Native UTF-8 input callbacks use an incremental decoder. Resize requests
are coalesced and serialized, including while an earlier RPC is suspended. Obsolete screens
cannot write to a newly selected session. Duplicate output sequences are ignored; sequence
gaps and transport failures disable input and expose Reconnect. Reconnect refreshes the
environment context and credentials, then replaces history rather than appending it twice.

The emulator bounds scrollback to 1,000 lines. History replay does not send historical device
query responses to the foreground program. Native parsing runs off the UI thread, independent
of Compose frame conflation. T3's bounded raw history is authoritative when reattaching;
content already discarded by the server cannot be recovered locally.

Tap the terminal or Keyboard to type. Swipe the key strip for Ctrl/Alt, Esc, Tab, arrows,
Home/End, paging, Ctrl+C, selection, copy, and paste. Ctrl/Alt latch for one key. Long press
uses the emulator's native selection controls. Multiline paste asks for confirmation and
uses the application's bracketed-paste mode. More contains reconnect, clear, restart, and
close; restart/close ask before stopping the process. Remote clipboard writes and inline
images remain disabled.
Clear erases the screen and local scrollback without resetting application key modes or
replacing the input view. The menu preserves IME focus so it cannot trigger a PTY resize
and restore cleared content through a server redraw.

## Validation

Focused JVM tests cover raw input ordering, Unicode chunk boundaries, snapshot replacement,
session isolation, sequence deduplication/gaps, resize serialization, exit, removal, and
reconnect. The WebSocket test verifies actual `terminal.resize` and raw `terminal.write`
payloads against the upstream Effect RPC envelope.

`TerminalEmulatorTest` runs the shipped JNI code and Android input connection. It covers
ANSI/cursor/erase behavior, alternate screens, Unicode, application cursor keys, control keys,
bracketed paste, bounded scrollback, historical-query suppression, IME commit/delete, and
idempotent native cleanup with queued work. Additional tests cover clear, pinch magnification,
menu/IME size stability, navigation restoration, and navigation-entry resource release.
Run on Android 16 or newer:

```bash
./apps/android/gradlew -p apps/android :app:connectedUitestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.chennemann.agentic.ui.terminal.TerminalEmulatorTest
```

Device acceptance also needs a paired T3 environment: exercise a shell, `less`, `vim` or
another full-screen program, keyboard show/hide, rotation, zoom, scrollback during continuing
output, selection/copy, disconnect/reconnect, tab switching, and repeated restart/close.
On 2026-10-04, acceptance testing used Android 16 (API 36, x86_64) and a paired T3
Windows environment. Live checks exercised shell input, Vim editing and rotation,
keyboard resizing (24 versus 46 rows), 1,500 lines of output and scrollback, isolated
session variables, Ctrl+C interruption, reconnect, clear, exit, and restart. Native JNI
tests also ran on Android 17 (API 37, x86_64) with 16 KB memory pages. The final Android 16
run passed all 12 terminal/navigation instrumentation tests. Repeated restart/close and
leaving/reopening a live shell passed; the debug APK installed and launched with no crash
log entries. The full Android quality gate passed (260 JVM tests passed, one opt-in test
skipped). Emulator validation
does not cover every physical keyboard, vendor IME, or server shell combination.
