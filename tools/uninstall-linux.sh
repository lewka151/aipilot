#!/usr/bin/env bash
set -euo pipefail
INSTALL_ROOT="${INSTALL_ROOT:-$HOME/.local/share/AIPilot}"
read -r -p "Remove ${INSTALL_ROOT}? [y/N] " ans
if [[ ! "$ans" =~ ^[Yy]$ ]]; then
  echo "Cancelled."
  exit 0
fi
pkill -f "${INSTALL_ROOT}/bin/AIPilot" 2>/dev/null || true
rm -f "${HOME}/.local/bin/aipilot"
rm -f "${HOME}/.local/share/applications/aipilot.desktop"
rm -rf "$INSTALL_ROOT"
echo "AIPilot removed (config in ~/.config/aipilot preserved)."
