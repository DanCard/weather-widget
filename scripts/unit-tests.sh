#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
GRADLEW="$ROOT_DIR/gradlew"
RUN_MODE=""
STREAM_OUTPUT=false
LOG_FILE=""
INSTALL_MODE=false
BUCKETS=()
OVERALL_START=$(date +%s)
SINGLE_INVOCATION_REPORTED_DIR=""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_and_echo() {
  local msg=$1
  echo -e "$msg"
  if [ -n "${LOG_FILE:-}" ] && [ -f "$LOG_FILE" ]; then
    # Strip ANSI colors for the log file to keep it clean and searchable
    echo -e "$msg" | sed 's/\x1b\[[0-9;]*m//g' >> "$LOG_FILE"
  fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    --fresh)
      RUN_MODE="Fresh"
      shift
      ;;
    --cached)
      RUN_MODE=""
      shift
      ;;
    --stream)
      STREAM_OUTPUT=true
      shift
      ;;
    --log-file)
      if [ $# -lt 2 ]; then
        echo "Error: --log-file requires a value" >&2
        exit 1
      fi
      LOG_FILE="$2"
      shift 2
      ;;
    --install)
      INSTALL_MODE=true
      shift
      ;;
    *)
      BUCKETS+=("$1")
      shift
      ;;
  esac
done

