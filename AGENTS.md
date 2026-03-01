# Agentic - A native opencode client for Android

## Project Overview

Agentic is a native opencode client for Android built with:

- **Kotlin 2.3.0** - Modern Kotlin with latest features
- **Jetpack Compose** - Declarative UI with Material 3
- **SQLDelight** - Type-safe SQL with Kotlin code generation
- **KTLint** - Kotlin formatting and style checks
- **Koin** - Lightweight dependency injection
- **Navigation 3** - Type-safe navigation with serializable routes

## Quick Reference Commands

```bash
# Build & Run
./gradlew clean build assembleDebug # Build debug APK
./gradlew installDebug && adb logcat --clear && adb shell am start -W -n de.chennemann.agentic/.MainActivity  # Install & Start app on device

# Logcat
adb logcat -d | grep -E "de\.chennemann\.opencode\.mobile|AndroidRuntime" # Check for errors when asked

# Code Quality
./gradlew ktlintCheck            # Check Kotlin style
./gradlew ktlintFormat           # Auto-fix style issues

# Testing
./gradlew clean test                   # Run unit tests

# Releases
./gradlew createBaselineTag            # Create next manual baseline tag (v<major>) at HEAD; tag push remains manual
keytool -genkeypair -v -keystore app/release.keystore -alias agentic -keyalg RSA -keysize 2048 -validity 36500 -storepass changeit -keypass changeit -dname "CN=Agentic, O=Agentic"  # Generate Android release keystore (replace passwords)
base64 app/release.keystore | gh secret set KEYSTORE_BASE64 && gh secret set KEYSTORE_PASSWORD -b"changeit" && gh secret set KEY_ALIAS -b"agentic"  # Upload signing secrets for GitHub Actions

# OpenAPI source
sh script/setup-opencode-submodule.sh  # Initialize/update opencode submodule with sparse API/DTO checkout

# Icons
bun run mobile:add-icon --source lucide --name pin --target PinLucide  # Generate ImageVector icon into app icons pack

# Database
# SQLDelight generates code in build/generated/sqldelight/
./gradlew generateSqlDelightInterface
```

## Critical Rules

### Releases

1. **Baseline tags are manual only** – Use `v<major>` tags (for example `v1`, `v2`)
2. **CI release tags are automatic** – Workflow publishes `v<major>.<commits_since_baseline>`
3. **Release APKs must be signed in CI** – Configure `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` GitHub secrets

### Architecture

1. **UI layer has NO business logic** – ViewModels only transform data for display
2. **Repositories are the single source of truth** – UI observes via Flow
3. **Services orchestrate the business logic** – Fully tested
4. **Type-safe navigation** – All routes use sealed interface with @Serializable
5. **Koin for DI** – No manual instance creation

### Compose

1. **State ownership by lifetime** – Hoist business/app/shared state to ViewModel; keep ephemeral presentation-only state (expand/collapse, local menus, focus, selection) in composables with `remember`; use `rememberSaveable` only when UI restoration is explicitly desired
2. **Unidirectional data flow** – State down, events up
3. **Material 3** – Use M3 components, theming, and adaptive layouts

### SQLDelight

1. **Schema in .sq files** – All tables defined in sqldelight/ directory
2. **Queries return Flow** – Use asFlow() for reactive updates
3. **Mapper functions** – Map database models to domain models in repository
4. **schema migrations** – ALWAYS add a new migration file for each schema change

## Task Finalization

### 1. Quality Gate (Mandatory — Gradle)

After completing the task, perform the following steps **in order**:

1. **Run linting**

    ```bash
    ./gradlew ktlintFormat
    ```

    - Fix any linting errors.
    - Re-run linting until it passes with zero issues.

2. **Run a clean build with all tests**

    ```bash
    ./gradlew build
    ```

3. **Failure handling**
    - If any tests fail:
        - Fix **only** the issues required to make tests pass.
        - Re-run:
            ```bash
            ./gradlew ktlintFormat
            ./gradlew build
            ```
    - Repeat until both linting and tests pass cleanly.

### 2. Completion Criteria (Stop Condition)

Stop when **all** of the following are true:

- The assigned task is fully implemented.
- `./gradlew ktlintCheck` passes with zero errors.
- `./gradlew build` passes with all tests green.
- **No additional tasks are started or modified.**

### 3. Create the Commit (Exactly One)

Use the following commit message format:

```text
<type>(<component>): <description>

Context:
- Why the change was made

Changes:
- Bullet list of what changed
```

### 4. Device Launch Requirement (Mandatory)

If an Android device is connected, always install and launch the app as part of task completion.

- Do not stop after tests/build only.
- Always run install + launch on the connected device.
- If install or launch fails, report the failure and reason in the final response.

---

Once all is done, **stop immediately**.
Do **not** suggest next steps, and do **not** continue with any additional work.
