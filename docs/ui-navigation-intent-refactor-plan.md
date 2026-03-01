# UI Navigation + Intent Contract Refactor Plan

## Scope

This plan covers only the UI layer refactor in `packages/mobile/app`:

- Navigation contract and routing behavior
- Screen -> ViewModel event contracts
- Naming and ownership boundaries

Out of scope:

- Splitting features/modules
- Data/domain architecture decomposition
- `SessionService` refactor

Constraints:

- Keep single-module app architecture (solo-dev friendly)
- Preserve existing behavior while improving contract clarity
- Execute as one big-bang refactor (single developer workflow)

---

## Current-State Diagnosis

### Navigation

- Routes are type-safe but minimal in `app/src/main/kotlin/de/chennemann/agentic/navigation/AppRoute.kt`.
- Navigation output from ViewModels is destination-first (`ToManage`, `ToConversation`, `ToLogs`, `Back`) in `app/src/main/kotlin/de/chennemann/agentic/navigation/NavEvent.kt`.
- `AppNavHost` performs route mutation inline per-screen with repeated nav collectors in `app/src/main/kotlin/de/chennemann/agentic/navigation/AppNavHost.kt`.

### Screen Contracts

- Contracts are centralized (good) in:
    - `app/src/main/kotlin/de/chennemann/agentic/ui/conversation/ConversationContract.kt`
    - `app/src/main/kotlin/de/chennemann/agentic/ui/manage/ManageContract.kt`
    - `app/src/main/kotlin/de/chennemann/agentic/ui/logs/LogsContract.kt`
- Many event names encode UI mechanics (`*Tapped`, `*LongPressed`) instead of user intent.
- ViewModels already perform orchestration correctly, but naming weakens API semantics and test readability:
    - `ConversationViewModel.kt`
    - `ManageViewModel.kt`
    - `LogsViewModel.kt`

### Tests

- Good coverage for conversation/manage:
    - `app/src/test/kotlin/de/chennemann/agentic/ui/conversation/ConversationViewModelTest.kt`
    - `app/src/test/kotlin/de/chennemann/agentic/ui/manage/ManageViewModelTest.kt`
- Missing dedicated logs VM test coverage for contract migration.

---

## Target Principles

1. **Intent-first events**
    - Screen events describe user intent, not gesture type.
    - Example: `MessageSubmitted` over `SendTapped`.

2. **Clear contract boundaries**
    - Screens emit `UiAction` only.
    - ViewModels translate `UiAction` into domain calls and optional nav output.
    - Nav host owns back stack mutation policy.

3. **Navigation as typed app action**
    - Replace destination-specific nav outputs with a single action model: `NavigateTo(route)` and `NavigateBack`.
    - Keep routes type-safe and let `AppNavHost` own all stack mutation.

4. **No external hoisting for UI-only state**
    - Keep ephemeral presentation state local to composables/UI surface when it is not business state.
    - Avoid external state hoisting for local interactions to reduce unnecessary render churn and keep business/UI responsibilities distinct.

5. **Single-pass migration**
    - Ship the full refactor in one coherent change set (no transitional aliases).
    - Preserve behavior and test assertions while renaming contracts.

---

## Proposed Contract Model

## 1) Screen -> ViewModel input

Use intent naming:

- `Requested`: user asks to do something
- `Changed`: value mutation from user input
- `Selected`: explicit choice
- `Toggled`: binary state intent
- `Submitted`: finalized textual action

Avoid:

- `Tapped`, `Clicked`, `Pressed`, `LongPressed` (gesture-level wording)

## 2) ViewModel -> NavHost output

Use typed navigation actions:

- `NavigateTo(route: AppRoute)`
- `NavigateBack`

Common examples:

- `NavigateTo(WorkspaceHubRoute)`
- `NavigateTo(AgentChatRoute)`
- `NavigateTo(LogsRoute)`

`AppNavHost` remains sole owner of stack operations and route transformations.

---

## Event Migration Map