# Default is cached (--cached): Gradle skips a bucket whose inputs are unchanged and the summary
# says "cached". --fresh forces every :app bucket to run (user's call 2026-10-03: cached is fine).
# Explicit bucket selection means "run only what I asked for": the parallel
# :shared/:desktop run rides along only on default (no-args) full runs.
DEFAULT_RUN=false
if [ ${#BUCKETS[@]} -eq 0 ]; then
  # All category buckets. They partition the suite (each test is in exactly one), so
  # Localization must be here — its tests run in NO duration bucket.
  BUCKETS=(Short Medium Long Localization)
  DEFAULT_RUN=true
fi

SINGLE_INVOCATION_MONITOR_PID=""
SINGLE_INVOCATION_REPORT_POLLER_PID=""

# Kill a backgrounded job together with the processes it forked.
#
# The children MUST be enumerated BEFORE the parent is signalled. `( tail -F ... | while
# read ... ) &` forks three processes: the subshell ($!), the tail, and the while-loop
# subshell. Killing $! first and only then running `pgrep -P $!` always loses the race —
# the kernel reparents the orphans to init the instant $! dies, so pgrep matches nothing
# and the tail survives the whole run holding an inotify watch. (tail never exits by
# itself here: -F retries forever, and SIGPIPE only fires on a write, which never comes
# once Gradle stops appending to the log.)
stop_pid_tree() {
  local pid=$1
  [ -n "$pid" ] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  local kids
  kids=$(pgrep -P "$pid" 2>/dev/null || true)
  kill "$pid" 2>/dev/null || true
  if [ -n "$kids" ]; then
    # shellcheck disable=SC2086  # intentional word splitting: pgrep emits one PID per line
    kill $kids 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
}

cleanup() {
  stop_pid_tree "${SINGLE_INVOCATION_MONITOR_PID:-}"
  stop_pid_tree "${SINGLE_INVOCATION_REPORT_POLLER_PID:-}"
  if [ -n "${SINGLE_INVOCATION_REPORTED_DIR:-}" ] && [ -d "$SINGLE_INVOCATION_REPORTED_DIR" ]; then
    rm -rf "$SINGLE_INVOCATION_REPORTED_DIR"
  fi
}

trap cleanup EXIT

# Elapsed time since the script started, e.g. "45s" or "1m02s".
format_elapsed() {
  local seconds=$1
  if [ "$seconds" -lt 60 ]; then
    printf '%ss' "$seconds"
  else
    printf '%dm%02ds' $((seconds / 60)) $((seconds % 60))
  fi
}

format_seconds() {
  local seconds=$1
  if [ "$seconds" -eq 1 ]; then
    printf '%s second' "$seconds"
  else
    printf '%s seconds' "$seconds"
  fi
}

bucket_result_summary() {
  local results_dir=$1
  python3 - "$results_dir" <<'PY'
import sys
from pathlib import Path
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta

results_dir = Path(sys.argv[1])
test_count = 0
failures = 0
errors = 0
skipped = 0
min_start = None
max_end = None

for xml_file in sorted(results_dir.glob("TEST-*.xml")):
    try:
        suite = ET.parse(xml_file).getroot()
        test_count += int(suite.attrib.get("tests", "0"))
        failures += int(suite.attrib.get("failures", "0"))
        errors += int(suite.attrib.get("errors", "0"))
        skipped += int(suite.attrib.get("skipped", "0"))
        
        # Calculate wall-clock span
        ts_str = suite.attrib.get("timestamp")
        duration_str = suite.attrib.get("time", "0.0")
        if ts_str:
            # fromisoformat handles 'Z' in 3.11+
            start = datetime.fromisoformat(ts_str.replace('Z', '+00:00'))
            duration = float(duration_str)
            end = start + timedelta(seconds=duration)
            
            if min_start is None or start < min_start:
                min_start = start
            if max_end is None or end > max_end:
                max_end = end
    except (ET.ParseError, ValueError, Exception):
        continue

wall_duration = 0
if min_start and max_end:
    wall_duration = int((max_end - min_start).total_seconds())
# Epoch second the last test ended: lets the caller say when the group finished, independent of
# when the summary line happens to be printed.
end_epoch = int(max_end.timestamp()) if max_end else 0

print(f"{test_count}|{failures}|{errors}|{skipped}|{wall_duration}|{end_epoch}")
PY
}

list_failed_tests() {
  local results_dir=$1
  python3 - "$results_dir" <<'PY'
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

results_dir = Path(sys.argv[1])
for xml_file in sorted(results_dir.glob("TEST-*.xml")):
    try:
        suite = ET.parse(xml_file).getroot()
        for testcase in suite.findall("testcase"):
            failure = testcase.find("failure")
            error = testcase.find("error")
            if failure is not None or error is not None:
                classname = testcase.attrib.get("classname", "UnknownClass")
                # Strip package for readability
                short_classname = classname.split(".")[-1]
                name = testcase.attrib.get("name", "UnknownTest")
                print(f"  ✗ {short_classname} > {name}")
                # Keep the assertion message: without it a one-off failure (e.g. a live-network
                # flake) is undiagnosable once the Gradle log is gone.
                node = failure if failure is not None else error
                message = (node.attrib.get("message") or "").strip()
                if not message:
                    text = (node.text or "").strip()
                    message = text.splitlines()[0] if text else ""
                for line in message.splitlines()[:4]:
                    print(f"      {line}")
    except:
        continue
PY
}

# One summary line for a test task's results. Tests are cached by default (2026-10-03): when Gradle
# skips the task (UP-TO-DATE / FROM-CACHE) the XML on disk is from an earlier run, so say "cached"
# instead of reporting that run's duration as this one's.
#   $1 label   $2 results dir   $3 Gradle log that ran the task   $4 task path (e.g. :app:testLongDebugUnitTest)
emit_results_summary() {
  local label=$1 results_dir=$2 task_log=$3 task_path=$4
  if [ ! -d "$results_dir" ] || ! compgen -G "$results_dir/TEST-*.xml" >/dev/null 2>&1; then
    return 1
  fi
  local test_count failures errors skipped duration end_epoch
  IFS='|' read -r test_count failures errors skipped duration end_epoch <<<"$(bucket_result_summary "$results_dir")"
  # "N seconds" is test execution only (first test start -> last test end); the build before it is
  # not included, so also say when the group finished relative to the script's start.
  local done_at=""
  if [ "${end_epoch:-0}" -ge "$OVERALL_START" ]; then
    done_at=" (done at $(format_elapsed $((end_epoch - OVERALL_START))))"
  fi
  local result_failures=$((failures + errors))
  local cached=false
  if [ -n "$task_log" ] && grep -qE "^> Task ${task_path} (UP-TO-DATE|FROM-CACHE)" "$task_log" 2>/dev/null; then
    cached=true
  fi
  if [ "$result_failures" -gt 0 ]; then
    log_and_echo "${test_count} ${label} tests: ${RED}${result_failures} failed.${NC}"
    list_failed_tests "$results_dir" | while IFS= read -r line; do
      log_and_echo "${RED}${line}${NC}"
    done
  elif [ "$cached" = true ]; then
    log_and_echo "${test_count} ${label} tests: cached (up to date; last ran $(date -r "$results_dir" +%H:%M 2>/dev/null || echo "?"))."
  elif [ "$skipped" -gt 0 ]; then
    log_and_echo "${test_count} ${label} tests passed (${skipped} skipped) in $(format_seconds "${duration:-0}")${done_at}."
  else
    log_and_echo "${test_count} ${label} tests passed in $(format_seconds "${duration:-0}")${done_at}."
  fi
}

emit_bucket_summary() {
  local bucket=$1 results_dir=$2 task_log=$3 task_name=$4
  emit_results_summary "${bucket,,}" "$results_dir" "$task_log" ":app:${task_name}"
}

emit_module_summary() {
  local module=$1
  emit_results_summary "$module" "$ROOT_DIR/$module/build/test-results/test" "${shared_desktop_log:-}" ":${module}:test"
}

start_single_invocation_summary_monitor() {
  local gradle_log=$1
  SINGLE_INVOCATION_REPORTED_DIR=$(mktemp -d)

  # Live feedback strategy:
  #   1. Tail the Gradle log to announce "<bucket> bucket build finished" as
  #      soon as Gradle prints the task marker for each bucket. Because Gradle
  #      re-prints that marker when parallel task output interleaves, we only
  #      act on the FIRST sighting per bucket (for the "build finished" signal,
  #      which is harmless to emit slightly early).
  #   2. Poll for each bucket's test report index.html. Gradle writes that file
  #      exactly once per task lifecycle, after the Test task fully completes
  #      (test execution + report generation). This is an authoritative
  #      completion signal, immune to log-line re-prints, and we filter out
  #      stale reports from previous runs via an mtime-vs-OVERALL_START check.
  (
    declare -A announced_execution
    tail -n 0 -F "$gradle_log" 2>/dev/null | while IFS= read -r line; do
      for bucket in "${BUCKETS[@]}"; do
        local task_name="test${bucket}DebugUnitTest${RUN_MODE}"
        local task_marker="> Task :app:${task_name}"

        if [ -z "${announced_execution[$bucket]:-}" ] && [[ "$line" == *"$task_marker"* ]]; then
          local build_elapsed=$(( $(date +%s) - OVERALL_START ))
          log_and_echo "${bucket} bucket build finished in $(format_seconds "$build_elapsed")."
          announced_execution["$bucket"]=1
        fi
      done
    done
  ) &
  SINGLE_INVOCATION_MONITOR_PID=$!

  (
    local pending=("${BUCKETS[@]}")
    # :shared/:desktop run in their own background Gradle process; report them the moment it
    # exits instead of after the (Long-bound) :app run, which made them look slow.
    if [ -n "${SHARED_DESKTOP_PID:-}" ]; then
      pending+=(shared desktop)
    fi
    while [ "${#pending[@]}" -gt 0 ]; do
      local remaining=()
      for bucket in "${pending[@]:-}"; do
        [ -z "$bucket" ] && continue
        if [ "$bucket" = shared ] || [ "$bucket" = desktop ]; then
          if ! kill -0 "$SHARED_DESKTOP_PID" 2>/dev/null; then
            # Exited: report what it left (no XML = it failed before running tests; the
            # post-run block reports that with the Gradle log).
            if emit_module_summary "$bucket"; then
              touch "$SINGLE_INVOCATION_REPORTED_DIR/$bucket"
            fi
            continue
          fi
          remaining+=("$bucket")
          continue
        fi
        local task_name="test${bucket}DebugUnitTest${RUN_MODE}"
        local report_html="$ROOT_DIR/app/build/reports/tests/${task_name}/index.html"
        local results_dir="$ROOT_DIR/app/build/test-results/${task_name}"
        # Done = a report written by THIS run, or Gradle said it skipped the task (a cached
        # bucket never rewrites its report, so the mtime test alone would wait forever).
        local done_now=false
        if grep -qE "^> Task :app:${task_name} (UP-TO-DATE|FROM-CACHE)" "$gradle_log" 2>/dev/null; then
          done_now=true
        elif [ -f "$report_html" ]; then
          local mtime
          mtime=$(stat -c %Y "$report_html" 2>/dev/null || echo 0)
          [ "$mtime" -ge "$OVERALL_START" ] && done_now=true
        fi
        if [ "$done_now" = true ] && emit_bucket_summary "$bucket" "$results_dir" "$gradle_log" "$task_name"; then
          touch "$SINGLE_INVOCATION_REPORTED_DIR/$bucket"
          continue
        fi
        remaining+=("$bucket")
      done
      pending=("${remaining[@]:-}")
      if [ "${#pending[@]}" -eq 1 ] && [ -z "${pending[0]}" ]; then
        pending=()
      fi
      if [ "${#pending[@]}" -gt 0 ]; then
        sleep 1
      fi
    done
  ) &
  SINGLE_INVOCATION_REPORT_POLLER_PID=$!
}

for bucket in "${BUCKETS[@]}"; do
  case "$bucket" in
    Short|Medium|Long|Localization) ;;
    *)
      echo "Unknown bucket: $bucket" >&2
      echo "Usage: $0 [--fresh|--cached] [Short] [Medium] [Long] [Localization]" >&2
      exit 2
      ;;
  esac
