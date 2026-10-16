# Android execution sessions

The terminal ports the native Android terminal from T3 Code mobile, using Ghostty's
`libghostty-vt`, its JNI snapshot bridge, Kotlin Canvas renderer, selection/scrolling,
and bundled MesloLGS Nerd Fonts. The pinned revision, complete local adaptation patch,
source hashes, and update procedure are documented in
[`apps/android/t3-terminal`](../apps/android/t3-terminal/README.md).

The Compose adapter applies Agentic's current light/dark surface and text colors,
uses the selected accent for the cursor, and supplies a contrasting ANSI palette.
Terminal text follows the application's interface and code-size settings. Styling
uses the upstream view's existing properties; the vendored renderer is unchanged.

T3 owns the shell process. Android opens/attaches ordinary execution sessions, feeds
raw output once into the retained native session, forwards ordered raw input without
adding Enter, and sends measured dimensions through `terminal.resize`. Compose receives
presentation models rather than RPC DTOs. The navigation-scoped ViewModel retains native
state across Activity/view recreation; reconnect or session replacement replays the
server's attach snapshot into a fresh native session. No Activity or Android View is
retained. Leaving the screen detaches the client and releases native resources;
**Close terminal** explicitly stops the remote shell.

Input writes are ordered and bounded. Oversized writes split without dividing UTF-16
surrogate pairs. Resize requests are coalesced and serialized. Obsolete screens cannot
write to a newly selected session. Duplicate output sequences are ignored; sequence gaps
and transport failures disable input and expose Reconnect. Reconnect refreshes environment
context and credentials, then replaces history rather than appending it twice.

Native parsing runs off the UI thread under a lifetime lock shared with rendering,
selection, resizing, and close. A conflated revision flow refreshes the Canvas without
dropping output chunks. History replay discards historical device-query responses.
Ghostty bounds scrollback to 10,000 lines, matching upstream. T3's bounded raw history
remains authoritative when reattaching; discarded server history cannot be recovered.

Tap the terminal or Keyboard to type. The key strip provides one-shot Ctrl/Alt, Esc,
Tab, arrows, Home/End, paging, Ctrl+C, selection, copy, and paste. Long press uses the
upstream selection handles and copy toolbar. Multiline paste requires confirmation.
As in upstream mobile, paste strips unsafe control characters, normalizes newlines to
CR, and does not add bracketed-paste markers. The upstream Android view does not expose
application-cursor mode to the toolbar and has no pinch magnification. These differ from
the previous ConnectBot integration and are tracked in the port's README.

More contains reconnect, clear, restart, and close. Restart/close require confirmation
before stopping the process. Clear erases the current screen and scrollback without
replacing native state. Menus preserve IME focus to avoid incidental PTY resizing.
Remote clipboard writes and inline images have no application callbacks.

## Validation

Focused JVM tests cover input ordering, Unicode chunk boundaries, snapshot replacement,
session isolation, sequence deduplication/gaps, resize serialization, exit, removal,
reconnect, and the translated upstream control/paste encoding. Native instrumentation
covers the shipped Ghostty JNI and Canvas, split ANSI sequences, Unicode, alternate
screens, bounded scrollback, selection, clear, historical-query suppression, view
recreation, input disabling, IME commit/delete/Enter/control input, toolbar keys, menu
size stability, and concurrent parsing/close. Navigation tests check entry resource
release and state restoration.

Run instrumentation on an explicitly selected Android 16+ emulator:

```bash
ANDROID_SERIAL=emulator-5580 ./apps/android/gradlew -p apps/android \
  :app:connectedUitestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.chennemann.agentic.ui.terminal.TerminalEmulatorTest
```

A paired T3 environment is still needed for live server/shell acceptance: exercise
shell input, a full-screen program, keyboard show/hide, rotation, output while scrolling,
selection/copy, disconnect/reconnect, tab switching, and repeated restart/close. The
2026-10-04 live acceptance results for the previous ConnectBot renderer do not establish
Ghostty acceptance. The current port is tested independently with local native output.
