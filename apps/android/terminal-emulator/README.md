# ConnectBot terminal component

Pinned Kotlin and resource sources from ConnectBot termlib **0.3.10**, commit
`194995476486117b1b32edf062a2655221068c28`:
https://github.com/connectbot/termlib/tree/0.3.10/lib/src/main

The build extracts the **unmodified native binaries** for all four Android ABIs from
`org.connectbot:termlib:0.3.10` on Maven Central. It verifies the AAR's SHA-256 before
extraction. No sibling checkout, JitPack build, local shell, or Android NDK is required.
Only `jni/` is extracted; the AAR's original Kotlin classes are not packaged.

## Local lifecycle patch

`TerminalEmulator.kt` adds `AutoCloseable`, idempotent `close()`, and closed-state
guards. Upstream's native terminal holds a JNI global reference to its Kotlin callbacks;
that object owns the native wrapper. Consequently, merely dropping the emulator or relying
on its finalizer leaves a reference cycle rooted in JNI. Session replacement must close it.

Close shares the existing session lock with parser, input, and snapshot work. It destroys
only an initialized native handle, cancels queued host callbacks, and makes late parser,
keyboard, resize, and snapshot work harmless. It does not shut down upstream's shared
dispatchers. The last immutable screen snapshot remains safe for a departing Compose view.
`lifecycle.patch` records the exact change against the pinned upstream file.

The application's native instrumentation tests include closing with pending input/snapshot
work, repeated close, and calls after close. Run them on an Android 16+ device:

```bash
./apps/android/gradlew -p apps/android :app:connectedUitestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.chennemann.agentic.ui.terminal.TerminalEmulatorTest
```

## Updating

Replace Kotlin/resources from one upstream tag and update the catalog coordinate and AAR
checksum together. Reapply the lifecycle patch, or remove it when upstream exposes an
equivalent supported close operation. Compare JNI callback signatures before using binaries
from a different tag. Run the Android quality gate and native instrumentation tests.

Keep upstream formatting in vendored sources to make updates reviewable. License and
copyright headers are retained; notices for ConnectBot (Apache-2.0) and libvterm (MIT)
are also packaged in the application's `assets/licenses` directory.
