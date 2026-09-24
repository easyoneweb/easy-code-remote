#!/usr/bin/env bash
# End-to-end test for easy-code-remote against a real kilo binary.
# Usage: scripts/e2e.sh   (kilo resolved via KILO_BIN, PATH, or the VSCode extension)
#
# Exercises: health, auth rejection, session listing, SSE stream, message send
# (message.part.updated events), abort, 404 handling, and kilo-child crash recovery.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
SRV_PID=""
SSE_PID=""
SPIKE_SID=""

cleanup() {
    [ -n "$SSE_PID" ] && kill "$SSE_PID" 2>/dev/null || true
    [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null || true
    sleep 1
    pkill -f "easy-code-remote serve" 2>/dev/null || true
    pkill -f "kilo serve --port 18999" 2>/dev/null || true
    rm -rf "$TMP"
}
trap cleanup EXIT

echo "==> Resolving kilo binary"
KILO_BIN="${KILO_BIN:-}"
if [ -z "$KILO_BIN" ]; then
    KILO_BIN="$(command -v kilo 2>/dev/null || true)"
fi
if [ -z "$KILO_BIN" ]; then
    KILO_BIN="$(ls -1dt "$HOME"/.vscode/extensions/kilocode.kilo-code-*/bin/kilo 2>/dev/null | head -1)"
fi
if [ -z "$KILO_BIN" ] || [ ! -x "$KILO_BIN" ]; then
    echo "error: no kilo binary found (set KILO_BIN, add kilo to PATH, or install the extension)" >&2
    exit 1
fi
echo "    kilo: $KILO_BIN ($("$KILO_BIN" --version 2>/dev/null | tail -1))"

echo "==> Building server"
make -C "$ROOT" build

CFG="$TMP/config.json"
cat > "$CFG" <<EOF
{"listen_addr":"127.0.0.1:18443","tls":{"cert":"","key":""},"token":"",
 "kilo":{"bin":"$KILO_BIN","port":18999,"hostname":"127.0.0.1"},
 "log_level":"warn","rate_limit":{"rate":100,"burst":200},"mtls":false}
EOF
export EASY_CODE_REMOTE_CONFIG_DIR="$TMP"

echo "==> Starting server (config dir $TMP)"
"$ROOT/easy-code-remote" serve > "$TMP/server.log" 2>&1 &
SRV_PID=$!

BASE="https://127.0.0.1:18443"
TOKEN=""
for i in $(seq 1 60); do
    if curl -sk "$BASE/health" > "$TMP/health.json" 2>/dev/null; then
        TOKEN="$(python3 -c 'import json;print(json.load(open("'"$CFG"'"))["token"])')"
        break
    fi
    sleep 0.5
done
if [ -z "$TOKEN" ]; then
    echo "FAIL: server did not become healthy" >&2
    tail -20 "$TMP/server.log" >&2
    exit 1
fi
AUTH="Authorization: Bearer $TOKEN"
echo "    server up (token=${TOKEN:0:8}...)"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }

# 1. /health
HEALTH_ENGINE="$(python3 -c 'import json;print(json.load(open("'"$TMP"'/health.json"))["engine"])')"
[ "$HEALTH_ENGINE" = "up" ] || fail "health engine=$HEALTH_ENGINE, want up"
pass "health reports engine up"

# 2. wrong token -> 401
CODE="$(curl -sk -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer wrong' "$BASE/api/v1/sessions")"
[ "$CODE" = "401" ] || fail "wrong token status=$CODE, want 401"
pass "wrong token rejected with 401"

# 3. list sessions (at least one exists from the shared DB)
COUNT="$(curl -sk -H "$AUTH" "$BASE/api/v1/sessions" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)))')"
[ "$COUNT" -ge 1 ] || fail "session count=$COUNT, want >=1"
pass "sessions listed ($COUNT)"

# 4. SSE stream emits server.connected
curl -skN -H "$AUTH" "$BASE/api/v1/events" > "$TMP/events.log" 2>&1 &
SSE_PID=$!
for i in $(seq 1 10); do
    grep -q server.connected "$TMP/events.log" 2>/dev/null && break
    sleep 0.5
done
grep -q server.connected "$TMP/events.log" || fail "no server.connected on SSE"
pass "SSE delivered server.connected"

# 5. create a throwaway session with a trivial prompt (idle afterwards)
SPIKE_SID="$("$KILO_BIN" run "Reply with exactly one word: e2e-pong" --format json 2>/dev/null \
    | python3 -c 'import json,sys
ids=[]
for line in sys.stdin:
    line=line.strip()
    if not line: continue
    try: d=json.loads(line)
    except: continue
    s=d.get("sessionID")
    if s and s not in ids: ids.append(s)
print(ids[-1] if ids else "")')"
[ -n "$SPIKE_SID" ] || fail "could not create throwaway session via kilo run"
pass "throwaway session $SPIKE_SID"

# 6. send a message -> message.part.updated events must arrive on SSE
BODY="$(curl -sk -X POST -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"text":"Reply with exactly one word: e2e-ok"}' \
    "$BASE/api/v1/sessions/$SPIKE_SID/message")"
echo "$BODY" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert "info" in d, d' || fail "message POST response unexpected: $BODY"
for i in $(seq 1 30); do
    grep -q message.part.updated "$TMP/events.log" 2>/dev/null && break
    sleep 0.5
done
grep -q message.part.updated "$TMP/events.log" || fail "no message.part.updated on SSE after send"
pass "message sent and message.part.updated streamed"

# 7. abort
ABORT="$(curl -sk -X POST -H "$AUTH" "$BASE/api/v1/sessions/$SPIKE_SID/abort")"
echo "$ABORT" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("aborted") is True, d' || fail "abort response: $ABORT"
pass "abort accepted"

# 8. nonexistent session -> 404
CODE="$(curl -sk -o /dev/null -w '%{http_code}' -H "$AUTH" "$BASE/api/v1/sessions/ses_nonexistent/messages")"
[ "$CODE" = "404" ] || fail "nonexistent session status=$CODE, want 404"
pass "nonexistent session returns 404"

# 9. kill the supervised kilo child -> engine.disconnected then recovery
KILO_CHILD="$(pgrep -f "kilo serve --port 18999" | head -1)"
[ -n "$KILO_CHILD" ] || fail "supervised kilo child not found"
kill -9 "$KILO_CHILD"
for i in $(seq 1 30); do
    grep -q engine.disconnected "$TMP/events.log" 2>/dev/null && break
    sleep 0.5
done
grep -q engine.disconnected "$TMP/events.log" || fail "no engine.disconnected after killing kilo child"
for i in $(seq 1 60); do
    HEALTH_ENGINE="$(curl -sk "$BASE/health" | python3 -c 'import json,sys;print(json.load(sys.stdin)["engine"])' 2>/dev/null || echo down)"
    [ "$HEALTH_ENGINE" = "up" ] && break
    sleep 0.5
done
[ "$HEALTH_ENGINE" = "up" ] || fail "engine did not recover after kilo child kill"
pass "engine recovered after kilo child crash"

echo
echo "ALL E2E CHECKS PASSED"