#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="/home/dcar/projects/weather-widget"
APP_BIN="$REPO_DIR/desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/weather-widget"
LOG_FILE="$LOG_DIR/autostart-$(date +%Y%m%d-%H%M%S).log"

mkdir -p "$LOG_DIR"

# Clean up empty logs older than 3 days
find "$LOG_DIR" -name "*.log" -type f -empty -mtime +3 -delete || true
# Clean up all logs older than 90 days
find "$LOG_DIR" -name "*.log" -type f -mtime +90 -delete || true

cd "$REPO_DIR"

{
  echo "[$(date -Is)] weather-widget desktop autostart"
  if [ ! -x "$APP_BIN" ]; then
    echo "[$(date -Is)] distributable missing; building :desktop:createDistributable"
    ./gradlew :desktop:createDistributable
  fi
  echo "[$(date -Is)] exec $APP_BIN"
} >>"$LOG_FILE" 2>&1

exec "$APP_BIN" "$@" >>"$LOG_FILE" 2>&1
