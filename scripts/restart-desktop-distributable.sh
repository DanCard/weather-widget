#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="/home/dcar/projects/weather-widget"
AUTOSTART_SCRIPT="$REPO_DIR/scripts/weather-widget-desktop-autostart.sh"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/weather-widget"
LOG_FILE="$LOG_DIR/desktop-autostart.log"

mkdir -p "$LOG_DIR"
cd "$REPO_DIR"

echo "Building desktop distributable..."
./gradlew :desktop:createDistributable

echo "Stopping running desktop app instances..."
mapfile -t PIDS < <(
  pgrep -f 'com.weatherwidget.desktop.MainKt|/weather-widget-desktop/bin/weather-widget-desktop|/opt/weather-widget-desktop/bin/weather-widget-desktop' \
    | awk -v self="$$" '$1 != self'
)

if [ "${#PIDS[@]}" -gt 0 ]; then
  kill "${PIDS[@]}" 2>/dev/null || true
  sleep 1
fi

mapfile -t PIDS < <(
  pgrep -f 'com.weatherwidget.desktop.MainKt|/weather-widget-desktop/bin/weather-widget-desktop|/opt/weather-widget-desktop/bin/weather-widget-desktop' \
    | awk -v self="$$" '$1 != self'
)

if [ "${#PIDS[@]}" -gt 0 ]; then
  kill -9 "${PIDS[@]}" 2>/dev/null || true
fi

echo "Starting desktop app through autostart launcher..."
nohup "$AUTOSTART_SCRIPT" >/dev/null 2>&1 &
echo "Started launcher pid $!. Logs: $LOG_FILE"
