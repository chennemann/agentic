# Embedded Android terminal research

Investigated 2026-10-04 for Agentic's remote T3 PTY terminal. These are library-selection findings, not a claim that the application integration has passed device validation.

## Recommendation

Prefer **ConnectBot termlib**, subject to the application's build and device checks. It supplies a native Compose terminal backed by libvterm, with an explicit transport-independent input/output boundary. This avoids maintaining an emulator, renderer, IME implementation, or a local-process adaptation. Maven Central publishes `org.connectbot:termlib:0.3.10` as an AAR. The license is Apache-2.0; the underlying libvterm is MIT. [Project](https://github.com/connectbot/termlib), [published POM](https://repo.maven.apache.org/maven2/org/connectbot/termlib/0.3.10/termlib-0.3.10.pom), [README](https://github.com/connectbot/termlib/blob/0.3.10/README.md).

The current release requires evaluating a broader Android dependency update: its published POM specifies Kotlin 2.4.20 and Compose BOM 2026.09.00. The source builds with compileSdk 37 and minSdk 24. Agentic's starting versions are Kotlin 2.3.10 and Compose BOM 2026.01.01. Do not hide incompatibilities with metadata-version suppression. [Published dependencies](https://repo.maven.apache.org/maven2/org/connectbot/termlib/0.3.10/termlib-0.3.10.pom), [library build](https://github.com/connectbot/termlib/blob/0.3.10/lib/build.gradle.kts).

## Comparison

| Candidate | Remote PTY integration | Android interaction | Distribution and tradeoff |
| --- | --- | --- | --- |
| ConnectBot termlib | Factory callbacks for output and size; feed incoming bytes directly | Native Compose terminal, IME, hardware keys, selection, scrolling and zoom | Maven Central AAR; Apache-2.0/MIT; current dependencies need build validation |
| Termux terminal-emulator + terminal-view | Emulator accepts an abstract output sink; view expects concrete local `TerminalSession` | Established Android input connection, renderer, selection and gestures | Android library modules exist, but view/session seam must be adapted; repository GPLv3-only declaration has inherited Apache-code exceptions |
| xterm.js in WebView | `write`, `onData`, `onBinary`, `onResize`, `paste` fit a remote PTY | Browser terminal, requiring Android WebView/IME/selection integration | MIT; adds JavaScript assets and a native/browser bridge |

Sources: [ConnectBot component](https://github.com/connectbot/termlib), [Termux view](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java), [Termux license](https://github.com/termux/termux-app/blob/master/LICENSE.md), [xterm API](https://xtermjs.org/docs/api/terminal/classes/terminal/), [xterm license](https://github.com/xtermjs/xterm.js/blob/master/LICENSE).

## ConnectBot embedding API

`TerminalEmulatorFactory.create` accepts `onKeyboardInput: (ByteArray) -> Unit` and `onResize: (TerminalDimensions) -> Unit`. Feed server output through `writeInput(bytes)`. `pasteText(text)` uses bracketed-paste handling; `dispatchKey` and `dispatchCharacter` generate terminal input. Leave `onClipboardCopy` absent to avoid automatic remote OSC 52 clipboard writes. Inline images default to off. [0.3.10 emulator source](https://github.com/connectbot/termlib/blob/0.3.10/lib/src/main/java/org/connectbot/terminal/TerminalEmulator.kt).

The `Terminal` composable takes an emulator and provides keyboard enablement, IME visibility callbacks, paste requests, selection-controller access, font sizing, and optional forced dimensions. Its viewport computes terminal size; forward dimensions to T3 rather than estimating character cells outside the renderer. [0.3.10 Compose entry point](https://github.com/connectbot/termlib/blob/0.3.10/lib/src/main/java/org/connectbot/terminal/Terminal.kt).

The public emulator does not expose deterministic disposal. Its internal native object implements `AutoCloseable` with finalizer fallback, but JNI owns a global reference to the emulator callbacks, which in turn own the native wrapper. Dropping references alone cannot collect that cycle. Agentic therefore vendors the pinned Kotlin sources with an explicit, synchronized close operation and closed-state guards, while reusing checksum-verified upstream JNI binaries. See [the local lifecycle patch](../../apps/android/terminal-emulator/README.md). [Native lifetime](https://github.com/connectbot/termlib/blob/0.3.10/lib/src/main/java/org/connectbot/terminal/TerminalNative.kt), [dispatcher](https://github.com/connectbot/termlib/blob/0.3.10/lib/src/main/java/org/connectbot/terminal/TerminalDispatcher.kt).

Older published versions offer different dependency baselines, but need their own API and behavior checks:

| Release | Kotlin | Compose BOM |
| --- | --- | --- |
| [0.3.10](https://repo.maven.apache.org/maven2/org/connectbot/termlib/0.3.10/termlib-0.3.10.pom) | 2.4.20 | 2026.09.00 |
| [0.2.1](https://repo.maven.apache.org/maven2/org/connectbot/termlib/0.2.1/termlib-0.2.1.pom) | 2.3.21 | 2026.09.00 |
| [0.1.0](https://repo.maven.apache.org/maven2/org/connectbot/termlib/0.1.0/termlib-0.1.0.pom) | 2.3.21 | 2026.05.01 |
| [0.0.20](https://repo.maven.apache.org/maven2/org/connectbot/termlib/0.0.20/termlib-0.0.20.pom) | 2.3.10 | 2026.02.00 |

## Termux findings

Termux's emulator constructor accepts `TerminalOutput`, rows/columns, cell dimensions, transcript capacity and client callbacks. Incoming byte chunks go to `append`; resizing updates the emulator. It implements separate normal/alternate buffers, terminal modes, mouse reporting and bracketed paste. This core can serve a remote transport without a local shell. [Emulator](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java), [output abstraction](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalOutput.java).

However, `TerminalView.attachSession` accepts `TerminalSession`, which creates a native subprocess and owns PTY queues. Reusing the full view therefore requires a maintained remote-session adaptation. The emulator module's normal build also includes JNI, so taking its AAR solely for the parser carries local-process machinery. [Session](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java), [view](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java), [module build](https://github.com/termux/termux-app/blob/master/terminal-emulator/build.gradle).

Do not assume the entire modified Termux terminal implementation is Apache-2.0 because some inherited files are. Its root license explicitly declares GPLv3-only and identifies inherited Android Terminal Emulator code as an exception. This is a reason to prefer the independently published permissive ConnectBot component, not a legal conclusion about a particular distribution. [Termux license](https://github.com/termux/termux-app/blob/master/LICENSE.md).

## Integration acceptance criteria

These are engineering recommendations for Agentic, derived from the remote-stream use case:

- Preserve raw terminal data and ordering. Never strip ANSI codes or append already-consumed snapshots a second time.
- Keep one emulator per live session across tab switches. Distinguish reconnect snapshots from incremental output; reset only for a new generation or explicit replay replacement.
- Send emulator-generated replies as well as user keystrokes through the same ordered transport queue.
- Resize from actual drawable dimensions, including keyboard appearance, rotation and zoom. Avoid stale resize responses overriding a newer size.
- Disable user input when the remote session cannot accept it and surface transport failures.
- Validate soft and physical keyboards, control/alt modifiers, arrows in application mode, Unicode, bracketed paste, selection/copy, scrollback, alternate-screen applications, reconnect and tab switching on a device.
- Exercise parser boundaries with split UTF-8 and escape sequences and test large output for bounded retention and UI responsiveness.

No library choice alone establishes these application-level guarantees.
