#!/usr/bin/env bash
# Install easy-code-remote as a systemd service for the current (desktop) user.
# Usage: sudo scripts/install.sh [--user <name>]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
USER_NAME="${2:-}"
if [ -z "$USER_NAME" ]; then
    USER_NAME="${SUDO_USER:-$USER}"
fi
if [ "$(id -u)" != "0" ]; then
    echo "error: run with sudo (the unit is installed system-wide): sudo $0 [--user <name>]" >&2
    exit 1
fi

echo "==> Building binary"
make -C "$ROOT" build

echo "==> Installing /usr/local/bin/easy-code-remote"
install -m 0755 "$ROOT/easy-code-remote" /usr/local/bin/easy-code-remote

echo "==> Installing systemd unit for user '$USER_NAME'"
sed "s/__USER__/$USER_NAME/g" "$ROOT/scripts/easy-code-remote.service" > /etc/systemd/system/easy-code-remote.service

systemctl daemon-reload
systemctl enable easy-code-remote
systemctl restart easy-code-remote

echo "==> Done. Status:"
systemctl --no-pager status easy-code-remote --lines=0 || true
echo
echo "Reminders:"
echo "  - the phone connects to https://<public-ip>:8443 (Bearer token from"
echo "    /home/$USER_NAME/.config/easy-code-remote/config.json)"
echo "  - open the firewall:  sudo ufw allow 8443/tcp"
echo "  - forward TCP 8443 on the router to this PC"
echo "  - 'easy-code-remote doctor' checks the environment; 'token rotate' re-issues the token"