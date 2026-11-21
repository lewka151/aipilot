#!/usr/bin/env bash
# Linux smoke tests (run on Ubuntu/WSL)
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== gradlew test =="
./gradlew test --no-daemon

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Skip jpackage/linux package (not Linux host)"
  exit 0
fi

echo "== packageLinuxApp =="
./gradlew packageLinuxApp --no-daemon

echo "== build linux tar =="
python3 tools/build_linux_package.py

echo "== install dry-run =="
INSTALL_ROOT=/tmp/aipilot-test-install
export INSTALL_ROOT
tar -xzf dist/aipilot-linux.tar.gz -C /tmp
/tmp/aipilot-linux/install.sh --dry-run

echo "== headless smoke (15s) =="
timeout 15 xvfb-run -a dist/AIPilot/bin/AIPilot --headless || [[ $? -eq 124 ]]

echo "Linux tests OK"
