#!/usr/bin/env bash
#
# This script is the launcher used by the autostart desktop entry:
# ~/.config/autostart/weather-widget-desktop.desktop
# It handles auto-detecting X11 variables, cleans up stale autostart logs,
# ensures the desktop app is built, and executes the binary.
#
set -euo pipefail

# Auto-detect DISPLAY and XAUTHORITY if not set (e.g. run from a non-interactive/sandbox shell)
if [ -z "${DISPLAY:-}" ]; then
  PANEL_PID=$(pgrep -u "$USER" xfce4-panel | head -n 1 || true)
  if [ -n "$PANEL_PID" ]; then
    export DISPLAY=$(cat /proc/"$PANEL_PID"/environ | tr '\0' '\n' | grep "^DISPLAY=" | cut -d= -f2- || true)
    export XAUTHORITY=$(cat /proc/"$PANEL_PID"/environ | tr '\0' '\n' | grep "^XAUTHORITY=" | cut -d= -f2- || true)
  fi
  export DISPLAY="${DISPLAY:-:0.0}"
  export XAUTHORITY="${XAUTHORITY:-$HOME/.Xauthority}"
fi

REPO_DIR="/home/dcar/projects/weather-widget"
APP_BIN="$REPO_DIR/desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/weather-widget"
LOG_FILE="$LOG_DIR/autostart-$(date +%Y%m%d-%H%M%S).log"

mkdir -p "$LOG_DIR"

# Clean up empty logs older than 3 days
find "$LOG_DIR" -name "*.log" -type f -empty -mtime +2 -delete || true
# Clean up all logs older than 90 days
find "$LOG_DIR" -name "*.log" -type f -mtime +90 -delete || true

cd "$REPO_DIR"

{
  echo "[$(date -Is)] weather-widget desktop autostart"
  echo "[$(date -Is)] resolved DISPLAY=${DISPLAY:-}, XAUTHORITY=${XAUTHORITY:-}"
  if [ ! -x "$APP_BIN" ]; then
    echo "[$(date -Is)] distributable missing; building :desktop:createDistributable"
    ./gradlew :desktop:createDistributable
  fi
  echo "[$(date -Is)] exec $APP_BIN"
} >>"$LOG_FILE" 2>&1

exec "$APP_BIN" "$@" >>"$LOG_FILE" 2>&1
