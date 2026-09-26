# Plan: Show active agent/model in sessions + grouped/searchable model picker

Date: 2026-09-26 (rev. 2)
Scope: Android app (`android/`). No server/protocol changes — all data needed is already
exposed by `/api/v1/config`, `/api/v1/sessions`, `/api/v1/sessions/{id}/messages` and the
SSE event stream (`session.updated`, `message.updated`).

## Confirmed requirements

1. **Header (session detail):** show the session's currently-active agent and model as
   `agent: implementer` / `model: router_ai · deepseek/deepseek-v4-flash-0731`
   (`providerID · id`), live-updating via `session.updated`.
2. **Per-message badges:** each assistant bubble shows a small caption
   `agent · providerID · modelID` taken from the message itself.
3. **Model picker everywhere a model list is picked from (today: the composer):**
   two-level — tap a **provider** first, then pick a **model within that provider** via a
   searchable list (8252 models / 225 providers on this box; largest provider ≈ 595).
4. **Variant picker:** use the **selected model's own `variants`** when present, falling
   back to the common `default/low/medium/high`.
5. **Picker chips never show bare "none":** when no explicit override is set, the chip
   shows the **session-active** value; an explicit selection is a one-shot override for the
   next message and clears after it is sent.
6. Sending the model sends an object `{id, providerID}` (matches how the session records
   it, and kilo accepts it).

## UX decisions (refined)

- **Model picker container = ModalBottomSheet, not nested DropdownMenus.** A DropdownMenu
  inside a DropdownMenu with a text field is awkward (keyboard, dismissal, item height).
  Use `ModalBottomSheet`:
  - **Level 1 (providers):** LazyColumn of providers, alphabetical, each row
    `providerID` + count (`openrouter · 385`). One row allows "Search all models" later.
  - **Level 2 (models of the provider):** a search `OutlinedTextField` at the top
    (filters by name or id, case-insensitive, substring) + a LazyColumn of that
    provider's models: `displayName` (primary), `id` (secondary, monospace,
    ellipsized). A back arrow/caret returns to level 1. Selecting the provider row
    header reopens level 2 with the same search preserved.
  - Opening the picker shows level 1 immediately if nothing is selected; if an override
    is active, it opens on that provider's level 2, pre-filtered to the selected model.
- **Config payload is ~13 MB** (8252 models) and is already fetched once (app-side TTL
  cache). Load `config.models` once per screen via `remember(config)` and pre-group by
  `providerID` into a sorted `Map<String, List<ModelEntryDto>>` for O(1) level-2 access.
  The model picker's search only filters the current provider's in-memory list (≤ ~600) —
  cheap.
- **Chip labels:** chips show compact values — agent name; model `displayName` (fallback
  `id`) or "auto" when neither override nor session value exists; variant name or "default".
  The full `providerID · id` canonical form lives in the header and message badges.
