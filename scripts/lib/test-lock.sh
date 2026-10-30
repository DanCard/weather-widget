# shellcheck shell=bash
#
# Serializes test runs within one checkout.
#
# Every run shares the same build/ outputs, and two Gradle builds of one project corrupt each other
# (the ASM race). The lock is deliberately one per checkout rather than per bucket or module: all
# buckets depend on the same compile and ASM tasks, and :shared is upstream of everything. Worktrees
# have their own build/ and git dir, so they do not block each other.
#
# Call it before any Gradle work and before installing traps, with the script's absolute path and
# its unparsed arguments. The script is re-run under the lock, and nested scripts see
# WW_TEST_LOCK_HELD and carry on. `flock -o` keeps the lock off the Gradle daemon and emulators the
# run leaves behind, which would otherwise hold it after the run ended.
#
# WW_TEST_TIMEOUT       seconds a run may hold the lock before it is stopped (default 300)
# WW_TEST_LOCK_WAIT     seconds to queue for the lock; keep above the hold limit (default 360)

TEST_LOCK_BUSY_STATUS=75
TEST_LOCK_KILL_GRACE=30

# shellcheck source=proc-tree.sh
source "$(dirname "${BASH_SOURCE[0]}")/proc-tree.sh"

# Stops this script and everything under it once it has held the lock too long. `timeout` alone is
# not enough: it signals only the script, a trap waits for a foreground Gradle to return, and
# killing the script would free the lock with Gradle or adb still running. Polling and `disown` let
# the script exit, and any bare `wait` return, without knowing the watchdog exists.
start_test_watchdog() {
    local owner=$$ limit=$1 script_name=$2
    (
        elapsed=0
        while [ "$elapsed" -lt "$limit" ]; do
            kill -0 "$owner" 2>/dev/null || exit 0
            sleep 1
            elapsed=$((elapsed + 1))
        done
        echo "Error: $script_name stopped after ${limit}s; set WW_TEST_TIMEOUT for longer runs" >&2
        # shellcheck disable=SC2046  # one pid per word
        stop_tree $(pgrep -P "$owner" | grep -vx "$BASHPID")
        kill -TERM "$owner" 2>/dev/null
        elapsed=0
        while [ "$elapsed" -lt "$TEST_LOCK_KILL_GRACE" ]; do
            kill -0 "$owner" 2>/dev/null || exit 0
            sleep 1
            elapsed=$((elapsed + 1))
        done
        kill -KILL "$owner" 2>/dev/null
    ) < /dev/null > /dev/null &
    disown
}

acquire_test_lock() {
    local script=$1
    shift

    case "${WW_TEST_LOCK_HELD:-}" in
        1) return 0 ;;
        acquired)
            export WW_TEST_LOCK_HELD=1
            echo "$(basename "$script") pid $$ since $(date '+%H:%M:%S')" > "$WW_TEST_LOCK_FILE"
            start_test_watchdog "${WW_TEST_TIMEOUT:-300}" "$(basename "$script")"
            return 0
            ;;
    esac

    local arg
    for arg in "$@"; do
        case "$arg" in
            -h|--help) return 0 ;;
        esac
    done

    local git_dir
    if ! git_dir=$(git -C "$(dirname "$script")" rev-parse --absolute-git-dir); then
        echo "Error: cannot locate the git dir for the test lock" >&2
        exit 1
    fi
    local lock="$git_dir/test-run.lock"
    local wait=${WW_TEST_LOCK_WAIT:-360}
    touch "$lock"

    if ! flock -n "$lock" true; then
        echo "Waiting for test lock, held by: $(cat "$lock")" >&2
    fi

    local status=0
    WW_TEST_LOCK_HELD=acquired WW_TEST_LOCK_FILE="$lock" \
        flock -o -w "$wait" -E "$TEST_LOCK_BUSY_STATUS" "$lock" "$script" "$@" || status=$?

    if [ "$status" -eq "$TEST_LOCK_BUSY_STATUS" ]; then
        echo "Error: gave up after ${wait}s waiting for test lock, held by: $(cat "$lock")" >&2
    fi
    exit "$status"
}
