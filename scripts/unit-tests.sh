#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
GRADLEW="$ROOT_DIR/gradlew"
RUN_MODE="Fresh"
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

cleanup() {
  if [ -n "${SINGLE_INVOCATION_MONITOR_PID:-}" ] && kill -0 "$SINGLE_INVOCATION_MONITOR_PID" 2>/dev/null; then
    kill "$SINGLE_INVOCATION_MONITOR_PID" 2>/dev/null || true
    # tail -F orphan: the subshell kill above may leave the tail process running,
    # especially when the script exits early. Kill all children of the subshell.
    pgrep -P "$SINGLE_INVOCATION_MONITOR_PID" 2>/dev/null | xargs -r kill 2>/dev/null || true
  fi
  if [ -n "${SINGLE_INVOCATION_REPORT_POLLER_PID:-}" ] && kill -0 "$SINGLE_INVOCATION_REPORT_POLLER_PID" 2>/dev/null; then
    kill "$SINGLE_INVOCATION_REPORT_POLLER_PID" 2>/dev/null || true
  fi
  if [ -n "${SINGLE_INVOCATION_REPORTED_DIR:-}" ] && [ -d "$SINGLE_INVOCATION_REPORTED_DIR" ]; then
    rm -rf "$SINGLE_INVOCATION_REPORTED_DIR"
  fi
}

trap cleanup EXIT

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

print(f"{test_count}|{failures}|{errors}|{skipped}|{wall_duration}")
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
    except:
        continue
PY
}

emit_bucket_summary() {
  local bucket=$1
  local results_dir=$2
  if [ ! -d "$results_dir" ]; then
    return 1
  fi
  if ! compgen -G "$results_dir/TEST-*.xml" >/dev/null; then
    return 1
  fi

  IFS='|' read -r test_count failures errors skipped bucket_duration <<<"$(bucket_result_summary "$results_dir")"
  bucket_failures=$((failures + errors))
  if [ "$bucket_failures" -gt 0 ]; then
    log_and_echo "${test_count} ${bucket,,} tests: ${RED}${bucket_failures} failed.${NC}"
    list_failed_tests "$results_dir" | while IFS= read -r line; do
      log_and_echo "${RED}${line}${NC}"
    done
  elif [ "$skipped" -gt 0 ]; then
    log_and_echo "${test_count} ${bucket,,} tests passed (${skipped} skipped) in $(format_seconds "$bucket_duration")."
  else
    log_and_echo "${test_count} ${bucket,,} tests passed in $(format_seconds "$bucket_duration").  "
  fi
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
    while [ "${#pending[@]}" -gt 0 ]; do
      local remaining=()
      for bucket in "${pending[@]:-}"; do
        [ -z "$bucket" ] && continue
        local task_name="test${bucket}DebugUnitTest${RUN_MODE}"
        local report_html="$ROOT_DIR/app/build/reports/tests/${task_name}/index.html"
        local results_dir="$ROOT_DIR/app/build/test-results/${task_name}"
        if [ -f "$report_html" ]; then
          local mtime
          mtime=$(stat -c %Y "$report_html" 2>/dev/null || echo 0)
          if [ "$mtime" -ge "$OVERALL_START" ]; then
            if emit_bucket_summary "$bucket" "$results_dir"; then
              touch "$SINGLE_INVOCATION_REPORTED_DIR/$bucket"
              continue
            fi
          fi
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
  ) | tee "$gradle_log" || overall_status=$?
else
  (
    cd "$ROOT_DIR"
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 "$GRADLEW" $task_name --console=plain
  ) >"$gradle_log" 2>&1 || overall_status=$?
fi

if [ -n "$SINGLE_INVOCATION_MONITOR_PID" ] && kill -0 "$SINGLE_INVOCATION_MONITOR_PID" 2>/dev/null; then
  kill "$SINGLE_INVOCATION_MONITOR_PID" 2>/dev/null || true
  pgrep -P "$SINGLE_INVOCATION_MONITOR_PID" 2>/dev/null | xargs -r kill 2>/dev/null || true
  SINGLE_INVOCATION_MONITOR_PID=""
fi
if [ -n "$SINGLE_INVOCATION_REPORT_POLLER_PID" ] && kill -0 "$SINGLE_INVOCATION_REPORT_POLLER_PID" 2>/dev/null; then
  # Give the poller a brief moment to pick up the final bucket's report
  # before we force-exit it, then kill if still running.
  for _ in 1 2 3; do
    if ! kill -0 "$SINGLE_INVOCATION_REPORT_POLLER_PID" 2>/dev/null; then
      break
    fi
    sleep 0.5
  done
  if kill -0 "$SINGLE_INVOCATION_REPORT_POLLER_PID" 2>/dev/null; then
    kill "$SINGLE_INVOCATION_REPORT_POLLER_PID" 2>/dev/null || true
  fi
  SINGLE_INVOCATION_REPORT_POLLER_PID=""
fi

# Report results from both :app buckets and :shared/:desktop tests.
total_tests=0
total_failures=0

# Wait for the parallel :shared/:desktop tests to finish and report results.
if [ -n "$SHARED_DESKTOP_PID" ] && kill -0 "$SHARED_DESKTOP_PID" 2>/dev/null; then
  log_and_echo "Waiting for :shared and :desktop tests to finish..."
  wait "$SHARED_DESKTOP_PID" || shared_desktop_status=$?
fi
if [ "$DEFAULT_RUN" = true ]; then
for module in shared desktop; do
  results_dir="$ROOT_DIR/$module/build/test-results/test"
  if [ -d "$results_dir" ] && compgen -G "$results_dir/TEST-*.xml" >/dev/null 2>&1; then
    IFS='|' read -r test_count failures errors skipped module_duration <<<"$(bucket_result_summary "$results_dir")"
    total_tests=$((total_tests + test_count))
    module_failures=$((failures + errors))
    total_failures=$((total_failures + module_failures))
    if [ "$module_failures" -gt 0 ]; then
      log_and_echo "${test_count} ${module} tests: ${RED}${module_failures} failed.${NC}"
      list_failed_tests "$results_dir" | while IFS= read -r line; do
        log_and_echo "${RED}${line}${NC}"
      done
    elif [ "$skipped" -gt 0 ]; then
      log_and_echo "${test_count} ${module} tests passed (${skipped} skipped) in $(format_seconds "${module_duration:-0}")."
    else
      log_and_echo "${test_count} ${module} tests passed in $(format_seconds "${module_duration:-0}")."
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
    IFS='|' read -r test_count failures errors skipped bucket_duration <<<"$(bucket_result_summary "$results_dir")"
    total_tests=$((total_tests + test_count))
    bucket_failures=$((failures + errors))
    total_failures=$((total_failures + bucket_failures))
    if [ -z "${SINGLE_INVOCATION_REPORTED_DIR:-}" ] || [ ! -f "$SINGLE_INVOCATION_REPORTED_DIR/$bucket" ]; then
      if [ "$bucket_failures" -gt 0 ]; then
        log_and_echo "${test_count} ${bucket,,} tests: ${RED}${bucket_failures} failed.${NC}"
        list_failed_tests "$results_dir" | while IFS= read -r line; do
          log_and_echo "${RED}${line}${NC}"
        done
      elif [ "$skipped" -gt 0 ]; then
        log_and_echo "${test_count} ${bucket,,} tests passed (${skipped} skipped) in $(format_seconds "$bucket_duration")."
      else
        log_and_echo "${test_count} ${bucket,,} tests passed in $(format_seconds "$bucket_duration")."
      fi
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
