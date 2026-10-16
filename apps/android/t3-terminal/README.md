# T3 Code Android terminal

Port of `apps/mobile/modules/t3-terminal/android` from T3 Code revision
`3e6b45028ceec5820dacb37dc3852470ebdc9411`. The upstream package names, filenames, and
formatting are retained so related fixes can be compared and applied directly.
Text line endings are normalized to LF; binary files are copied byte for byte.

`GhosttyBridge.kt`, `TerminalFrame.kt`, `TerminalCanvasView.kt`, and
`t3_terminal_jni.cpp` are unmodified. The four ABI libraries, C headers, fonts,
Ghostty build script and its Android patches are also copied without changes.
Ghostty is pinned to `9f62873bf195e4d8a762d768a1405a5f2f7b1697`.
`upstream.json` records source paths and SHA-256 hashes. Gradle verifies all unchanged
artifacts before building. Builds use only files in this module, with NDK
29.0.14206865 and CMake 3.22.1; no sibling checkout or Expo runtime is required.

## Local adaptation

`upstream.patch` records every change to copied files:

- `T3TerminalView` extends `FrameLayout` and uses Kotlin callbacks instead of Expo
  events. Expo module registration is omitted; Compose hosts the view with `AndroidView`.
- Native state belongs to the additional `GhosttySession` class. The navigation-scoped
  ViewModel retains it across Activity/view recreation, without retaining Android Views.
  `cleanup()` releases only view callbacks/selection; the owner explicitly closes the
  session. A shared lock serializes native calls, background parsing, and destruction.
- Output feeds the session once per chunk rather than retaining and diffing a growing
  JavaScript `initialBuffer`. A conflated revision flow refreshes the Canvas without
  conflating parser input. Historical replies are discarded before live input begins.
- Kotlin callbacks expose selection, keyboard, resize replies, paste requests, and input
  availability. `TerminalInputView` handles IME backspace after the local editor clears,
  and routes native paste through the same confirmation as the Compose toolbar.
- CMake's header search path points at this module's vendored headers.

`GhosttySession.kt` and `TerminalInputView.kt` are Agentic-owned adapters. Changes to
these files do not need to be reapplied to upstream. The app's `TerminalInput.kt` ports
the control and paste encoding from `reference/terminalInput.ts`. Paste is sanitized
and unbracketed, matching upstream mobile; the native view does not expose DECSET 2004
or application cursor mode to toolbar encoding. The upstream Android view has no pinch
magnification. Keep these limits explicit when evaluating future upstream changes.

## Applying upstream patches

From the Agentic repository root, with an independent T3 checkout containing the pinned commit:

```bash
python apps/android/t3-terminal/check-upstream.py apps/t3code
```

For a new baseline, compare the terminal's upstream commits since `upstream.json`'s
revision, update the manifest revision, paths, and source hashes, copy unchanged files,
and merge the view changes using `upstream.patch`. Keep bridge, JNI, headers and native
libraries from the same baseline. Copy the complete font and notice set when updating
fonts. After reviewing the new local differences:

```bash
python apps/android/t3-terminal/check-upstream.py apps/t3code --write-patch
python apps/android/t3-terminal/check-upstream.py apps/t3code
```

This records the reviewed adapter delta and regenerates the build's artifact hashes.
Never regenerate hashes to hide an unexpected binary change. Upstream's original
`vendor/build-libghostty-android.sh` is retained as build provenance; run it in the
matching T3 checkout on a supported host to rebuild binaries, then copy the artifacts.
It is not part of the Android build and its original T3-relative paths are unchanged.

Run focused terminal JVM and JNI instrumentation tests, then the repository's
`ktlintFormat`, `ktlintCheck`, and `build` gate. Instrumentation uses
`de.chennemann.agentic.ui.terminal.TerminalEmulatorTest` on Android 16+.
MIT and Apache notices are packaged in the app's `assets/licenses` directory.
