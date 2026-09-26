# Android app refinements: lazy sessions, config Providers→Models, live status, server naming, live-sync notification

## Context

All seven items are solved inside `android/` (Kotlin + Compose, MVVM). No `server/` Go changes, no `docs/protocol.md` changes; `make check` stays green. Each user-facing change gets a `CHANGELOG.md` `[Unreleased]` entry. Android unit tests run with `./gradlew testDebugUnitTest` (in `android/`).

## Decisions (with rationale)

- **D1 — Session list "lazy load" = progressive disclosure over locally-cached Room data, NOT server-side pagination.**
  The phone's Room cache is the single source of truth; SSE `resync.required` semantics ("re-fetch all of `/sessions`") and `session.deleted`/status overlays require the full local set. Server pagination would be a protocol change with zero user-visible benefit. The user-facing symptom (long flat list after server select) is solved by rendering the first N rows and growing the window on scroll. Note: this constrains **rendering** (composition/recomposition proportional to the visible window); the Room flow still decodes the ordered list on emission, which is the same cost as today — an optional later optimization is a `LIMIT`-window DAO query (risks tab).
  **Confirmed refinement:** archived sessions are hidden by default (they are most of the "a lot of sessions" tail) behind a show-archived toggle; the lazy window applies after the active/archived filter. A first-composition fetch fills the list immediately after server select (live sync resync normally does this, but the screen shouldn't be blank when live sync is off).
- **D2 — Config screen hierarchy: Providers (overview) → per-provider Model list.** Agents/Commands/Skills remain on the overview below Providers. Companion fix: scope `Repository.configCache` per active profile (a single global 60 s cache today leaks the previous server's config into the new server's screens).
- **D3 — Live status has three independent root causes, all fixed:**
  - (a) `Repository.observeSessions()`/`observeSession()` decode `SessionDto` from `SessionEntity.rawJson`, which status-only SSE events never rewrite. The authoritative derived `status`/`waitingReason` **columns** (maintained by `EventApplier.refreshSessionStatus`) are ignored by the display path → badges stale until a full `GET /sessions` refetch.
  - (b) `LiveSyncService`'s `activeProfileId` collector calls `connectIfNeeded(id)`, which early-returns when a stream already exists → switching servers while live sync is on keeps streaming the OLD server into the old profile's Room rows.
  - (c) `EventApplier.apply` ignores `AppEvent.SessionIdle` (EventApplier.kt:79-82) — the protocol-defined "run finished" event — so a running→idle session stays "running" until a refetch/re-snapshot.
  - Safety net: while the stream is not `Connected`, a light 30 s list poll (mirrors the detail screen's 10 s live-edge poll).
- **D4 — Server naming:** optional "Server name" field in the connect wizard + a rename dialog on the Servers list. `Profile.name` already exists in DataStore and renders in the list header; `saveProfile` already merges by id and dedupes by `baseUrl`, so no schema/DB migration.
- **D5 — "Starting live sync…" notification:** it is an **FGS (foreground-service) notification**; Android mandates it stays visible while the service runs, so it cannot *fully* disappear on app open without disabling live sync (user-confirmed: full-hide-by-foregrounding is rejected as out of scope). The fix makes the text truthful: it now reflects stream state (`Connecting…` → `Live sync connected` → `Reconnecting…`) instead of a forever-stale "Starting live sync…" (user-confirmed: **live state text + tap-to-open**). Rejected alternative: auto-stop the service when app is foreground — kills background sync and violates the FGS contract.
- **D6 — Reported bugs: notification bodies show raw JSON; send button can get stuck / message silently dropped.**
  - **Notification body:** `LiveSyncService.notifyFor` passes `event.data?.toString()` — raw kilo JSON — into `Notifier.permissionAsked`/`questionAsked` (LiveSyncService.kt:189,193; question path reported). Fix: reuse the existing, already-tested readable extractors `permissionSummary()` / `questionText()` / `questionSummary()` (`data/model/Models.kt:252,266`), which is exactly what `ApprovalScreen` renders inline (ApprovalScreen.kt:86) — so notification text matches the approval sheet. The identical permission path is fixed too, even though only the question notification was reported.
  - **Send reliability:** three changes —
    (a) `SessionDetailViewModel.send()` wraps the network call in `try/finally` so `sending` can never stay `true` (today an unexpected throw/cancel leaves the button disabled forever);
    (b) failures are surfaced to the user — `Repository.guarded` emits `UiEvent.OperationResult` only for `ApiException` (Repository.kt:266) and nothing consumes it (MainActivity.kt:49-58 handles only CertChanged/ReauthRequired), so a failed send today is completely silent;
    (c) optimistic echo — `POST /message` returns the created kilo message (protocol-documented) and `ApiClient.sendMessage` currently discards it; storing it locally makes the sent message appear in the transcript even when live sync / SSE echo is off or lagging.

## Task list (ordered; execute top-down)

### Phase 1 — Live session status (D3)

1. **Fix live status end-to-end (display overlay + `session.idle` event gap).**
   - (a) Add pure mapper `fun SessionEntity.toDto(): SessionDto` (top-level in `data/local/Entities.kt` — constructs entity directly in tests, no Android deps) that decodes `rawJson` then overlays `copy(status = status, waitingReason = waitingReason)` from the entity columns. Use it in `Repository.observeSessions()` (Repository.kt:142-148) and `Repository.observeSession()` (Repository.kt:150-154) — `SessionDetailViewModel`'s header badge benefits automatically via `observeSession`.
   - (b) **`EventApplier.apply`: handle `AppEvent.SessionIdle`** — it is currently a no-op (EventApplier.kt:79-82). When kilo signals a finished run via `session.idle` (the protocol-defined completion event), call `statusComputer.onRawStatus(sessionId, "idle")` + `refreshSessionStatus(sessionId)` so a running→idle session updates its Row live instead of staying "running" until a full refetch (this is a second real contributor to "must tap reload to see status"). Add an `EventApplierTest` case for it.
2. **`service/LiveSyncService.kt`: switch streams on profile change.** In the `activeProfileId` collector (LiveSyncService.kt:63-75), when `id != profileId && id != null`: call `reconnect(id)` if a stream is alive (tear down + connect), `connectIfNeeded(id)` on first connect, `tearDown()+stopSelf()` on `null`. Existing `connectMutex` already serializes.
3. **Sessions-list stale poll (safety net).** `ui/screens/SessionsScreen.kt`: `LaunchedEffect(liveState)` polling `viewModel.refresh()` every ~30 s while `liveState != Connected` and the screen is composed.

### Phase 2 — Live-sync notification: FGS state text (D5) + approval/question body summaries (D6)

4. **`service/Notifier.kt`:** add `fun foregroundStateLabel(state: LiveStream.StreamState): String` (`Connecting → "Connecting to server…"`, `Connected → "Live sync connected"`, `Reconnecting → "Reconnecting…"`) and `fun updateForeground(text: String)` reusing the existing `foreground()` builder; optionally add a content intent to `MainActivity` (no extras → app root).
5. **`service/LiveSyncService.kt`:** in `connect()` set the `onState` callback to also `startForeground(NOTIF_LIVE, notifier.foreground(label))` on each state change (keep `LiveSyncState.update` for the UI). Race-guard: only update after the initial `startForeground` in `onCreate` (already running on the service thread).
   - **Accepted behavior (document in plan/PR):** the notification stays while live sync is on (Android FGS requirement); it now always reflects the actual state and disappears with the service when live sync is turned off/stopped.
6. **Permission/question notification bodies (reported bug).** `LiveSyncService.notifyFor` passes `event.data?.toString()` — raw JSON — as the notification text (LiveSyncService.kt:189,193). Replace with the existing tolerant extractors in `Models.kt`: `notifier.permissionAsked(..., event.data.permissionSummary())` and `notifier.questionAsked(..., event.data.questionText())` (questionText falls back to `questionSummary()` when the text is empty; keep the `?: "Permission requested"` / `?: "Question asked"` guards). Optionally cap the body at ~300 chars for notification-safe display. No `Notifier` signature changes — it already takes `text: String`; the approval *screen* already uses the same helpers, so notification text matches what `ApprovalScreen` renders.

### Phase 3 — Lazy-load sessions list (D1)

7. **`ui/viewmodel/SessionsViewModel.kt`:** add `visibleCount` (default `30`), `loadMore()` (page of `30`, capped); add `showArchived` (`MutableStateFlow<Boolean>`, default `false`) + `toggleShowArchived()`; expose `pagedSessions = combine(sessions, showArchived, visibleCount) { all, showArch, n -> all.filter { showArch || !it.isArchived }.take(n) }` with `distinctUntilChanged()`, plus `totalCount` (post-filter) and a `refreshing` flag set around `repo.fetchSessions()`. New pure helper `windowSessions(all: List<T>, visible: Int): List<T>` and `filterArchived(all, showArchived)` in a companion/pure file for unit tests.
8. **`ui/screens/SessionsScreen.kt`:** collect `pagedSessions`/`totalCount`/`refreshing`; add a `HIDE_ARCHIVED`…/`FilterList` toggle `IconButton` in the `TopAppBar` actions (tinted when archived are shown); `rememberLazyListState` + `derivedStateOf` triggering `loadMore()` when `lastVisibleIndex >= size - 8` via `LaunchedEffect`; footer row "Showing X of N" while `hasMore`; `refreshing && sessions.isEmpty()` → `LoadingRow` (not "No sessions yet"); on first composition fetch only when the list is empty **or** live sync is stopped — `LaunchedEffect(Unit) { if (sessions.isEmpty() || liveState == Stopped) viewModel.refresh() }` — so a connected live sync (already fresh via resync/stream) is not hit with an extra `/sessions` request per screen visit.

### Phase 4 — Config: Providers → Provider → Models (D2)

9. **`data/model/Models.kt`:** add `ProviderEntryDto(id, name?, displayName)` tolerant parser over `providers: List<JsonElement>`; `fun groupModelsByProvider(models): Map<String, List<ModelEntryDto>>` (blank provider → `"unknown"`, sorted keys); `fun providerDisplayName(providerID, providers): String` (favor raw `name`/`displayName`, else id). Refactor `ModelPickerSheet` (SessionDetailScreen.kt:546-549) to reuse `groupModelsByProvider`.
10. **`data/repo/Repository.kt`: per-profile config cache.** Replace the single `configCache` pair (Repository.kt:58-59, 201-208) with a `MutableMap<String, Pair<ServerConfigDto, Long>>` keyed by the profile id returned by `activeApi()`, each entry with its own 60 s TTL and eviction on profile delete. This also fixes the composer picker and the new provider screens, which share `repo.fetchConfig()`.
11. **`ui/screens/ConfigScreen.kt`:** overview = section "Providers (N)" with rows (`providerDisplayName` + `· M models`), each navigates to the provider screen; Agents/Commands/Skills sections unchanged below.
12. **New `ui/screens/ProviderModelsScreen.kt` + `ui/viewmodel/ProviderModelsViewModel.kt`:** VM takes `providerID` from `SavedStateHandle`, reads `repo.fetchConfig()` (per-profile cached), exposes filtered models, `providerName`, `loading`, `error`. Screen shows `displayName`, mono `id`, and "variants: a, b, c" secondary line; optional search field (reuse `ModelLevel` search pattern).
13. **`ui/navigation/Routes.kt` + `NavGraph.kt`:** add `ROUTE_PROVIDER_MODELS = "config/provider/{providerID}"`, `ARG_PROVIDER_ID`, `fun providerModels(id)` (URL-encode — provider ids may contain `/`), register composable with `navArgument(NavType.StringType)`.

### Phase 5 — Server naming (D4)

14. **`ui/viewmodel/ProfileSetupViewModel.kt` + `ui/screens/ProfileSetupScreen.kt`:** add `name` to `UiState`, `setName()`, optional `OutlinedTextField` ("Server name", placeholder "Home PC") on the BASE_URL step; `confirmAndSave()` passes `name = s.name.trim().ifBlank { host }` (host derived from baseUrl, fallback baseUrl).
15. **`data/repo/Repository.kt` + `ui/viewmodel/ProfilesViewModel.kt` + `ui/screens/ProfilesScreen.kt`:** `Repository.renameProfile(id, name)` (load → `saveProfile(profile.copy(name)))`; `ProfilesViewModel.rename(id, newName)`; `ProfileCard` gets an `Icons.Default.Edit` icon button next to Delete opening an `AlertDialog` with a pre-filled `OutlinedTextField` (blank input = no-op).

### Phase 6 — Send reliability & failure feedback (D6)

16. **Optimistic message echo.** Change `ApiClient.sendMessage` to return the created message: execute the POST, keep the response body, decode with a new tolerant pure helper `parseMessageResponse(body): SessionMessageDto?` (unit-tested) instead of discarding it (today the `POST /message` response — the created kilo message — is dropped). Extend `Repository.sendMessage`: on success, decode and `applier.storeHistory(sessionId, listOf(dto), olderPage = false)` before returning `OperationOutcome(true)`. The sent message then appears in the transcript instantly even when live sync / SSE echo is off or lagging; the later SSE echo is idempotent (upsert keyed by message id).
17. **Never leave the send button stuck.** In `SessionDetailViewModel.send()`, wrap the sending block in `try { ... } finally { sending.value = false }` (and log failures) so an unexpected throwable or cancellation can no longer leave `sending=true` forever — the reported "button became disabled, message not submitted" state. Failure handling after the reset: clear the composer only when `outcome?.ok == true`; show a Snackbar when `outcome?.ok == false` (task 18); treat `outcome == null` (edge: slash input where the command name parsed empty, e.g. `/` alone) as a silent no-op — nothing to send, no error.
18. **Failure feedback (silent-failure root cause).** Emit `UiEvent.OperationResult(false, msg)` from the generic `Exception` branch of `Repository.guarded` too (today only `ApiException` emits, Repository.kt:266, and nothing consumes it — MainActivity.kt:49-58 handles only CertChanged/ReauthRequired). Add `snackbarHostState` to `SessionDetailScreen`'s `Scaffold`, collect `LiveEventBus.uiEvents` filtered to `OperationResult`, and show a `Snackbar` for failures (cleared on next input).

### Phase 7 — Tests, CHANGELOG, review

19. **Unit tests** (see "Test coverage" below) + run `./gradlew testDebugUnitTest`.
20. **`CHANGELOG.md`** `[Unreleased]`:
    - Added: server naming (wizard + rename); Providers → Provider → Models config screens; lazy-loading (progressive) sessions list; archived sessions hidden by default behind a show-archived toggle; sent messages appear in the transcript immediately (echoed from the send response, no longer waiting on SSE).
    - Changed: session status badges update live from SSE without manual reload; live-sync FGS notification shows real stream state instead of a static "Starting live sync…".
    - Fixed: stale status under live sync; SSE stream kept following the old server after a switch; config cache leaked across servers; permission/question notifications displayed raw JSON instead of a readable message; send button could remain disabled with a silently dropped message.
21. **Android Lint** (`./gradlew lint`) and a final diff review.

## Test coverage

| New/updated test | What it covers |
|---|---|
| `SessionEntityMappingTest` (pure) | `toDto()` overlays entity `status`/`waitingReason` over rawJson values; passes through other fields; blank `rawJson` tolerated. |
| `SessionsPagingTest` (pure) | `filterArchived` (active-only by default; archived included when toggled); `windowSessions` page size, `hasMore`, loadMore bounds, empty/short lists, `totalCount` post-filter. |
| `ProviderGroupingTest` (pure) | `groupModelsByProvider` blank→"unknown", sorted keys, per-provider counts; `providerDisplayName` from raw `providers`, fallback to id. |
| `ProfileStoreTest` (Robolectric, mirrors `SecurityStoreTest`) | rename persists, re-sort by name, blank input no-op; optional since `saveProfile` is already exercised. |
| `MessageResponseParserTest` (pure) | `parseMessageResponse` decodes a kilo `{info, parts}` send response into `SessionMessageDto`; tolerates malformed/empty bodies (returns null). |
| `ModelsSummaryTest` / extend `QuestionPayloadTest` | `permissionSummary()` and `questionText()`/`questionSummary()` produce readable (non-JSON) notification bodies for the permission and question shapes. |

Existing suites (`EventParserTest`, `EventApplierTest`, `SessionStatusComputerTest`, `ModelLabelTest`, `ComposerOverridesTest`, `TranscriptWindowTest`, `ErrorMapperTest`, `ReconnectPolicyTest`) stay green — none of their inputs change; `EventApplierTest` gains a `session.idle` case (running→idle without a full snapshot). `EventApplier.storeHistory` is reused by the optimistic-echo path, so its existing tests already cover append/seq semantics for a single new message.

## New/changed files (summary)

- `data/model/Models.kt` — provider DTOs + grouping/name helpers (changed)
- `data/remote/ApiClient.kt` — `sendMessage` returns the created message via `parseMessageResponse` (changed)
- `data/repo/Repository.kt` — `toDto` usage, per-profile config cache, `renameProfile`, optimistic-echo `sendMessage`, `guarded` emits `OperationResult` on generic failures (changed)
- `service/EventApplier.kt` — handle `AppEvent.SessionIdle` (running→idle refresh); `storeHistory` reused by echo (changed)
- `service/LiveSyncService.kt` — profile-switch reconnect, notification state updates, permission/question notification body summaries (changed)
- `service/Notifier.kt` — `foregroundStateLabel`, `updateForeground`, tap-to-open content intent (changed)
- `ui/viewmodel/SessionsViewModel.kt` — windowing paging, archived filter, `refreshing` (changed)
- `ui/screens/SessionsScreen.kt` — paged list, archived toggle, scroll trigger, footer, loading state, stale poll, first-composition fetch (changed)
- `ui/viewmodel/SessionDetailViewModel.kt` — `try/finally` send reset, transient error state (changed)
- `ui/screens/SessionDetailScreen.kt` — `SnackbarHost` surfaces `OperationResult` failures; `ModelPickerSheet` reuse of shared grouping (changed)
- `ui/viewmodel/ConfigViewModel.kt` — unchanged
- `ui/screens/ConfigScreen.kt` — providers-first overview (changed)
- `ui/screens/ProviderModelsScreen.kt` — new
- `ui/viewmodel/ProviderModelsViewModel.kt` — new
- `ui/viewmodel/ProfileSetupViewModel.kt` / `ui/screens/ProfileSetupScreen.kt` — optional name field (changed)
- `ui/viewmodel/ProfilesViewModel.kt` / `ui/screens/ProfilesScreen.kt` — rename dialog (changed)
- `ui/navigation/Routes.kt` / `NavGraph.kt` — provider-models route (changed)
- `data/local/Entities.kt` — `SessionEntity.toDto()` overlay mapper (changed; entities unchanged, no Room migration)
- Tests: new `SessionEntityMappingTest`, `ProviderGroupingTest`, `SessionsPagingTest` (incl. archived filter + `filterArchived`), `MessageResponseParserTest`, `ModelsSummaryTest`; optional `ProfileStoreTest`

## Validation plan

Automated: `android/ ./gradlew testDebugUnitTest` (+ `lint`); `make check` at repo root must stay green (Go untouched).

Manual (device):
1. Start live sync → notification reads "Live sync connected"; toggle a session busy/idle on the PC (and let a run finish via `session.idle`) → badge updates to running and back to idle **without** tapping reload, and the notification reflects Reconnecting/Connected through a server restart.
2. Two servers configured: switch server while live sync is ON → the old server's events must not leak into the new profile's list; new profile fills from its own resync.
3. Background the app with a pending permission or question → notification body shows readable text ("permission · pattern · path" / the question), not JSON; tapping it still opens the approval sheet with the same text.
4. Large session history → list starts at 30 (non-archived only), toggle "show archived" reveals the archived tail, loads more on scroll, no jank during streamed updates; first run shows "Loading…" not "No sessions yet"; freshly-selected server with live sync off still fills the list on open.
5. Config → Providers → a provider → model list (id + variants); switch server and reopen config within 60 s → shows the new server's providers/models.
6. Name a server in the wizard and via rename; survives relaunch; re-adding the same baseUrl updates the existing entry.
7. Android 13+: deny `POST_NOTIFICATIONS` → live sync still works, no notification shown.
8. Send a message with live sync OFF: the bubble appears in the transcript immediately (echo from the `POST /message` response), the button re-enables, and a server-side error (e.g. wrong token / engine down) shows a Snackbar instead of a silent stuck-disabled button. Send while the agent is streaming → the message echoes instantly and the stream continues.

## Risks / notes

- Room list window is anchored to the live top; a new session via SSE while scrolled deep shifts content by one row. Accepted for v1.
- FGS notifications are non-dismissible by design while the service runs; do not attempt `NotificationManager.cancel(NOTIF_LIVE)` while running — Android may kill the service. Only text/contentIntent changes.
- Provider ids may contain `/` — always `Uri.encode` when building the nav route and declare `NavType.StringType`.
- Parsing of raw `providers` JSON must stay tolerant (fields `name`/`displayName`/`id` optional); provider name falls back to the id; blank model provider groups under `"unknown"`.
- Status overlay: `SessionEntity.status`/`waitingReason` are always set by `upsertSession` (statusComputer defaults to `idle`), so no blank-override edge case is expected.
- `session.idle` no-op was a hidden status-staleness path; the fix relies on `EventApplier`'s `statusComputer` state, which is authoritative after the first resync/snapshot of each session.
- Optional later perf optimization: a `LIMIT`-window Room query (instead of the full-list flow) to also skip decoding off-window rows; not needed for v1 and would re-introduce window-shift complexity.
- Optimistic echo is idempotent: the sent message is upserted by message id via the same `storeHistory` path SSE echoes use, so no duplicate bubbles when live sync is on.
- Send feedback scope: the Snackbar in the session detail screen covers composer operations; other screens keep current behavior (a later app-wide snackbar is a possible follow-up).
- Notification body cap (~300 chars) keeps long question JSON-derived text notification-safe; the full text stays in the approval sheet.

## Open questions

- None. Confirmed this session: archived sessions hidden by default with a toggle (D1); config overview = providers first, agents/commands/skills below (D2); notification stays as FGS with live state text + tap-to-open (D5); permission/question notification bodies use the existing readable summaries, permission path fixed alongside question, and the send-path reliability changes (try/finally, failure Snackbar, optimistic echo) are in scope (D6). Remaining choices (page size 30, 30 s stale poll, edit-icon rename, ~300-char notification cap) are specified defaults with no material uncertainty for the implementer.