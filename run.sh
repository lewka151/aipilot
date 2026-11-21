#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ -x "$PWD/.tmp-jdk21/jdk-21.0.12+8/bin/java" ]]; then
  export JAVA_HOME="$PWD/.tmp-jdk21/jdk-21.0.12+8"
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  :
elif command -v java >/dev/null 2>&1; then
  echo "Using java from PATH: $(command -v java)"
else
  echo "JDK 21 not found. Install openjdk-21-jdk and set JAVA_HOME." >&2
  exit 1
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
  echo "Using JAVA_HOME=${JAVA_HOME}"
fi

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8}"
export GDK_BACKEND="${GDK_BACKEND:-x11}"

if [[ "${1:-}" == "--headless" ]]; then
  exec ./gradlew run --args="--headless ${*:2}"
fi
exec ./gradlew run --args="$*"
