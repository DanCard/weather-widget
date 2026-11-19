#!/usr/bin/env bash
# Shared "make sure an emulator is up" helpers, sourced by staggered-tests.sh (boots one beside the
# unit tests) and verify-apk-dex.sh (release check: boots one when none is running).
#
# Which AVD boots rotates, so every form factor gets exercised: the AVD after the one booted last,
# skipping any not installed. Remembered in BOOT_AVD_STATE; losing it just starts over at the first.
# Medium_Phone_API_28 and _30 are compatibility targets, started by hand when needed rather than
# booted here (arch/testing-strategy.md). The boot itself is emulator-tests.sh --boot-only (unlock,
# readiness), so there is one boot procedure.

_EMULATOR_BOOT_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_EMULATOR_BOOT_PROJECT_DIR="$(cd "$_EMULATOR_BOOT_LIB_DIR/../.." && pwd)"

BOOT_AVDS=(Generic_Foldable_API36 Medium_Phone_API_36 Pixel_Tablet_API36)
BOOT_AVD_STATE="${BOOT_AVD_STATE:-$_EMULATOR_BOOT_PROJECT_DIR/logs/.last-booted-avd}"
EMULATOR_BOOT_SCRIPT="$_EMULATOR_BOOT_PROJECT_DIR/scripts/emulator-tests.sh"

# The adb binary: $ADB, the SDK's platform-tools, or whatever is on PATH.
resolve_adb() {
    local adb_bin="${ADB:-$HOME/.Android/Sdk/platform-tools/adb}"
    [ -x "$adb_bin" ] || adb_bin="$(command -v adb || true)"
    echo "$adb_bin"
}

# Serial of the first emulator in state "device" (never a phone); empty when none.
running_emulator() {
    local adb_bin="$1"
    "$adb_bin" devices 2>/dev/null | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" { print $1; exit }'
}

# The next AVD in the rotation that is installed; returns 1 when none is.
next_boot_avd() {
    local adb_bin="$1" installed last i n candidate
    installed=$("$(dirname "$(dirname "$adb_bin")")/emulator/emulator" -list-avds 2>/dev/null)
    last=$(cat "$BOOT_AVD_STATE" 2>/dev/null)
    n=${#BOOT_AVDS[@]}
    local start=0
    for i in "${!BOOT_AVDS[@]}"; do
        [ "${BOOT_AVDS[$i]}" = "$last" ] && start=$(( (i + 1) % n ))
    done
    for i in $(seq 0 $((n - 1))); do
        candidate="${BOOT_AVDS[$(( (start + i) % n ))]}"
        if grep -qx "$candidate" <<<"$installed"; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}

# Picks the next rotating AVD and fills BOOT_ARGS for `emulator-tests.sh` (--boot-only [-e avd]) and
# BOOT_AVD (empty when none is installed: emulator-tests.sh then boots its default). Recorded at the
# pick, not after the boot, so an AVD that fails to boot is not retried forever.
pick_boot_avd() {
    local adb_bin="$1"
    BOOT_ARGS=(--boot-only)
    BOOT_AVD=""
    if BOOT_AVD=$(next_boot_avd "$adb_bin"); then
        BOOT_ARGS+=(-e "$BOOT_AVD")
        mkdir -p "$(dirname "$BOOT_AVD_STATE")" && echo "$BOOT_AVD" > "$BOOT_AVD_STATE"
    else
        BOOT_AVD=""
    fi
}

# Blocks until an emulator is up, booting the next rotating AVD when none is running; prints its
# serial. Returns 1 when the boot fails. [log] receives emulator-tests.sh's boot output.
ensure_emulator() {
    local adb_bin="$1" log="${2:-/dev/null}" serial
    serial=$(running_emulator "$adb_bin")
    if [ -n "$serial" ]; then
        echo "$serial"
        return 0
    fi
    pick_boot_avd "$adb_bin"
    echo "No emulator running — booting ${BOOT_AVD:-the default} (log: $log)" >&2
    if ! "$EMULATOR_BOOT_SCRIPT" "${BOOT_ARGS[@]}" > "$log" 2>&1; then
        echo "Emulator boot failed (log: $log)" >&2
        return 1
    fi
    serial=$(running_emulator "$adb_bin")
    [ -n "$serial" ] || { echo "Emulator boot reported success but no emulator is in state device" >&2; return 1; }
    echo "$serial"
}
