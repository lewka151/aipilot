#!/usr/bin/env bash
# Headless launcher for installed AIPilot desktop package
set -euo pipefail
ROOT="${INSTALL_ROOT:-$HOME/.local/share/AIPilot}"
BIN="${ROOT}/bin/AIPilot"
if [[ ! -x "$BIN" ]]; then
  echo "AIPilot not installed at ${ROOT}. Run install.sh first." >&2
  exit 1
fi
if ! command -v xvfb-run >/dev/null 2>&1; then
  echo "Install xvfb: sudo apt install -y xvfb" >&2
  exit 2
fi
exec xvfb-run -a "$BIN" --headless "$@"