done

# Run all buckets in one Gradle process (avoids ASM races).
# Gradle's own parallel executor handles concurrent test tasks safely.
# The aggregate task covers exactly the full default bucket set.
if [ "${BUCKETS[*]}" = "Short Medium Long Localization" ] && [ "$RUN_MODE" = "Fresh" ]; then
  task_name="testByDurationDebugUnitTestFresh"
else
  task_name=""
  for bucket in "${BUCKETS[@]}"; do
    task_name="$task_name :app:test${bucket}DebugUnitTest${RUN_MODE}"
  done
fi

if [ "$INSTALL_MODE" = true ]; then
  task_name="$task_name installDebug"
fi

gradle_log="${LOG_FILE}"
is_temp_log=false
if [ -z "$gradle_log" ]; then
  gradle_log=$(mktemp)
  is_temp_log=true
fi
# Empty it once here, then Gradle APPENDS. log_and_echo appends summary lines to this same file
# while Gradle runs; a truncating `>` redirect kept Gradle writing from its own offset and
# overwrote them (staggered-tests logs held only the last two summary lines). Emptying first keeps
# the UP-TO-DATE/FROM-CACHE greps scoped to this run.
: > "$gradle_log"

# Run :shared and :desktop tests in parallel with the :app buckets below — but only on
# default full runs; explicit bucket selection runs just those buckets.
# These are pure-JVM tests (no Robolectric, no ASM cache) so a separate Gradle
# invocation is safe and avoids competing for the :app build cache.
shared_desktop_log=""
shared_desktop_status=0
SHARED_DESKTOP_PID=""
if [ "$DEFAULT_RUN" = true ]; then
  shared_desktop_log=$(mktemp)
  (
    cd "$ROOT_DIR"
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 "$GRADLEW" :shared:test :desktop:test --console=plain
  ) >"$shared_desktop_log" 2>&1 &
  SHARED_DESKTOP_PID=$!
