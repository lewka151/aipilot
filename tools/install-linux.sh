#!/usr/bin/env bash
# AIPilot Linux user-level installer (Ubuntu 24.04/26.04)
set -euo pipefail

DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_ROOT="${INSTALL_ROOT:-$HOME/.local/share/AIPilot}"
BIN_LINK="${HOME}/.local/bin/aipilot"
DESKTOP_FILE="${HOME}/.local/share/applications/aipilot.desktop"

log() { echo "[AIPilot install] $*"; }

find_app_image() {
  local candidates=(
    "${SCRIPT_DIR}/AIPilot"
    "${SCRIPT_DIR}/../dist/AIPilot"
    "${SCRIPT_DIR}/../../dist/AIPilot"
  )
  local dir
  for dir in "${candidates[@]}"; do
    if [[ -e "${dir}/bin/AIPilot" || -e "${dir}/bin/AIPilot.bin" ]]; then
      echo "$(cd "$(dirname "$dir")" && pwd)/$(basename "$dir")"
      return 0
    fi
  done
  return 1
}

pkg_installed() {
  dpkg -s "$1" >/dev/null 2>&1
}

pkg_in_apt() {
  apt-cache show "$1" >/dev/null 2>&1
}

# Prefer t64 names (Ubuntu 24.04+ / 26.04); fall back to pre-transition packages.
need_any_pkg() {
  local c
  for c in "$@"; do
    if pkg_installed "$c"; then
      return 0
    fi
  done
  for c in "$@"; do
    if pkg_in_apt "$c"; then
      missing+=("$c")
      return 0
    fi
  done
  log "WARN: no candidate package: $*"
  return 0
}

ensure_deps() {
  if ! command -v dpkg >/dev/null 2>&1; then
    log "WARN: not Debian/Ubuntu — skip apt deps"
    return 0
  fi
  missing=()
  need_any_pkg libgtk-3-0t64 libgtk-3-0
  need_any_pkg libglib2.0-0t64 libglib2.0-0
  need_any_pkg libatk1.0-0t64 libatk1.0-0
  need_any_pkg libatk-bridge2.0-0t64 libatk-bridge2.0-0
  need_any_pkg libasound2t64 libasound2
  need_any_pkg libcups2t64 libcups2
  need_any_pkg libwebkit2gtk-4.1-0 libwebkit2gtk-4.0-0
  local p
  for p in libpango-1.0-0 libx11-6 libxext6 libxrender1 libxtst6 libxi6 \
           libfontconfig1 libgl1 libdrm2 libgbm1 libnss3 libnspr4 xvfb xwayland; do
    need_any_pkg "$p"
  done
  if ((${#missing[@]} == 0)); then
    return 0
  fi
  log "Installing OS packages: ${missing[*]}"
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: would run sudo apt-get install -y ${missing[*]}"
    return 0
  fi
  sudo apt-get update -qq
  if ! sudo apt-get install -y "${missing[@]}"; then
    log "WARN: apt could not install some packages; JavaFX needs GTK3 + X11/XWayland"
  fi
}

stop_running() {
  pkill -f "${INSTALL_ROOT}/bin/AIPilot" 2>/dev/null || true
  sleep 0.3
}

install_app() {
  local src
  if ! src="$(find_app_image)"; then
    echo "Missing AIPilot/bin/AIPilot — build with ./gradlew packageLinuxApp first" >&2
    exit 1
  fi
  chmod +x "${src}/bin/AIPilot" "${src}/bin/AIPilot.bin" 2>/dev/null || true
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: would copy ${src} → ${INSTALL_ROOT}"
    return 0
  fi
  stop_running
  mkdir -p "$(dirname "$INSTALL_ROOT")"
  rm -rf "${INSTALL_ROOT}.old" 2>/dev/null || true
  if [[ -d "$INSTALL_ROOT" ]]; then
    mv "$INSTALL_ROOT" "${INSTALL_ROOT}.old" || true
  fi
  cp -a "$src" "$INSTALL_ROOT"
  chmod +x "${INSTALL_ROOT}/bin/AIPilot" 2>/dev/null || true
  chmod +x "${INSTALL_ROOT}/bin/AIPilot.bin" 2>/dev/null || true
  find "${INSTALL_ROOT}" -type f -name java -exec chmod +x {} \; 2>/dev/null || true
  mkdir -p "${HOME}/.local/bin" "${HOME}/.local/share/applications"
  cat > "$BIN_LINK" <<EOF
#!/usr/bin/env bash
export GDK_BACKEND="\${GDK_BACKEND:-x11}"
exec "${INSTALL_ROOT}/bin/AIPilot" "\$@"
EOF
  chmod +x "$BIN_LINK"
  local icon="${INSTALL_ROOT}/lib/AIPilot.png"
  if [[ ! -f "$icon" ]]; then
    icon="${INSTALL_ROOT}/lib/app/AIPilot.png"
  fi
  cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=AIPilot
Comment=Qwen/DeepSeek orchestrator
Exec=env GDK_BACKEND=x11 ${INSTALL_ROOT}/bin/AIPilot
Icon=${icon}
Terminal=false
Categories=Development;
StartupWMClass=AIPilot
EOF
  log "Installed to ${INSTALL_ROOT}"
  log "Launcher: ${BIN_LINK}"
}

ensure_deps
install_app
log "Done. Run: aipilot"