| Screen       | Current Event                      | Proposed Intent Event                | Mapping Behavior                           |
| ------------ | ---------------------------------- | ------------------------------------ | ------------------------------------------ |
| Conversation | `OpenManageTapped`                 | `NavigateTo(WorkspaceHubRoute)`      | emit `NavigateTo(WorkspaceHubRoute)`       |
| Conversation | `ToolCallSessionTapped(sessionId)` | `SubsessionRequested(sessionId)`     | `openToolCallSession`                      |
| Conversation | `SendTapped`                       | `MessageSubmitted`                   | `service.send(...)`                        |
| Conversation | `ReloadTapped`                     | `RefreshRequested`                   | `service.refresh()`                        |
| Conversation | `LoadMoreMessagesTapped`           | `MoreMessagesRequested`              | `service.loadMoreMessages()`               |
| Conversation | `QuickSwitchMenuCreateTapped`      | `NewSessionRequested`                | create session                             |
| Manage       | `OpenProjectTapped`                | `LoadProjectRequested`               | select project                             |
| Manage       | `CreateSessionTapped`              | `SessionRequested(null)`             | create + emit `NavigateTo(AgentChatRoute)` |
| Manage       | `OpenSessionTapped(session)`       | `SessionRequested(sessionId)`        | open + emit `NavigateTo(AgentChatRoute)`   |
| Manage       | `OpenLogsTapped`                   | `NavigateTo(LogsRoute)`              | emit `NavigateTo(LogsRoute)`               |
| Manage       | `BackTapped`                       | `NavigateBack`                       | emit `NavigateBack`                        |
| Logs         | `BackTapped`                       | `NavigateBack`                       | emit `NavigateBack`                        |
| Logs         | `AddFilterFromRow(key, value)`     | `FilterAppliedFromEntry(key, value)` | apply row-derived filter                   |

Notes:

- `ManageEvent.Connect(url)` is already semantic and should stay.
- `SessionRequested(sessionId: String?)` uses `null` to represent "create new session".
- Keep these interactions UI-local (not ViewModel contract events): `QuickSwitchTapped`, `QuickSwitchLongPressed`, `QuickSwitchMenuDismissed`, `ProjectListToggleTapped`, `ClearFilter`.
- Do not externally hoist those UI-local states; keep them local to the screen/composable to improve render performance and keep business logic out of presentation state handling.

---

## Navigation Refactor Plan (No Feature Split)

### Navigation goals

- Keep current destination set, but rename routes for clarity:
    - `AgentChatRoute` (current `ConversationRoute`)
    - `WorkspaceHubRoute` (current `ManageProjectsRoute`)
    - `LogsRoute`
- Improve nav contract semantics and centralize stack policy.

### Planned shape

1. Replace destination-specific `NavEvent` variants with `NavigateTo(route)` and `NavigateBack`.
2. Refactor `AppNavHost` to use a single dispatcher helper for nav actions.
3. Consolidate "return to agent chat root" behavior in one function.
4. Remove old nav outputs in the same change set (no transition layer).

---

## Big-Bang Execution

Implement in one cohesive refactor:

1. Introduce the typed nav action contract (`NavigateTo(route)`, `NavigateBack`) and update `AppNavHost` dispatching.
2. Rename routes to `AgentChatRoute` and `WorkspaceHubRoute` (keep `LogsRoute`).
3. Migrate `ConversationEvent`, `ManageEvent`, and `LogsEvent` names to the intent model from this document.
4. Consolidate manage session open/create into `SessionRequested(sessionId: String?)`.
5. Keep UI-local interactions local (no new external state hoisting) and remove gesture-named contract events.
6. Update tests and remove all legacy names in the same change set.

---

## Testing Plan

## Unit tests

- Update existing tests for renamed events:
    - `ConversationViewModelTest`
    - `ManageViewModelTest`
- Add `LogsViewModelTest` for:
    - back navigation action
    - filter apply/remove/reset semantics
    - query/date filter updates

## Navigation behavior tests

Add focused nav wiring tests for:

- Manage -> AgentChat root (both `SessionRequested(null)` and `SessionRequested(sessionId)`)
- Conversation -> WorkspaceHub
- Manage -> Logs
- Logs -> Back
- Back stack stability from `AppNavHost`

## Regression checks

- Ensure same service calls and payloads before/after rename.
- Validate no behavior regression in quick switch, load-more, connect, and filters.

---

## Risk Mitigation

- Keep scope strictly UI + navigation contracts (no service/domain behavior changes).
- Make this change largely mechanical: rename events/routes and centralize nav dispatch without changing business flows.
- Keep UI-local state local and avoid introducing new hoisted/shared UI state.
- Validate with tests plus manual smoke for session open/create, manage/logs navigation, and filters.

Rollback strategy:

- Revert the single refactor commit if regressions are found.

---

## Done Criteria

1. All UI contract events are intent-named and gesture-agnostic.
2. All ViewModels emit only `NavigateTo(route)` / `NavigateBack` navigation actions.
3. `AppNavHost` is the only stack mutation authority.
4. No `Tapped/Pressed/Clicked/LongPressed` suffix remains in contract events.
5. Updated tests pass + new logs/nav tests are green.
6. No module/feature split introduced.
