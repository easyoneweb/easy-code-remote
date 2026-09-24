# AGENTS.md

## Project

`easy-code-remote`: a Go companion server (`server/`) that runs on the home Linux PC,
exposes a secure HTTPS API to Android phones over the public internet, and lets the phone
observe and drive every Kilo Code session (VSCode windows + CLI TUIs) **without** a Kilo
gateway/account. See `README.md` (deployment) and `docs/protocol.md` (phone↔server contract).

## Commands

- Pre-commit check: `make check` — runs `gofmt -l .`, `go vet ./...`, `go test ./...` inside `server/`.
- Build: `make build` (produces `easy-code-remote` binary).
- Test: `make test`.
- End-to-end (requires a `kilo` binary on PATH or `KILO_BIN`): `scripts/e2e.sh`.

## Commit rules

- Conventional Commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`, `perf:`.
- Message style: one short imperative summary line; match the style of `git log --oneline -10`.
- Never commit:
  - `TEMP.md` / `TEMP_*.md` — developer decision logs; must stay untracked.
  - `config.json`, `*.pem`, `*.key` — runtime credentials / TLS material.
  - the `easy-code-remote` binary, any Kilo database files (`kilo.db*`).
- Push: the repository is published at `git@github.com:easyoneweb/easy-code-remote.git`
  (default branch `main`). Pushing to the remote is allowed and expected when the user
  asks for it (or per the release process); always confirm the user's intent before
  pushing, and never force-push to shared branches.

## Changelog rules

- Maintain `CHANGELOG.md` with an `[Unreleased]` section using categories
  `Added / Changed / Fixed / Removed / Security`.
- Add an entry for every user-facing, API, or infra change. Entries in English.

## Tech stack

- Go 1.22+ module in `server/`; std library only (`net/http` + SSE). No WebSocket, no external deps.
- Direct `kilo.db` access is forbidden; all reads/writes go through the kilo HTTP API on
  `127.0.0.1:<port>`. Kilo sessions are observed via the global `/event` SSE stream.
- Go code must be `gofmt`-clean and pass `go vet`.

## Layout

- `server/` — Go module: `cmd/`, `internal/{config,auth,kilo,api,event,supervisor,store,httpapi}`, `tests/`.
- `docs/` — `protocol.md` (phone↔server API contract, single source of truth), `spike-findings.md`.
- `scripts/` — systemd unit, `install.sh`, `e2e.sh`.