fi

overall_status=0
start_single_invocation_summary_monitor "$gradle_log"
if [ "$STREAM_OUTPUT" = true ]; then
  (
    cd "$ROOT_DIR"
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 "$GRADLEW" $task_name --console=plain
  ) | tee -a "$gradle_log" || overall_status=$?
else
  (
    cd "$ROOT_DIR"
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 "$GRADLEW" $task_name --console=plain
  ) >>"$gradle_log" 2>&1 || overall_status=$?
fi

stop_pid_tree "$SINGLE_INVOCATION_MONITOR_PID"
SINGLE_INVOCATION_MONITOR_PID=""
if [ -n "$SINGLE_INVOCATION_REPORT_POLLER_PID" ] && kill -0 "$SINGLE_INVOCATION_REPORT_POLLER_PID" 2>/dev/null; then
  # Give the poller a brief moment to pick up the final bucket's report
  # before we force-exit it, then kill if still running.
  for _ in 1 2 3; do
    if ! kill -0 "$SINGLE_INVOCATION_REPORT_POLLER_PID" 2>/dev/null; then
      break
    fi
    sleep 0.5
  done
  stop_pid_tree "$SINGLE_INVOCATION_REPORT_POLLER_PID"
  SINGLE_INVOCATION_REPORT_POLLER_PID=""
fi

# Report results from both :app buckets and :shared/:desktop tests.
total_tests=0
total_failures=0

