#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="/home/dcar/projects/weather-widget"
AUTOSTART_SCRIPT="$REPO_DIR/scripts/desktop-app-launcher-and-autostart.sh"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/weather-widget"
LOG_FILE="$LOG_DIR/restart-distributable-$(date +%Y%m%d-%H%M%S).log"

QUIT_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/.quit"

mkdir -p "$LOG_DIR"
cd "$REPO_DIR"

printf "\t Stopping running desktop app instance (graceful signal)...\n"
set -x
touch "$QUIT_FILE"
{ set +x; } 2>/dev/null

printf "\t Building executable desktop distributable...\n\n\t"
set -x
./gradlew :desktop:createDistributable
{ set +x; } 2>/dev/null

printf "\n\t Starting desktop app through autostart launcher: $AUTOSTART_SCRIPT\n"
set -x
nohup "$AUTOSTART_SCRIPT" >>"$LOG_FILE" 2>&1 &
{ set +x; } 2>/dev/null
sleep 1   # No hup message prints late.  Add sleep so messages come out in expected order.
printf "\t Started launcher pid $!. Logs: $LOG_FILE\n"

# Touch .show to ensure the window is surfaced immediately on restart
SHOW_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/.show"
sleep 3
touch "$SHOW_FILE"
