# shellcheck shell=bash
#
# Stopping a script's background work on Ctrl-C.
#
# A non-interactive bash starts every `cmd &` with SIGINT and SIGQUIT IGNORED, and the child
# inherits that across exec (verified: `SigIgn: …06`). So Ctrl-C never reaches a backgrounded
# unit-tests.sh, its gradlew clients, or a `( gradlew … ) &` — they outlive the script that
# started them. The script must catch the signal itself and send SIGTERM, which is not ignored.
# plans/261018-staggered-tests-ctrl-c-leaves-unit-tests-running.md

# Prints every pid in [pid]'s tree, stopping each (SIGSTOP) before listing its children: a
# frozen process cannot fork a new child after the listing, and none can be reparented to init
# before it is listed (the race stop_pid_tree in unit-tests.sh documents).
freeze_tree() {
  local pid=$1 kid
  kill -STOP "$pid" 2>/dev/null || return 0
  echo "$pid"
  for kid in $(pgrep -P "$pid" 2>/dev/null); do
    freeze_tree "$kid"
  done
}

# SIGTERM to each given pid and everything under it. Frozen first, then TERM + CONT, so the whole
# tree goes at once. A gradlew client that dies this way cancels its build in the daemon.
stop_tree() {
  local pids=() pid
  for pid in "$@"; do
    [ -n "$pid" ] || continue
    mapfile -t -O "${#pids[@]}" pids < <(freeze_tree "$pid")
  done
  [ "${#pids[@]}" -gt 0 ] || return 0
  kill -TERM "${pids[@]}" 2>/dev/null || true
  kill -CONT "${pids[@]}" 2>/dev/null || true
}

# Every direct child of the calling shell — its background jobs, process substitutions, and
# the foreground command it is waiting on.
own_children() {
  pgrep -P "$$" 2>/dev/null || true
}
