# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Session detail header shows the session's active agent and model (`agent: X`,
  `model: providerID · id`) and updates live via `session.updated`.
- Assistant message bubbles carry a small `agent · providerID · modelID` caption
  taken from the message itself (blank parts dropped; hidden when none present).
- The composer model picker is now a two-level, searchable sheet: pick a provider
  first, then a model within it (grouped from the fetched `/config` model list),
  with an in-picker spinner and empty state.
- The composer variant picker uses the selected model's own `variants` when present,
  falling back to the common `default/low/medium/high`.
- Picker chips show the session's active agent/model/variant when no explicit
  override is set; agent/model/variant overrides are one-shot and clear after a
  successful message send, and picking a different model resets the variant override.
- The session detail screen re-fetches pending permission/question payloads on the
  live-edge poll, so approval banners appear even when the SSE stream is down.

### Fixed

- Transcript messages with `question`/tool parts are no longer silently dropped:
  kilo sends those parts with a `{status, input}` object in `state`, which used to
  fail `PartDto` decoding and made the tolerant array decoder discard the whole
  message (assistant replies right before a pending question never appeared).
- Transcript scrolling is smooth on high-refresh devices: the live transcript flow
  used to join one Room parts-flow per message (thousands of concurrent flows that
  re-fanned out on every streaming token). It now combines a single bulk parts
  query with a trailing debounce, so streaming bursts coalesce into one recomposition
  instead of re-rendering the whole visible list per token; the live-edge autoscroll
  additionally only re-anchors while the user is actually pinned to the newest message.
- Android app (`android/`, Kotlin + Jetpack Compose, min SDK 26): server profiles with
  TOFU cert pinning, live session list with status badges, streaming session transcript
  with real-time markdown rendering, permission/question approval dialogs, slash
  commands, agent/model/variant pickers, and local notifications from a foreground
  SSE service (no gateway account, no Firebase).
- Transcript markdown rendering in the session detail screen via Markwon (headings,
  lists, tables, task lists, inline/fenced code, bold/italic/strikethrough, links):
  parsed off the main thread with streaming debounce, 64 KiB size cap with a
  "Show full text" toggle, long-press copy, http(s)-only links, and remote images
  never loaded (security).
- Lazy transcript windowing: the detail screen opens at the live edge (latest ~200
  messages, newest-first) and pages older history on scroll-up via the messages
  `before`/`limit` params, with dedup by message id, autoscroll at the live edge,
  and in-place streaming part updates.
- `GET /api/v1/sessions/{id}/pending` returning the raw pending permission/question
  payloads for a session (kilo passthrough arrays), so phones can recover approval
  dialogs after missed SSE events. The store now retains per-session pending payloads
  from `permission.asked`/`question.asked` and the 10 s pending poll.
- Go companion server (`server/`) exposing a secure HTTPS API (`/api/v1`) to Android phones
  over the public internet: TLS termination, bearer-token auth, per-IP rate limiting, JSON access log.
- Supervised `kilo serve` subprocess (own `KILO_SERVER_PASSWORD`, port 18500) with crash
  restart/backoff and engine up/down health events.
- Read path: `GET /api/v1/sessions`, `GET /api/v1/sessions/{id}`,
  `GET /api/v1/sessions/{id}/messages`, `GET /api/v1/events` (SSE with cursor resume),
  `GET /api/v1/config` (agents/skills/commands/MCPs/providers, 60s TTL).
- Write path: `POST /api/v1/sessions/{id}/message|abort|command|permission|question` with
  kilo error-to-HTTP mapping and stable error bodies.
- CLI: `serve` (default), `status`, `doctor`, `token rotate`, `cert regen`.
- First-run setup: generates a 256-bit bearer token and a self-signed TLS certificate.
- Systemd unit and install script (`scripts/`), end-to-end test script (`scripts/e2e.sh`).

### Fixed

- Android app crash right after saving a server profile when the pasted bearer token
  contained a trailing newline/whitespace (OkHttp rejects control characters in the
  `Authorization` header). Tokens are now trimmed on save, on load, and when the
  header is built, and a malformed header can no longer crash the SSE reconnect loop.
- `/api/v1/sessions` returned only the current project's sessions: kilo's `/session`
  HTTP endpoint is scoped to the serve process's working directory, so sessions from
  other projects (e.g. a session running in another VSCode window) were invisible to
  the phone. The server now seeds the session list from kilo's own `db` CLI against
  the shared `kilo.db` (global across projects), falling back to the scoped HTTP
  endpoint when the kilo binary is unavailable.
- Phone transcripts and pending approvals stayed stale for sessions outside the
  supervised kilo serve's project: kilo's `/event`, `/question` and `/permission`
  endpoints are all project-scoped, and a passive `kilo serve` never emits the
  sessions that VSCode windows drive. The server now discovers every `kilo serve`
  on the host (`/proc` scan), fans their `/event` streams into the phone feed, and
  merges pending questions/permissions and statuses across all of them. The phone
  also re-fetches the live edge of the open session every 10 s as a safety net.
- Android approval screen did not render question variants: kilo question payloads
  wrap the question in a `questions` array with selectable `options`. The screen
  now shows the header/body and each option as a selectable card with a Submit
  button (plus Reject), instead of dumping raw JSON and answering "yes".
- Transcript rendering (from screen-recording review): phantom empty "assistant"
  bubbles are gone (empty text parts are skipped, and messages with no visible
  content are not rendered), the live edge stays pinned while the streaming
  message grows, and the composer no longer sits flush against the gesture
  navigation bar (IME + navigation-bar insets are both handled).
- Streaming assistant text no longer loses its earlier content: kilo interleaves
  live text deltas (append) with persisted full-part snapshots whose text is
  empty/stale mid-stream. A full-part replace (or history re-fetch) can no longer
  shrink the accumulated text, so streaming keeps appending from the correct base.
- Transcript messages rendered empty and tool/command activity was invisible: the
  server forwarded `message.part.*` events without a `messageID` (kilo nests it as
  `part.messageID`), so the phone stored every part with an empty `messageId` and
  no message could attach to its content. Normalization now extracts the part's
  `messageID`, so streaming text, tool chips and command output attach to their
  messages again (and already-stored parts re-attach on the next history fetch).
- Transcript history appeared out of order: the messages endpoint returns pages
  oldest-first, but the phone assigned local sequence numbers assuming newest-first,
  so old messages got inverted order and the conversation looked scrambled. Message
  ordering now uses the server creation time (`timeCreated`, added via a Room
  migration v1→v2) with `seq` only as a tiebreak, and history sequence numbers are
  assigned chronologically.