# Spike findings (Phase 0)

Live probes against `kilo serve 7.7.9` on `127.0.0.1:18500` (shared `~/.local/share/kilo/kilo.db`,
VSCode sessions active). Probe date: 2026-09-24. All writes used a throwaway session
(`ses_f2e77ddc0ffePFYtPrviUftASD`, created with `kilo run`, deleted after).

## Confirmed live HTTP surface

- `GET /session` → 200; entries carry `id, slug, projectID, directory, path, summary,
  cost, tokens{input,output,reasoning,cache{read,write}}, title, agent,
  model{id,providerID,variant}, version, time{created,updated}, permission[]`.
- `GET /session/status` → map `{sessionID: {type}}`; empty `{}` when all sessions idle.
- `GET /permission` → `[]` when none pending; `GET /question` → `[]`.
- `GET /config` → config-file surface (`$schema, command, plugin, disabled_providers,
  username, mode, agent, provider, permission`) — NOT the agent/skill lists.
- `GET /agent` → array (name, description, mode, permission[]).
- `GET /skill` → array; `GET /command` → array; `GET /mcp` → object (empty `{}`).
- `GET /provider` → `{all:[{id,name,source,models:{...}}]}` — models live here, not in
  `/models` (`/models` and `/models/{provider}` 404).
- `GET /session/{id}/message?limit=` → array of `{info:{id,sessionID,role,time,model,...},
  parts:[...]}`; parts include text parts and tool parts.
- `POST /session/{id}/message` → 200, returns created message `{info, parts}`. Works
  **without** `directory`/`workspace` query params (session carries its own cwd).
- `POST /session/{id}/prompt_async` → 200 empty body (fire-and-forget).
- `POST /session/{id}/abort` → 200 `true`.
- `kilo serve --hostname 127.0.0.1 --port N` accepted and binds correctly (flag verified in
  `--help` and live).

## SSE `/event` wire format

Every DB-sourced event arrives **twice**: once wrapped and once live, with the **same** `id`:

```json
{"type":"sync","syncEvent":{"id":"evt_...","type":"message.updated.1","seq":33,
 "aggregateID":"ses_...","data":{...},"properties":{...}},"id":"evt_..."}
```

and live:

```json
{"id":"evt_...","type":"message.updated","properties":{...}}
```

Observed event types (one short run): `server.connected`, `server.heartbeat` (periodic),
`message.updated` (= sync `message.updated.1`), `message.part.updated` (= sync
`message.part.updated.1`), `session.updated` (= sync `session.updated.1`),
`session.turn.open`, `session.status` (`properties.status.type` ∈ `busy`), `session.diff`,
`message.part.delta`, `session.idle`, `session.turn.close`.

Live payload homes:

| Event | Location |
|---|---|
| `session.updated` | `properties.info` (full session) |
| `message.updated` | `properties.info` (message info) |
| `message.part.updated` | `properties.part` (full part) |
| `message.part.delta` | `properties.{sessionID,messageID,partID,field:"text",delta}` |
| `session.status` | `properties.{sessionID,status:{type}}` |
| `session.turn.open/close`, `session.idle` | `properties.sessionID` |
| `server.connected` | `properties:{}` |

## `message.part.delta` semantics

Full `message.part.updated` events **still arrive** alongside deltas (7 full-part vs 4 delta
for one reply). Deltas are per-chunk text (`delta:"The"`, ...). Store policy: **append** on
delta, **replace** on full-part update. Deltas carry `field:"text"`.

## Dedup requirement confirmed

Because every DB event arrives twice (sync wrapper + live, same `id`), de-duplication by
`event.id` is mandatory before fan-out, otherwise phones see every event twice. Sync events
carry `seq` (continuous per DB); live events do not. The server therefore assigns its own
monotonic cursor for the ring buffer / replay.

## SDK param shapes (from VSCode extension `dist/extension.js`, kilo-code 7.7.9)

- `POST /session/{sessionID}/message` body keys: `messageID, model, agent, noReply, tools,
  format, system, variant, snapshotInitialization, editorContext, parts` (+ query
  `directory`, `workspace`). Same for `/prompt_async`.
- `POST /session/{sessionID}/command` body: `messageID, agent, model, arguments, command,
  variant, snapshotInitialization, parts`.
- `POST /permission/{requestID}/reply` body: `reply, message, interactive`.
- `POST /permission/{requestID}/always-rules` body: `approvedAlways, deniedAlways`.
- `POST /question/{requestID}/reply` body: `answers`; `POST /question/{requestID}/reject` no body.
- `POST /session` (`session.create`) exists with `parentID, title, agent, model, metadata,
  permission, platform, ...` (used for forking; not part of v1 phone contract).
- Message POST has **no** `parentID`/`replyTo` key → branching/reply-to-message requires a
  session fork; v1 returns `not_supported` for `replyToMessageID`.

## Not validated live (recorded as open items)

- Real `permission.asked` / `question.asked` round-trip: the default `code` agent's
  permission config auto-allows the probed operations (shell `*` allow; external-directory
  write to `/tmp/kilo` did not ask). No live `ask` was triggered without modifying the
  user's global permission config, which the server must not do. The write path is
  implemented per SDK shapes above and covered by the e2e script.
- `POST /session/{id}/diff` response shape (probe at implementation time).
- Per-session `GET /api/session/{id}/event?after=` route not found in the 7.7.9 bundle
  (may be `/api/session/{id}/event` in a newer SDK); global `/event` remains the primary leg.