#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
command -v kotlinc >/dev/null || { echo 'kotlinc is required' >&2; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
kotlinc "$root/app/src/main/java/com/emerge/app/monitoring/MonitorCore.kt" \
  "$root/app/src/test/java/com/emerge/app/monitoring/MonitorCoreCases.kt" \
  -include-runtime -d "$tmp/monitor.jar"
java -cp "$tmp/monitor.jar" com.emerge.app.monitoring.MonitorCoreCases
