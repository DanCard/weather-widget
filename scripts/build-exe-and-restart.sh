#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="/home/dcar/projects/weather-widget"
AUTOSTART_SCRIPT="$REPO_DIR/scripts/desktop-app-launcher-and-autostart.sh"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/weather-widget"
LOG_FILE="$LOG_DIR/restart-distributable-$(date +%Y%m%d-%H%M%S).log"

QUIT_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/.quit"

mkdir -p "$LOG_DIR"
cd "$REPO_DIR"

printf "\t Building executable desktop distributable...\n\n\t"
set -x
./gradlew :desktop:createDistributable
{ set +x; } 2>/dev/null

printf "\t Stopping running desktop app instance(s)...\n"
set -x
touch "$QUIT_FILE"   # graceful: WatchService-driven quit
{ set +x; } 2>/dev/null
# Deterministic stop so dev rebuilds never stack instances: let the graceful signal land, then
# force-kill any survivors (daemon + UI) before launching the new build.
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
sleep 1   # No hup message prints late.  Add sleep so messages come out in expected order.
printf "\t Started launcher pid $!. Logs: $LOG_FILE\n"

# Touch .show to ensure the window is surfaced immediately on restart
# SHOW_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/.show"
# sleep 3
# touch "$SHOW_FILE"
