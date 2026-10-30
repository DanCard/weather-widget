#!/bin/bash
#
# Capture launcher state while a widget looks wrong (read-only)
# Run before touching the device: pressing HOME or restarting the launcher destroys the evidence.
# Fast captures run on every target first so a slower bugreport cannot change what they record.
#
# Usage:
#   ./scripts/capture-launcher-state.sh                # Capture every running emulator
#   ./scripts/capture-launcher-state.sh -s SERIAL      # Capture a specific adb target
#   ./scripts/capture-launcher-state.sh -h             # Show help
#
# Output: captures/launcher-state-<timestamp>/<serial>/ (gitignored)
#   screen.png    screenshot
#   top.txt       live view tree; check the graph_view flags (V visible, I invisible, G gone)
#   bugreport.zip all logcat buffers and system dumps
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SERIALS=()

usage() {
    sed -n '3,15p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
}

while getopts "s:h" opt; do
    case "$opt" in
        s) SERIALS+=("$OPTARG") ;;
        h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done

if [ ${#SERIALS[@]} -eq 0 ]; then
    mapfile -t SERIALS < <(adb devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ {print $1}')
fi
if [ ${#SERIALS[@]} -eq 0 ]; then
    echo "No running emulators found" >&2
    exit 1
fi

# adb -s waits forever for an absent target, so reject one before the first capture.
for serial in "${SERIALS[@]}"; do
    if [ "$(adb -s "$serial" get-state 2>/dev/null)" != "device" ]; then
        echo "Target not available: $serial" >&2
        exit 1
    fi
done

OUT_DIR="$PROJECT_DIR/captures/launcher-state-$(date +%Y%m%d_%H%M%S)"
FAILED=0

capture_fast() {
    local serial="$1" dir="$OUT_DIR/$1"
    mkdir -p "$dir"
    echo "$serial"
    adb -s "$serial" exec-out screencap -p > "$dir/screen.png" && echo "  screen.png" || return 1
    adb -s "$serial" shell dumpsys activity top > "$dir/top.txt" && echo "  top.txt" || return 1
    grep -E 'LauncherAppWidgetHostView|app:id/graph_view' "$dir/top.txt" | sed 's/^ */    /'
    return 0
}

echo "Capturing ${#SERIALS[@]} target(s) to $OUT_DIR"
for serial in "${SERIALS[@]}"; do
    capture_fast "$serial" || { echo "  FAILED fast capture on $serial" >&2; FAILED=1; }
done

# Bugreports run concurrently so one slow target does not delay the others.
pids=()
for serial in "${SERIALS[@]}"; do
    echo "$serial: bugreport.zip (takes a few minutes)"
    adb -s "$serial" bugreport "$OUT_DIR/$serial/bugreport.zip" > /dev/null &
    pids+=($!)
done
for i in "${!pids[@]}"; do
    wait "${pids[$i]}" || { echo "FAILED bugreport on ${SERIALS[$i]}" >&2; FAILED=1; }
done

echo "Done: $OUT_DIR"
exit "$FAILED"
