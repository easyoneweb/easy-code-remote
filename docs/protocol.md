# easy-code-remote — Phone ↔ Server API contract (v1)

Single source of truth for the HTTP contract between the Android app and the companion
server. Server implementation must match this document; changes here require a
`CHANGELOG.md` entry.

## Transport

- Base URL: `https://<pc>:8443`. TLS is mandatory.
- All endpoints under `/api/v1` require `Authorization: Bearer <token>` except
  `GET /health`.
- Request/response bodies are JSON (`Content-Type: application/json`), except the SSE
  stream (`text/event-stream`).
- Error responses use a stable body:

```json
{"error": {"code": "session_not_found", "message": "human readable"}}
```

Stable error codes:

| HTTP | `code`                     | Meaning / retryable                     |
|------|----------------------------|------------------------------------------|
| 400  | `bad_request`              | Malformed body / invalid parameters.     |
| 401  | `unauthorized`             | Missing/invalid bearer token.            |
| 403  | `forbidden`                | Token valid but operation denied.        |
| 404  | `session_not_found`        | Unknown session id.                      |
| 409  | `busy`                     | Session busy / request conflict. Retryable. |
| 429  | `rate_limited`             | Rate limit exceeded. Retryable (see `Retry-After`). |
| 502  | `engine_unavailable`       | Kilo engine down/auth failed. Retryable. |
| 502  | `engine_auth`              | Kilo basic-auth failure (config error). Retryable. |
| 502  | `engine_error`             | Other upstream kilo error. Retryable.    |
| 500  | `internal`                 | Server bug — report.                     |
| 501  | `not_supported`            | Feature not implemented in this server version. |

## Endpoints

### `GET /health` — auth-free liveness

```json
{"status":"ok","serverVersion":"0.1.0","kiloVersion":"7.7.9",
 "engine":"up","sessions":3}
```

`engine` is `"up"`/`"down"`; `sessions` is the number of known sessions.

### `GET /api/v1/sessions`

Array of all sessions. Each item is the kilo `/session` entry plus a derived `status`:

```json
[{"id":"ses_...","slug":"...","title":"...","agent":"code",
  "model":{"id":"...","providerID":"...","variant":"..."},
  "directory":"/path","summary":{...},"tokens":{...},"cost":0,
  "time":{"created":...},"status":"running|idle|waiting",
  "waitingReason":"permission|question|", ...}]
```

Derived status rules: `waiting` when the session has a pending permission or question;
otherwise `running` when kilo reports `busy`/`retry`, `idle` when `idle`/`offline`.

### `GET /api/v1/sessions/{id}`

Single session, same shape as above. `404 session_not_found` when unknown.

### `GET /api/v1/sessions/{id}/pending`

Pending permission/question payloads for a session, used to recover approval dialogs
after missed SSE events (app/service down, `resync.required`, ring-buffer rollover).
The arrays are the raw kilo `/permission` and `/question` payloads for this session:

```json
{"permissions":[{"id":"perm_...","sessionID":"ses_...","permission":"bash","pattern":"git *"}],
 "questions":[]}
```

- `permissions` / `questions` are always arrays (`[]` when nothing is pending; never `null`).
- `404 session_not_found` when the session is unknown.
- Payloads are retained in memory as they arrive (SSE `permission.asked`/`question.asked`)
  and refreshed every pending poll (10 s) / resync. Payload shape is a kilo passthrough.
- Older servers without this endpoint return `404 not_found` — the app must degrade to
  "needs approval on PC".

### `GET /api/v1/sessions/{id}/messages?limit=&before=`

Transcript. `limit` (default 50, max 500) and `before` (message id, exclusive) map directly
to kilo `/session/{id}/message`. Response is the kilo shape (ordered messages with nested
`parts`):

```json
[{"info":{"id":"msg_...","role":"user","time":{...},"model":{...}},
  "parts":[{"id":"prt_...","type":"text","text":"..."}]}]
```

### `GET /api/v1/events?cursor=<opaque>` — SSE live stream

`text/event-stream`. Each `data:` line is one envelope:

```json
{"type":"session.updated","sessionID":"ses_...","messageID":"msg_...","partID":"prt_...",
 "data":{...},"ts":1790221764329,"cursor":42}
```

