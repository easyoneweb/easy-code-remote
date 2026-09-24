# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

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