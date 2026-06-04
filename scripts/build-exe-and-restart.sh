#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="/home/dcar/projects/weather-widget"
AUTOSTART_SCRIPT="$REPO_DIR/scripts/desktop-app-launcher-and-autostart.sh"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/weather-widget"
LOG_FILE="$LOG_DIR/restart-distributable-$(date +%Y%m%d-%H%M%S).log"

QUIT_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/.quit"

mkdir -p "$LOG_DIR"
cd "$REPO_DIR"

echo "Stopping running desktop app instance (graceful signal)..."
touch "$QUIT_FILE"
sleep 1

echo "Building desktop distributable..."
./gradlew :desktop:createDistributable

echo "Starting desktop app through autostart launcher..."
nohup "$AUTOSTART_SCRIPT" >>"$LOG_FILE" 2>&1 &
echo "Started launcher pid $!. Logs: $LOG_FILE"
