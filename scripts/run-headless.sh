#!/usr/bin/env bash
# AIPilot headless launcher for Ubuntu (Xvfb).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${DIR}/aipilot.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR" >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "Install Java 21: sudo apt install -y openjdk-21-jre-headless" >&2
  exit 3
fi
if ! command -v Xvfb >/dev/null 2>&1 && ! command -v xvfb-run >/dev/null 2>&1; then
  echo "Install xvfb: sudo apt install xvfb" >&2
  exit 2
fi
if command -v xvfb-run >/dev/null 2>&1; then
  exec xvfb-run -a java -jar "$JAR" --headless "$@"
else
  export DISPLAY="${DISPLAY:-:99}"
  if ! xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; then
    Xvfb "$DISPLAY" -screen 0 1920x1080x24 -nolisten tcp &
    sleep 0.5
  fi
  exec java -jar "$JAR" --headless "$@"
fi
