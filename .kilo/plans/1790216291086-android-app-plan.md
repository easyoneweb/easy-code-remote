# easy-code-remote — Android App Plan (Kotlin + Jetpack Compose)

Phase: SECOND. Implemented only after the companion server (`1790216291086-companion-server-plan.md`) is deployed and tested. The phone↔server contract lives in `docs/protocol.md` (authoritative).

## 1. Goal

A native Android app that connects over the public internet (TLS) to the Go companion server on the home PC and provides: live view of all Kilo Code sessions (VSCode + CLI), real-time streaming transcripts with **proper markdown rendering** in the chat view, and interaction — send messages, stop, choose agent/model/variant, run slash commands, approve permission prompts, with notifications. No Kilo gateway/account, no Firebase.

## 2. Non-goals

- No direct connection to kilo engine or its DB (always through the Go server).
- No WebSocket (server exposes SSE); no FCM/Google push.
- No iOS; no multi-PC aggregation beyond saved server profiles.
- No full VSCode editor viewport rendering (diffs + tool output instead).
- No WebView-based markdown rendering (see section 5.7).
- No remote image loading from agent content (security, see 5.7).
- v1: no support for editing server-side config from the phone (read-only pickers + commands).

## 3. Prerequisites

- Server from plan `1790216291086-companion-server-plan.md` deployed, `https://<pc-public-ip-or-domain>:8443` reachable, token issued, `docs/protocol.md` frozen.
- Android Studio / AGP toolchain; physical phone (emulator cannot reach the PC's public address in a useful way for TLS/TOFU testing — use a real device or adb reverse for LAN dev).

## 4. Architecture

Single-activity Compose app, MVVM, clean layering:

```
[UI: Compose screens]  ->  [ViewModels (StateFlow)]  ->  [Repository]
                                                          |-> RemoteDataSource: OkHttp REST + okhttp-sse EventSource
                                                          |-> Room cache (sessions, messages/parts)
                                                          |-> SecurityStore: Keystore (token, cert pin)
                                                          \-> Service: foreground SSE + notifications
```

- Package layout: `com.easycoderemote` → `data/remote`, `data/local`, `data/repo`, `data/model`, `ui/{screens,components,theme}`, `service`, `security`, `render/markdown`.
- Live path: foreground service holds one SSE connection (`EventSource` to `GET /api/v1/events?cursor=`), normalizes envelopes into app events, pushes into a single `SharedFlow`; UI + Room write consume it.
- History path: `GET /api/v1/sessions`, then per-session `GET .../messages?after=` (incremental) and `?before=&limit=` (older pages). Room gives instant rendering on open; SSE then applies deltas (part `status` running→done updates in place).
- Render source: the session detail renders exclusively from a per-session in-memory `SessionUiState` (StateFlow) inside the ViewModel — seeded from Room, updated by SSE events, trimmed to the visible lazy window (5.8). Room is a persistence mirror, never a render source (avoids double-source-of-truth flicker).

## 5. Key design decisions

1. **Transport**: OkHttp + `okhttp-sse` EventSource (SSE) for live; plain OkHttp calls for REST. No Retrofit (fewer layers, direct control of SSE). `kotlinx.serialization` for JSON; DTOs mirror `docs/protocol.md`.
2. **TLS (public exposure, self-signed server cert) — TOFU pinning**: on first connect to a profile, server cert SHA-256 fingerprint is shown with its IP/domain; user confirms; pin stored in Android Keystore. On later mismatch (cert rotated or MITM): blocking screen "Certificate changed", fingerprint shown, only "Trust new certificate" (requires re-entering token) or "Cancel". Release builds require `https://`; debug builds allow cleartext on LAN IPs/localhost via `network_security_config.xml` for development against a dev server.
3. **Auth storage**: bearer token stored in Keystore (AndroidKeyStore AES-GCM, no plaintext prefs). Multiple server profiles (id, name, base URL, token, cert pin) in DataStore Preferences; one active profile.
4. **Room cache**: tables `sessions`, `messages`, `parts` (with part `state` JSON + status), keyed by server profile + kilo IDs; upsert on SSE; purge for archived sessions beyond a retention (e.g. 50 per profile).
5. **Foreground service**: `dataSync` type, holds SSE, posts **local** notifications (no FCM) on `permission.request`, `question`, and agent-completed/attention events when the app is backgrounded. User can disable per-channel.
6. **Reconnect**: exponential backoff (1s→30s cap, jitter) on SSE drop; resume with `cursor`; full resync of session list on `server.connected`/`engine.connected` events.
7. **Markdown rendering in chat view (Markwon, native, no WebView)**:
   - **Library**: `io.noties.markwon` (4.6.x, pin at implementation). Renders CommonMark into a `Spannable` drawn by a `TextView` embedded in Compose via `AndroidView`. Native text selection, copy, and theme integration; efficient for long transcripts; no per-message WebView (heavy, slow to stream, poor text selection).
   - **Where applied**: assistant message **text parts** only. User input is rendered as plain text (prevents layout/typo spoofing and markdown injection in the user bubble). Tool parts are rendered as collapsible monospace chips, **not** through Markwon (they can be huge; see cap below).
   - **Plugins enabled**: tables (`markwon-ext-tables`), strikethrough/task-lists (core/extensions), link resolution with a `LinkResolver` that only opens `https://`/`http://` via `ACTION_VIEW` on explicit tap. **Images explicitly NOT loaded** (`markwon-image` omitted): agent output may contain tracking/beacon URLs; loading them from the phone would leak requests to arbitrary hosts. Raw HTML is not rendered (Markwon escapes it by default). Syntax highlighting is **optional** polish (Phase 5) via `markwon-syntax-highlight`.
   - **Streaming**: parse on `Dispatchers.Default` with `Markwon.parse`; debounce re-render of the currently-streaming message to ~200 ms; completed messages keep their final `Spannable` (never re-parsed). One `Markwon` instance reused app-wide.
   - **Size caps**: messages > 64 KiB get a truncated preview + "Show full text" expand (copies raw text); protects main-thread draw time on long outputs. Tool outputs capped similarly in their chip view.
   - **Copy**: long-press a message bubble → copy raw text; per-code-block copy button is Phase 5 polish (custom Markwon node).
8. **Transcript windowing (lazy, newest-first)**: session detail opens at the live edge with the latest ~200 messages rendered; scrolling toward the past loads older pages (`GET .../messages?before=<msgId>&limit=200`, newest-first, deduplicated by message id); items outside the window are evicted from `SessionUiState`. The streaming message (the newest) is always inside the window and updates via debounced markdown re-render; an "autoscroll" affordance keeps the live edge visible and is disabled once the user scrolls up. Room retains full history, so reopening the session is instant and older pages can be served from cache before the network fetch.

## 6. Dependencies (pinned at implementation time)

- Core: `org.jetbrains.kotlin:kotlin-stdlib`, `androidx.compose.*` (BOM), `androidx.activity:activity-compose`, `androidx.navigation:navigation-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose` + `lifecycle-runtime-compose`, `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `kotlinx-serialization-json`.
- Networking: `com.squareup.okhttp3:okhttp` + `okhttp-sse`.
- Persistence: `androidx.room:room-runtime` + `room-ktx` (+ `room-compiler`), `androidx.datastore:datastore-preferences`.
- Markdown: `io.noties.markwon:markwon` + `markwon-ext-tables` (+ `markwon-syntax-highlight` in Phase 5).
- Misc: `androidx.core:core-ktx`, material3, `material-icons-extended`. Keystore wrapper: small hand-written util over `AndroidKeyStore` (avoids deprecated `security-crypto`).

Build config: minSdk 26, targetSdk/compileSdk 35, Kotlin 2.x with Compose compiler plugin.

## 7. Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`.
- `POST_NOTIFICATIONS` (Android 13+), runtime request with rationale.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` (Android 14+); start from an explicit user action ("Enable live notifications").
- No location, no storage.

## 8. Screens & navigation

Navigation routes: `profiles`, `profileSetup`, `sessions`, `sessionDetail/{id}`, `config`, `settings`, `certChanged` (blocking).

1. **Profiles / Setup**: list saved servers, add/edit; connect wizard: base URL → health check (`/health`) → token → cert fingerprint confirm (TOFU) → save.
2. **Sessions list**: live-updating cards: title, agent/model, project dir, token/cost counters, status badge (`running` spinner, `idle`, `waiting` amber = needs approval), last-updated, archived dimmed. Pull-to-refresh.
3. **Session detail**: streaming transcript in a **reverseLayout LazyColumn, newest-first lazy window** (see 5.8): opens at the live edge showing the latest ~200 messages, loads older pages (200 at a time) on scroll-up, anchors the scroll at the newest message, live stream appends at the live edge. User bubbles: plain text. Assistant bubbles: **Markwon-rendered markdown** (headings, lists, tables, inline code, code fences, links, bold/italic, task lists) with streaming debounce and size caps. Tool parts: collapsible chips with live status (`running` spinner → `done`/`error`) and expandable monospace input/output. Diffs tab (if server exposes it). Sticky composer at bottom.
4. **Composer**: text field, send; slash-command autocomplete from `/config` commands; buttons: agent picker, model picker, variant picker; primary action "Stop" (enabled while session running → `POST /abort`).
5. **Approval dialog**: triggered by `permission.request`/`question` events — shows action/resource/path, Approve / Deny / Always for this session (→ `POST /api/v1/sessions/{id}/permission`).
6. **Config screen**: read-only lists from `/config` (agents, models, skills, commands, MCPs) used by composer pickers; pull-to-refresh.
7. **Settings**: active profile, notifications toggle + channels, retention, about/version, "test connection".

## 9. Phases & ordered tasks

**Phase 0 — Scaffold + connection**:
- [ ] Gradle project, min/target SDKs, theme, navigation skeleton, deps.
- [ ] Server profile model + DataStore, Keystore util (token + cert pin).
- [ ] Remote client: `/health`, TOFU TLS handling (fetch cert chain via `X509TrustManager` inspection of the `SSLSocketFactory` used by the OkHttp client), fingerprint dialog.
- [ ] Connect wizard screen working against the real server.
- Exit criteria: app connects over public TLS to the deployed server, trusts + pins cert, health shows engine up.

**Phase 1 — Sessions list**:
- [ ] DTOs for `/api/v1/sessions` + `GET /api/v1/sessions/{id}/messages?after=` and `?before=&limit=`; Room schema + DAOs. **Contract dependency**: confirm/land `before` + `limit` pagination params in `docs/protocol.md` and the server (android plan assumes them; add to server plan Phase 2).
- [ ] SSE client (`EventSource`), envelope parser, app-event model, `SharedFlow` wiring.
- [ ] Sessions screen with live status badges; reconnect/backoff logic.
- Exit criteria: live list reflects running/idle/waiting sessions including a session started from a CLI on the PC.

**Phase 2 — Session detail (read + markdown rendering)**:
- [ ] `MarkdownRenderer` component: single `Markwon` instance (tables plugin, link resolver, no images), background-thread parse, debounced re-render for the streaming message, size caps (64 KiB) + "Show full text" expand, long-press copy.
- [ ] Transcript renderer wiring: user text parts → plain; assistant text parts → `MarkdownRenderer`; tool parts → collapsible monospace chips with state transitions; expandable details; diff tab if server exposes it.
- [ ] Lazy window: reverseLayout LazyColumn, latest ~200 messages, scroll-up pagination (`before`/`limit`), dedup by message id, item eviction, autoscroll anchor at the live edge, "loading older…" footer.
- [ ] History load on open (Room first, then fetch), incremental SSE updates, `after` cursor for missed messages; handle `message.part.removed` (retraction) and `session.compacting` (banner + transcript rewrite).
- Exit criteria: watching a live agent run renders token-by-token with correct markdown (headings/tables/code/links), completed messages stable (no flicker), huge outputs truncated safely.

**Phase 3 — Interaction**:
- [ ] Composer send (text→parts), reply-to, `queued`; Stop (`/abort`); error mapping to user messages (401 → re-auth, 404 → session gone).
- [ ] Agent/model/variant pickers populated from `/config`; slash-command autocomplete + `/command` execution.
- [ ] Approval dialog wired to `permission.request`/`question` events with Approve/Deny/Always.
- Exit criteria: full round-trip — start session on phone, watch it work, approve an `ask` permission, stop it; VSCode UI on the PC reflects everything.

**Phase 4 — Foreground service + notifications**:
- [ ] Foreground service holding SSE; local notifications (attention, approvals, agent idle); Android 14 FGS rules handled; start/stop from settings.
- Exit criteria: app backgrounded → still receives + notifies on permission requests and completions.

**Phase 5 — Polish & cache**:
- [ ] Code-block copy buttons (custom Markwon node), optional syntax highlighting, offline/error/empty states, pull-to-refresh, archived-session filtering, Room retention/cleanup, state restoration on process death (saved state + Room), dark theme, battery-optimization exemption hint.
- Exit criteria: app behaves correctly across process death, network flapping, server restarts.

## 10. Testing & validation

- Unit: envelope parser, status transitions (running→done), cursor/resume logic, **windowing logic** (page merge by message id, dedup, anchor drift when new messages arrive while scrolled up, eviction, part removal/compaction), Room DAOs (Robolectric or instrumented), Keystore wrapper (instrumented), URL/cleartext policy, **MarkdownRenderer fixture corpus** (headings, nested lists, tables, inline code, fenced code blocks, links, bold/italic, task lists, raw HTML → escaped, 64 KiB cap, unicode/emoji).
- Manual checklist on a real device: first-connect TOFU; kill server → reconnect on recovery; rotate server cert → cert-changed flow; wrong token → clean error; notification arrives while app is closed; session driven from phone appears live in PC VSCode; streaming markdown renders progressively without flicker.
- Validation against server `scripts/e2e.sh` results; contract conformance tests against a test server instance.

## 11. Failure modes & mitigations

- SSE drop → backoff + cursor resume; UI keeps working from Room.
- Server unreachable → explicit banner, retry, no crash; queue composer input locally with "sent when online" hint (Phase 5).
- Cert changed (legit regen vs MITM) → blocking cert screen; user decides with fingerprint evidence.
- Token invalid/rotated → 401 handler → re-auth prompt.
- Foreground service killed by OS → restart on next app open; notification explains it's off.
- **Markdown perf**: huge/edge-case input → 64 KiB cap + background parse + debounce; never parse on main thread.
- **Pagination race**: new live messages arriving while an older page is fetched → merge by message id, keep newest-first order, never duplicate.
- **Malicious markdown** (tracking links, raw HTML, resource tricks) → no image loading, HTML escaped, links limited to http(s) with explicit tap.
- Android 14 FGS restrictions → start only from user action; handle `ForegroundServiceStartNotAllowedException`.

## 12. Risks & open questions

- Server API evolution → `docs/protocol.md` is the contract; conformance tests gate both sides.
- OkHttp `EventSource` buffer limits on long-lived connections over mobile networks → consider heartbeats and automatic re-subscribe with cursor on timeout.
- Markwon maturity/perf vs WebView fidelity → Markwon chosen for native selection/copy and streaming; WebView is the fallback if fidelity gaps matter later (acknowledged trade-off).
- Notifications without FCM depend on the foreground service staying alive (OEM battery killers) → document exemption hint; optional Firebase later.
- Emulator cannot test public-TLS path → use real device.

## 13. Out of scope

iOS, WebSocket, FCM push, direct kilo/DB access, server-side config editing from the phone, multi-PC live aggregation, worktree/Agent-Manager execution from the phone, WebView-based markdown.

## 14. Acceptance criteria (app done)

App connects to the deployed server over public TLS with TOFU pinning; lists live sessions incl. CLI sessions; renders a streaming agent run with **correct live markdown** (tables, code fences, links, lists — stable after completion, safe against raw HTML and remote images); sends/aborts/approves/commands end-to-end (reflected live in PC VSCode); notifies while backgrounded; survives reconnect, cert rotation, and process death.