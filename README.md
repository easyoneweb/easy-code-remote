# easy-code-remote

A Go companion server that runs on your home Linux PC, exposes a secure HTTPS API to your
Android phone **over the public internet** (router port-forward + TLS), and lets the phone
observe and drive every Kilo Code session (VSCode windows + CLI TUIs) — **without** a Kilo
gateway/account.

```
[Android phone]  HTTPS + Authorization: Bearer <token>
      │  public internet, router port-forward -> PC:8443
      ▼
[easy-code-remote]  TLS + token auth + rate limit
      │  spawns and supervises its own kilo serve (127.0.0.1:18500)
      ▼
[kilo serve]  -> shared ~/.local/share/kilo/kilo.db
      ▼
[VSCode windows + CLI TUIs]
```

## Prerequisites

- Linux PC with systemd.
- Go 1.22+ (build only).
- A `kilo` binary (discovery order: config `kilo.bin` → env `KILO_BIN` → `which kilo` →
  newest `~/.vscode/extensions/kilocode.kilo-code-*/bin/kilo`).
- Router port-forward to the PC (TCP 8443) and a firewall rule (`ufw allow 8443/tcp`).

## Build & test

```sh
make build    # produces ./easy-code-remote
make check    # gofmt + go vet + go test
make test
```

End-to-end test (needs a kilo binary): `scripts/e2e.sh`.

## First run

```sh
./easy-code-remote serve
```

On first run the server:

1. creates `~/.config/easy-code-remote/config.json` (mode 0600) with a random 256-bit
   bearer token;
2. generates a self-signed certificate into `~/.config/easy-code-remote/tls/`;
3. spawns `kilo serve --port 18500 --hostname 127.0.0.1` with its own random
   `KILO_SERVER_PASSWORD`;
4. prints the URL for the phone, the token location, and reminders for
   firewall / port-forward.

The config directory defaults to `~/.config/easy-code-remote` and can be overridden with
the `EASY_CODE_REMOTE_CONFIG_DIR` environment variable (the systemd unit sets it).

Example config (`~/.config/easy-code-remote/config.json`):

```json
{
  "listen_addr": "0.0.0.0:8443",
  "tls": {"cert": ".../tls/cert.pem", "key": ".../tls/key.pem"},
  "token": "<64 hex chars>",
  "kilo": {"bin": "", "port": 18500, "hostname": "127.0.0.1"},
  "log_level": "info",
  "rate_limit": {"rate": 30, "burst": 60},
  "mtls": false,
  "client_ca": ""
}
```

## Run as a service

```sh
sudo scripts/install.sh        # installs binary + systemd unit, enables + starts
systemctl status easy-code-remote
```

The unit is `scripts/easy-code-remote.service`. It runs as your desktop user (the service
defaults to `User=` being replaced by the invoking user at install time) so it can talk to
the shared Kilo data directory.

## CLI

| Command            | Purpose                                                        |
|--------------------|----------------------------------------------------------------|
| `serve` (default)  | Run the server (supervises `kilo serve`).                      |
| `status`           | Show config path, token/TLS status, kilo version, engine state.|
| `doctor`           | Full environment check (kilo binary, port, TLS, engine).       |
| `token rotate`     | Regenerate the bearer token and print the new one.             |
| `cert regen`       | Regenerate the self-signed certificate.                        |

## Phone

- Base URL: `https://<pc>:8443`, all `/api/v1` endpoints require
  `Authorization: Bearer <token>` (except `GET /health`).
- Full contract (single source of truth): [`docs/protocol.md`](docs/protocol.md).

### Android app (`android/`)

A native Kotlin + Jetpack Compose app (single activity, MVVM) in this repository. It
connects over public TLS to this server and provides live session lists, streaming
transcripts, message/abort/command, permission + question approvals, and background
notifications — no Kilo gateway/account, no Firebase.

- Build: open `android/` in Android Studio and run on a real device (min SDK 26, target 35).
  Sideload the APK — there is no store distribution for v1.
- First connect is TOFU (trust-on-first-use): the app shows the server certificate
  fingerprint; after you confirm, it is pinned. `easy-code-remote cert regen` then shows a
  blocking "certificate changed" screen instead of a silent error.
- Dev without public internet: `adb reverse tcp:8443 tcp:8443`, then connect to
  `https://127.0.0.1:8443` (debug builds allow cleartext only for localhost/LAN).

### Certificate trust

The self-signed certificate is not trusted by Android. The Android app uses TOFU pinning,
so no manual install is needed. For other clients, either:

- import `~/.config/easy-code-remote/tls/cert.pem` into the Android device (Settings →
  Security → Install certificate), or
- use a real domain + Let's Encrypt and drop `cert.pem`/`key.pem` into
  `~/.config/easy-code-remote/tls/` (paths are configurable in `config.json`).

## TLS with a real certificate

Replace `~/.config/easy-code-remote/tls/cert.pem` and `key.pem` with your real certificate
(e.g. from Let's Encrypt via `certbot certonly --standalone`) and restart the service.
The self-signed cert is only a convenience for first setup.

## Failure modes

- `kilo` missing → startup error with install hint; `doctor` explains.
- `kilo serve` crashes → supervisor restarts with backoff; phones get `engine.disconnected`
  / `engine.connected` events.
- Phone SSE drop → reconnect with `?cursor=` resumes from the in-memory ring buffer; if the
  buffer rolled over, a `resync.required` event is sent (call `GET /api/v1/sessions`).
- Missed `permission.asked`/`question.asked` (app closed, buffer rollover) → the phone
  recovers the payloads via `GET /api/v1/sessions/{id}/pending`; older servers without that
  endpoint degrade to "needs approval on PC".
- Cert expiry → `cert regen` (or replace files) and restart.

## Security notes

- Token is compared in constant time; rate limiting is per client IP.
- TLS 1.2+ enforced; optional mTLS via `mtls: true` + `client_ca` in config.
- The Kilo engine port (18500) stays bound to 127.0.0.1 and is never exposed.
- The server never touches `kilo.db` directly and never uses the Kilo gateway/account.