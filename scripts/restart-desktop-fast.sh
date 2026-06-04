#!/usr/bin/env bash
# Fast restart: stop the running desktop app and relaunch the EXISTING distributable,
# with NO Gradle build. Use this for a plain restart that does not need to pick up code
# or build.gradle.kts changes (e.g. the app got wedged, or to re-read on-disk config).
#
# If you changed Kotlin code or JVM flags in build.gradle.kts, use
# restart-desktop-distributable.sh instead — those changes only reach the running process
# after createDistributable regenerates the launcher + its .cfg.
set -euo pipefail

REPO_DIR="/home/dcar/projects/weather-widget"
AUTOSTART_SCRIPT="$REPO_DIR/scripts/weather-widget-desktop-autostart.sh"
APP_BIN="$REPO_DIR/desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/weather-widget"
LOG_FILE="$LOG_DIR/desktop-autostart.log"

mkdir -p "$LOG_DIR"

if [ ! -x "$APP_BIN" ]; then
  echo "Distributable not built yet ($APP_BIN missing)."
  echo "Run scripts/restart-desktop-distributable.sh once to build it."
  exit 1
fi

PGREP_PAT='com.weatherwidget.desktop.MainKt|/weather-widget-desktop/bin/weather-widget-desktop|/opt/weather-widget-desktop/bin/weather-widget-desktop'

echo "Stopping running desktop app instances..."
mapfile -t PIDS < <(pgrep -f "$PGREP_PAT" | awk -v self="$$" '$1 != self')
if [ "${#PIDS[@]}" -gt 0 ]; then
  kill "${PIDS[@]}" 2>/dev/null || true
  sleep 1
fi

mapfile -t PIDS < <(pgrep -f "$PGREP_PAT" | awk -v self="$$" '$1 != self')
if [ "${#PIDS[@]}" -gt 0 ]; then
  kill -9 "${PIDS[@]}" 2>/dev/null || true
fi

echo "Relaunching existing distributable (no build)..."
nohup "$AUTOSTART_SCRIPT" >/dev/null 2>&1 &
echo "Started launcher pid $!. Logs: $LOG_FILE"