# Wait for the parallel :shared/:desktop tests to finish and report results. Always `wait` — the
# process may have finished before this point, and gating the wait on `kill -0` used to drop a
# non-zero exit status (the test failure was visible in the XML summary but its Gradle output was
# never printed). `kill -0` now only decides whether to print the "waiting" line.
if [ -n "$SHARED_DESKTOP_PID" ]; then
  if kill -0 "$SHARED_DESKTOP_PID" 2>/dev/null; then
    log_and_echo "Waiting for :shared and :desktop tests to finish..."
  fi
  wait "$SHARED_DESKTOP_PID" 2>/dev/null || shared_desktop_status=$?
fi
if [ "$DEFAULT_RUN" = true ]; then
for module in shared desktop; do
  results_dir="$ROOT_DIR/$module/build/test-results/test"
  if [ -d "$results_dir" ] && compgen -G "$results_dir/TEST-*.xml" >/dev/null 2>&1; then
    IFS='|' read -r test_count failures errors skipped module_duration _ <<<"$(bucket_result_summary "$results_dir")"
    total_tests=$((total_tests + test_count))
    module_failures=$((failures + errors))
    total_failures=$((total_failures + module_failures))
    # Usually already printed live by the poller when the background run exited.
    if [ -z "${SINGLE_INVOCATION_REPORTED_DIR:-}" ] || [ ! -f "$SINGLE_INVOCATION_REPORTED_DIR/$module" ]; then
      emit_module_summary "$module"
    fi
  elif [ "$shared_desktop_status" -ne 0 ]; then
    log_and_echo "${RED}:${module} tests failed (exit $shared_desktop_status)${NC}"
    total_failures=$((total_failures + 1))
  fi
done
if [ "$shared_desktop_status" -ne 0 ] && [ -n "$shared_desktop_log" ]; then
  log_and_echo "${RED}Shared/desktop test log:${NC}"
  cat "$shared_desktop_log"
fi
if [ -n "$shared_desktop_log" ]; then
  rm -f "$shared_desktop_log"
fi
fi

# Report per-bucket results from JUnit XML
for bucket in "${BUCKETS[@]}"; do
  results_dir="$ROOT_DIR/app/build/test-results/test${bucket}DebugUnitTest${RUN_MODE}"
  if [ -d "$results_dir" ]; then
    IFS='|' read -r test_count failures errors skipped bucket_duration _ <<<"$(bucket_result_summary "$results_dir")"
    total_tests=$((total_tests + test_count))
    bucket_failures=$((failures + errors))
    total_failures=$((total_failures + bucket_failures))
    if [ -z "${SINGLE_INVOCATION_REPORTED_DIR:-}" ] || [ ! -f "$SINGLE_INVOCATION_REPORTED_DIR/$bucket" ]; then
      emit_bucket_summary "$bucket" "$results_dir" "$gradle_log" "test${bucket}DebugUnitTest${RUN_MODE}"
    fi
  fi
done

if [ "$INSTALL_MODE" = true ]; then
  if grep -q "> Task :app:installDebug FAILED" "$gradle_log" 2>/dev/null; then
    log_and_echo "${RED}Install debug APK: FAILED${NC}"
  elif grep -q "Installing APK" "$gradle_log" 2>/dev/null; then
    grep "Installing APK" "$gradle_log" | while IFS= read -r line; do
      log_and_echo "$line"
    done
    device_count=$(grep -c "Installing APK" "$gradle_log" 2>/dev/null || echo "?")
    log_and_echo "Installed debug APK to ${device_count} device(s)."
  else
    log_and_echo "${YELLOW}Install debug APK: no install output found${NC}"
  fi
fi

overall_elapsed=$(( $(date +%s) - OVERALL_START ))
if [ "$overall_status" -eq 0 ] && [ "$shared_desktop_status" -eq 0 ] && [ "$total_failures" -eq 0 ]; then
  log_and_echo "${total_tests} tests passed in $(format_seconds "$overall_elapsed")."
else
  if [ "$total_failures" -gt 0 ] && [ "$overall_status" -eq 0 ]; then
    overall_status=1
  fi
  if [ "$shared_desktop_status" -ne 0 ] && [ "$overall_status" -eq 0 ]; then
    overall_status=1
  fi
  log_and_echo "${total_tests} tests, ${RED}${total_failures} failed${NC} in $(format_seconds "$overall_elapsed")."
  # Show Gradle output only on failure
  cat "$gradle_log"
fi
if [ "$is_temp_log" = true ]; then
  rm -f "$gradle_log"
fi
exit "$overall_status"
