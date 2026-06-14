#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="/home/dcar/projects/weather-widget"
AUTOSTART_SCRIPT="$REPO_DIR/scripts/desktop-app-launcher-and-autostart.sh"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/weather-widget"
LOG_FILE="$LOG_DIR/build-all-$(date +%Y%m%d-%H%M%S).log"
QUIT_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/.quit"
SHOW_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/.show"

mkdir -p "$LOG_DIR"
cd "$REPO_DIR"

printf "\t Building desktop distributable + Android debug APK in parallel...\n\n\t"
set -x
./gradlew --parallel :desktop:createDistributable installDebug
{ set +x; } 2>/dev/null

printf "\n\t Stopping running desktop app instance(s)...\n"
set -x
touch "$QUIT_FILE"
{ set +x; } 2>/dev/null
APP_PROC_PATTERN='weather-widget-desktop/bin/weather-widget-desktop'
for _ in 1 2 3 4 5 6; do
  pgrep -f "$APP_PROC_PATTERN" >/dev/null 2>&1 || break
  sleep 0.25
done
pkill -9 -f "$APP_PROC_PATTERN" 2>/dev/null || true

printf "\n\t Starting desktop app through autostart launcher: $AUTOSTART_SCRIPT\n"
set -x
nohup "$AUTOSTART_SCRIPT" >>"$LOG_FILE" 2>&1 & disown
{ set +x; } 2>/dev/null
sleep 1
printf "\t Started launcher pid $!. Logs: $LOG_FILE\n"

sleep 3
touch "$SHOW_FILE"
