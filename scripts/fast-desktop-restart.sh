#!/usr/bin/env bash
# Fast restart: stop the running desktop app and relaunch the EXISTING distributable,
# with NO Gradle build. Use this for a plain restart that does not need to pick up code
# or build.gradle.kts changes (e.g. the app got wedged, or to re-read on-disk config).
#
# If you changed Kotlin code or JVM flags in build.gradle.kts, use
# build-exe-and-restart.sh instead — those changes only reach the running process
# after createDistributable regenerates the launcher + its .cfg.
set -euo pipefail

REPO_DIR="/home/dcar/projects/weather-widget"
AUTOSTART_SCRIPT="$REPO_DIR/scripts/desktop-app-launcher-and-autostart.sh"
APP_BIN="$REPO_DIR/desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/weather-widget"
LOG_FILE="$LOG_DIR/restart-fast-$(date +%Y%m%d-%H%M%S).log"

mkdir -p "$LOG_DIR"

if [ ! -x "$APP_BIN" ]; then
  echo "Distributable not built yet ($APP_BIN missing)."
  echo "Run scripts/build-exe-and-restart.sh once to build it."
  exit 1
fi

# Deterministic stop so dev restarts never stack instances: graceful .quit, then force-kill any
# survivors (daemon + UI) before relaunching.
QUIT_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/.quit"
APP_PROC_PATTERN='weather-widget-desktop/bin/weather-widget-desktop'
touch "$QUIT_FILE"
for _ in 1 2 3 4 5 6; do
  pgrep -f "$APP_PROC_PATTERN" >/dev/null 2>&1 || break
  sleep 0.25
done
pkill -9 -f "$APP_PROC_PATTERN" 2>/dev/null || true

echo "Relaunching existing distributable..."
nohup "$AUTOSTART_SCRIPT" >>"$LOG_FILE" 2>&1 & disown
echo "Started launcher pid $!. Logs: $LOG_FILE"

# Touch .show to ensure the window is surfaced immediately on restart
SHOW_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/.show"
sleep 3
touch "$SHOW_FILE"
