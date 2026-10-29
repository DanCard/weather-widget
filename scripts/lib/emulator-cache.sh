# shellcheck shell=bash
#
# Helpers for caching emulator test execution and results.
#
# Follows the model of unit-tests.sh: if code/resources are unchanged since the last
# successful run, report the cached result and avoid repeating 40-90s emulator runs.

EMULATOR_CACHE_DIR="${PROJECT_DIR:-$(pwd)}/logs/emulator-tests"
EMULATOR_CACHE_FILE="$EMULATOR_CACHE_DIR/.last-successful-run"

get_emulator_cache_file() {
    local avd_name="${1:-}"
    if [ -n "$avd_name" ]; then
        local clean_avd="${avd_name//[^a-zA-Z0-9_-]/_}"
        echo "$EMULATOR_CACHE_DIR/.last-successful-run-${clean_avd}"
    else
        echo "$EMULATOR_CACHE_DIR/.last-successful-run"
    fi
}

get_emulator_avd() {
    local serial="$1"
    local adb_bin="${2:-adb}"
    local avd
    avd=$("$adb_bin" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')
    if [ -z "$avd" ] || [ "$avd" = "KO" ]; then
        avd=$("$adb_bin" -s "$serial" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')
    fi
    if [ -z "$avd" ]; then
        avd=$("$adb_bin" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    fi
    echo "${avd:-Unknown}"
}

compute_emulator_fingerprint() {
    local root_dir="${1:-$PROJECT_DIR}"
    (
        cd "$root_dir" || return 1
        {
            git rev-parse HEAD 2>/dev/null || echo "no-git"
            git status --porcelain app shared gradle build.gradle.kts settings.gradle.kts gradle.properties 2>/dev/null
            git diff HEAD -- app shared gradle build.gradle.kts settings.gradle.kts gradle.properties 2>/dev/null
            git ls-files --others --exclude-standard app shared gradle 2>/dev/null | sort | while IFS= read -r f; do
                [ -f "$f" ] && sha256sum "$f" 2>/dev/null
            done
        } | sha256sum | awk '{print $1}'
    )
}

read_emulator_cache_field() {
    local field="$1"
    local file="${2:-$EMULATOR_CACHE_FILE}"
    [ -f "$file" ] || return 1
    grep -E "^${field}=" "$file" 2>/dev/null | head -1 | cut -d'=' -f2-
}

is_emulator_cache_valid() {
    local req_test_class="${1:-}"
    local req_avd="${2:-}"
    local explicit_avd="${3:-false}"
    local root_dir="${4:-$PROJECT_DIR}"

    local cache_file
    if [ -n "$req_avd" ]; then
        cache_file=$(get_emulator_cache_file "$req_avd")
        if [ ! -f "$cache_file" ] && [ "$explicit_avd" = false ]; then
            cache_file="$EMULATOR_CACHE_FILE"
        fi
    else
        cache_file="$EMULATOR_CACHE_FILE"
    fi
    [ -f "$cache_file" ] || return 1

    local cached_fingerprint cached_test_class cached_avd cached_total cached_passed cached_failed cached_errors
    cached_fingerprint=$(read_emulator_cache_field "fingerprint" "$cache_file")
    cached_test_class=$(read_emulator_cache_field "test_class" "$cache_file")
    cached_avd=$(read_emulator_cache_field "avd_name" "$cache_file")
    cached_total=$(read_emulator_cache_field "total_tests" "$cache_file")
    cached_passed=$(read_emulator_cache_field "passed_tests" "$cache_file")
    cached_failed=$(read_emulator_cache_field "failed_tests" "$cache_file")
    cached_errors=$(read_emulator_cache_field "error_tests" "$cache_file")

    [ -n "$cached_fingerprint" ] || return 1
    [ "${cached_total:-0}" -gt 0 ] 2>/dev/null || return 1
    [ "${cached_failed:-0}" -eq 0 ] 2>/dev/null || return 1
    [ "${cached_errors:-0}" -eq 0 ] 2>/dev/null || return 1

    # Check test class scope
    local target_scope="ALL"
    [ -n "$req_test_class" ] && target_scope="$req_test_class"
    [ "$cached_test_class" = "$target_scope" ] || return 1

    # Check AVD if explicitly requested
    if [ "$explicit_avd" = true ] && [ -n "$req_avd" ]; then
        [ "$cached_avd" = "$req_avd" ] || return 1
    fi

    # Check source fingerprint
    local current_fingerprint
    current_fingerprint=$(compute_emulator_fingerprint "$root_dir")
    [ "$cached_fingerprint" = "$current_fingerprint" ] || return 1

    return 0
}

write_emulator_cache() {
    local test_class="${1:-ALL}"
    local avd_name="${2:-Unknown}"
    local total="${3:-0}"
    local passed="${4:-0}"
    local failed="${5:-0}"
    local errors="${6:-0}"
    local skipped="${7:-0}"
    local duration="${8:-0}"
    local log_file="${9:-}"
    local root_dir="${10:-$PROJECT_DIR}"

    # Only cache runs with 0 failures and 0 errors, and at least 1 test
    [ "${total:-0}" -gt 0 ] 2>/dev/null || return 0
    [ "${failed:-0}" -eq 0 ] 2>/dev/null || return 0
    [ "${errors:-0}" -eq 0 ] 2>/dev/null || return 0

    local current_fingerprint
    current_fingerprint=$(compute_emulator_fingerprint "$root_dir")
    [ -n "$current_fingerprint" ] || return 0

    mkdir -p "$EMULATOR_CACHE_DIR"
    local tmp_file="$EMULATOR_CACHE_DIR/.last-successful-run.tmp.$$"
    local now_epoch now_human
    now_epoch=$(date +%s)
    now_human=$(date '+%H:%M')

    cat > "$tmp_file" <<EOF
fingerprint=$current_fingerprint
test_class=$test_class
avd_name=$avd_name
total_tests=$total
passed_tests=$passed
failed_tests=$failed
error_tests=$errors
skipped_tests=$skipped
duration=$duration
timestamp=$now_epoch
timestamp_human=$now_human
log_file=$log_file
EOF
    mv "$tmp_file" "$EMULATOR_CACHE_FILE"
    if [ -n "$avd_name" ]; then
        cp "$EMULATOR_CACHE_FILE" "$(get_emulator_cache_file "$avd_name")"
    fi
}

clear_emulator_cache() {
    local avd_name="${1:-}"
    if [ -n "$avd_name" ]; then
        rm -f "$(get_emulator_cache_file "$avd_name")"
    else
        rm -f "$EMULATOR_CACHE_DIR"/.last-successful-run*
    fi
}