- **Override clearing:** `agent`, `model`, and `variant` overrides all clear after a
  successful **message send** (one-shot, matches kilo's per-message params). Slash
  commands do NOT touch overrides (commands ignore them). A **failed** send keeps the
  overrides so the user can retry.
- **Model change resets the variant override**: picking a different model clears any
  previous variant override (the variant list is per-model; a stale variant is invalid);
  the variant chip then shows the session default or "default".
- **Header vs. pending override:** the header always shows the **session's** active
  agent/model (from `SessionDto`); a composer override does NOT alter the header.
- **Loading/empty states:** if config is not loaded yet, the model chip shows the session
  active value and opening the picker shows a spinner; if config yields no models, the
  picker shows an empty message. All lookups guard against missing/blank values.

## Touch points (all in `android/app/src/main/java/com/easycoderemote/`)

### 1. Models — `data/model/Models.kt`
- `ModelEntryDto`: add `val variants: JsonElement? = null` and
  `fun variantNames(): List<String>` (keys of the `variants` object, sorted, fallback
  `["default","low","medium","high"]` when empty).
- `MessageInfoDto`: add `val agent: String? = null`, `val providerID: String? = null`,
  `val modelID: String? = null` (server already sends these inside `info`).
- New pure helpers (unit-testable):
  - `fun providerModelLabel(provider: String?, modelId: String?): String?` —
    `"provider · id"`, null when both blank (each part optional).
  - `fun badgeLabel(agent: String?, provider: String?, modelId: String?): String?` —
    `"agent · provider · id"` with blank parts dropped; null when nothing present.
  - `SessionDto.sessionModelProvider(): String?` / `SessionDto.sessionModelId(): String?`
    — parse `model` JsonElement (object `{id, providerID, variant}` or plain string id;
    string-id case → provider = null, id = the string).
  - `SessionDto.sessionModelVariant(): String?` — `model.variant` (fallback null).
- Keep `modelLabel()` for the sessions list (unchanged).

### 2. Room schema — `data/local/Entities.kt` + `AppDatabase.kt`
- `MessageEntity`: add `val agent: String? = null`, `val providerID: String? = null`,
  `val modelID: String? = null`.
- `AppDatabase`: version `2 → 3`; new `MIGRATION_2_3`:
  ```sql
  ALTER TABLE messages ADD COLUMN agent TEXT
  ALTER TABLE messages ADD COLUMN providerID TEXT
  ALTER TABLE messages ADD COLUMN modelID TEXT
  ```
  Chain `addMigrations(MIGRATION_1_2, MIGRATION_2_3)` (non-destructive; existing data
  migrates in place, badge fields fill in on the next `message.updated` / history fetch).

### 3. EventApplier — `service/EventApplier.kt`
- `upsertMessage(...)` (SSE `message.updated`): populate the three new fields from
  `data.info.agent`, `data.info.providerID`, `data.info.modelID` (string helpers).
- `storeHistory(...)` (REST): populate the same fields from `m.info.agent` /
  `m.info.providerID` / `m.info.modelID` on the upserted `MessageEntity`.
- `upsertSession` unchanged (session agent/modelLabel already stored) — the header reads
  the live `SessionDto` (decoded from the stored `rawJson`, updated on `session.updated`).

### 4. Repository — `data/repo/Repository.kt`
- No data-flow change; `TranscriptMessage` already carries the full `MessageEntity`.

### 5. ComposerOverrides — `ui/viewmodel/ComposerOverrides.kt` (new, pure, testable)
Extract the picker semantics out of the ViewModel into a pure helper:
- `modelOverrideJson(providerID: String?, id: String): JsonObject` — `{id}` + optional
  `providerID`.
- `resolveAgent(overrideAgent, sessionAgent): String?` — override wins, else session.
- `resolveModelLabel(overrideModel, sessionDto): String?` — override displayName/id, else
  `providerModelLabel(sessionModelProvider(), sessionModelId())`, else "auto".
- `resolveVariant(overrideVariant, sessionDto, chosenModel): String?` — as specified.
- `clearAfterSend(success: Boolean)` — tuple of the three cleared values.

### 6. SessionDetailViewModel — `ui/viewmodel/SessionDetailViewModel.kt`
- `selectModel(providerID: String?, id: String)`: `model.value = ComposerOverrides.modelOverrideJson(...)`
  and `variant.value = null` (model change resets the variant override).
- `send()`: on `outcome?.ok == true` AND a text message (not a slash command), clear the
  three overrides; on failure keep them.
- Expose `config` (already), `session` (already) — UI derives chip labels via the
  `ComposerOverrides` helpers in the Composable (no new StateFlows needed).

### 7. SessionDetailScreen — `ui/screens/SessionDetailScreen.kt`
- **Header:** under the title (next to `StatusBadge`), a `Row` of two compact value
  labels when `session != null`: `"agent: X"` and
  `"model: providerID · id"` (wrap, ellipsize, `labelSmall`, `onSurfaceVariant`).
- **MessageBubble badges:** for non-user messages, above the bubble content a `labelSmall`
  caption `badgeLabel(message.agent, message.providerID, message.modelID)`; skip the badge
  when the label is null (all parts blank). `maxLines = 1`, `TextOverflow.Ellipsis`.
- **Composer pickers**:
  - Agent: keep the flat `PickerChip` (few agents), value = `resolveAgent(...)`.
  - Model: new `ModelPickerSheet` (see UX decisions) driven by `config.models`; chip
    value = `resolveModelLabel(...)`; on pick → `viewModel.selectModel(providerID, id)`.
  - Variant: `PickerChip` with options = `chosenModel.variantNames()` (the model picked in
    the override, else the session model, else the common list), value = `resolveVariant(...)`.
  - `PickerChip` gets an optional "clearable → (none)" item that maps to the session
    default (null override), not a hard "none" state.
- Reuse note: `ModelPickerSheet` is the single model-picker component; the Config screen
  is an informational list (LazyColumn), not a picker — out of scope.

### 8. Tests — `android/app/src/test/java/com/easycoderemote/`
- `data/model/ModelLabelTest.kt` (new): `providerModelLabel`, `badgeLabel` formatting
  (all-null, partial, full); `ModelEntryDto` decode incl. `variants` + `variantNames()`;
  `MessageInfoDto` decode of `agent/providerID/modelID`; `SessionDto` model parsing
  (object vs string; provider/id/variant extraction).
- `ui/viewmodel/ComposerOverridesTest.kt` (new): model JSON shape; resolve* priority
  (override wins, session fallback, "auto" when both missing); variant reset on model
  change; clear only on success.
- `service/EventApplierTest.kt` (extend): `message.updated` and `storeHistory` populate
  `agent/providerID/modelID`; migration v2→v3 preserves existing rows (Room
  MigrationTestHelper or in-memory round-trip).
- Existing suites (`TranscriptWindowTest`, `QuestionPayloadTest`, etc.) stay green.
- No Go changes → `make check` green.

### 9. CHANGELOG.md
`[Unreleased] > Added`: active agent/model in the session header and per-message badges
(`provider · model id`); provider-grouped, searchable model picker; variant list driven by
the selected model; picker chips show the session's active values and overrides clear
after sending.

## Out of scope / notes
- Server/protocol: none (data already present).
- `ConfigScreen`: informational model list stays as-is (LazyColumn handles 8k rows).
- Sessions list already shows agent/model — unchanged.
- Migration is non-destructive; badge fields backfill on the next fetch/event after upgrade.
- Overrides are per-send and never persisted — the session's active agent/model always
  wins between messages.

## Verification
1. `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` — green, APK builds.
2. `make check` — green (no Go changes).
3. Manual (install via Android Studio Run on the device):
   - Header shows `agent` + `providerID · id` for idle and running sessions; updates when
     the agent switches on the PC.
   - Assistant bubbles show `agent · providerID · modelID` captions; a subagent-written
     message shows the subagent's agent.
   - Model chip shows the session's active model; opening the picker → providers →
     searchable model list; selecting sends `{id, providerID}`; the next `session.updated`
     on the PC reflects it.
   - Variant chip lists the chosen model's variants when present.
   - Send with overrides → chips return to session defaults; a failed send keeps them.
   - Switch model over an old session having streaming enabled at a different provider —
     variant override resets and the list reflects the new model's variants.