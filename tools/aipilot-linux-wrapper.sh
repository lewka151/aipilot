#!/usr/bin/env bash
# JavaFX 21 on Ubuntu 26 (Wayland): GTK3 + X11, then the jpackage binary.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
export GDK_BACKEND="${GDK_BACKEND:-x11}"
NATIVE="${HERE}/AIPilot.bin"
if [[ ! -e "$NATIVE" ]]; then
  echo "Missing ${NATIVE}. Rebuild with ./gradlew packageLinuxApp" >&2
  exit 1
fi
chmod +x "$NATIVE" 2>/dev/null || true
exec "$NATIVE" "$@"