- `ts` is unix milliseconds; `cursor` is an opaque, monotonically increasing id. The phone
  stores the last `cursor` it saw and passes it back on reconnect for replay.
- Event `type` is dot-form with the version suffix stripped (`session.updated.1` →
  `session.updated`).
- Forwarded types: `server.connected`, `engine.connected`, `engine.disconnected`,
  `session.created`, `session.updated`, `session.deleted`, `session.status`,
  `session.wakeup`, `session.turn.open`, `session.turn.close`, `session.idle`,
  `session.error`, `session.diff`, `todo.updated`, `message.updated`, `message.removed`,
  `message.part.updated`, `message.part.removed`, `permission.asked`, `permission.replied`,
  `question.asked`, `question.replied`, `question.rejected`.
- `message.part.delta` from kilo is mapped to type `message.part.updated` with
  `data.part` = `{id:<partID>, type:"text", text:<delta>}` and
  `data.delta` = `{"type":"text-delta","textDelta":<delta>}` — the phone appends
  `textDelta` to the part text it already has.
- `message.part.updated` with a full `data.part` (from kilo `message.part.updated.1`)
  replaces the phone's cached part.
- Reconnect semantics:
  - cursor matches buffer → events after the cursor are replayed, then live events continue.
  - cursor too old / unknown → the server first emits
    `{"type":"resync.required","data":{"reason":"..."}}` — the phone must re-fetch
    `GET /api/v1/sessions` (and refresh transcripts) before relying on further events.
  - server start → `server.connected` then live events.
- The server keeps a bounded in-memory ring buffer (10 000 events). Events are
  de-duplicated by kilo `event.id`.

### `POST /api/v1/sessions/{id}/message`

Send user input.

```json
{"text":"hello", "agent":"code", "model":"deepseek/deepseek-v4-flash-0731",
 "variant":"default", "messageID":"msg_...", "queued":false}
```

- `text` is translated to `parts:[{"type":"text","text":<text>}]`. `parts` may be given
  directly instead of `text` (advanced).
- `agent`, `model`, `variant`, `messageID` pass through. When omitted, the server generates
  a fresh `messageID`.
- `queued:true` maps to kilo `noReply:true` (fire-and-forget, no streamed response).
- `replyToMessageID` is **not supported** in v1 (`501 not_supported`): branching requires a
  session fork, which is not part of v1. Omit it.
- Maps to kilo `POST /session/{id}/message`. Returns the created user message (kilo shape).

### `POST /api/v1/sessions/{id}/abort`

Stop the running agent. Maps to kilo `POST /session/{id}/abort`. Returns
`{"aborted":true}`.

### `POST /api/v1/sessions/{id}/command`

Run a slash command.

```json
{"command":"init", "arguments":"...", "agent":"code", "model":"...", "variant":"..."}
```

Maps to kilo `POST /session/{id}/command`.

### `POST /api/v1/sessions/{id}/permission`

Resolve a permission request raised for this session.

```json
{"permissionID":"perm_...", "action":"allow"|"deny", "message":"optional note",
 "always":false, "approvedAlways":[{"permission":"*","pattern":"*"}], "deniedAlways":[]}
```

- `allow` → kilo `/permission/{requestID}/reply` `{"reply":"once","message":...}`.
- `deny` → kilo `/permission/{requestID}/reply` `{"reply":"reject","message":...}`.
- `always:true` additionally calls kilo `/permission/{requestID}/always-rules` with
  `approvedAlways`/`deniedAlways` (must be non-empty; wildcard `*` allow rules are
  rejected for safety).
- Returns `{"replied":true}`.

### `POST /api/v1/sessions/{id}/question`

Answer or reject a question asked in this session.

```json
{"questionID":"q_...", "answers":["option label"]}
{"questionID":"q_...", "action":"reject"}
```

Maps to kilo `/question/{requestID}/reply` `{"answers":[...]}` or
`/question/{requestID}/reject`.

### `GET /api/v1/config`

Cached kilo configuration (60 s TTL):

```json
{"agents":[...], "skills":[...], "commands":[...], "mcps":{...},
 "providers":[...], "models":[{"id":"...","providerID":"...",...}]}
```

`models` is a flattened list derived from `/provider`.

### `GET /api/v1/sessions/{id}/diff`

Kilo diff summary for the session (`?base=` optional), passthrough of
`GET /session/{id}/diff`.