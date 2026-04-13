#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
GRADLEW="$ROOT_DIR/gradlew"
RUN_MODE="Fresh"
SINGLE_INVOCATION=false
STREAM_OUTPUT=false
LOG_FILE=""
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
    --single-invocation)
      SINGLE_INVOCATION=true
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
    *)
      BUCKETS+=("$1")
      shift
      ;;
  esac
done

if [ ${#BUCKETS[@]} -eq 0 ]; then
  BUCKETS=(Short Medium Long)
fi

declare -A pids
declare -A logs
declare -A starts
declare -A results_dirs
declare -A monitor_pids
SINGLE_INVOCATION_MONITOR_PID=""

cleanup() {
  for pid in "${pids[@]:-}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
  for pid in "${monitor_pids[@]:-}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
  if [ -n "${SINGLE_INVOCATION_MONITOR_PID:-}" ] && kill -0 "$SINGLE_INVOCATION_MONITOR_PID" 2>/dev/null; then
    kill "$SINGLE_INVOCATION_MONITOR_PID" 2>/dev/null || true
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

start_bucket_progress_monitor() {
  local gradle_log=$1
  local bucket=$2
  local task_name=$3
  local bucket_start=$4

  (
    local announced_execution=false

    tail -n 0 -F "$gradle_log" 2>/dev/null | while IFS= read -r line; do
      if [ "$announced_execution" = false ] && [[ "$line" == *"> Task :app:${task_name}"* ]]; then
        local build_elapsed=$(( $(date +%s) - bucket_start ))
        log_and_echo "${bucket} bucket build finished in $(format_seconds "$build_elapsed")."
        announced_execution=true
      fi

      if [ "$announced_execution" = true ]; then
        break
      fi
    done
  ) &
  monitor_pids["$bucket"]=$!
}

start_single_invocation_summary_monitor() {
  local gradle_log=$1
  SINGLE_INVOCATION_REPORTED_DIR=$(mktemp -d)

  # Live feedback only: announce when each bucket's task starts executing.
  # Per-bucket PASS/FAIL summaries are intentionally NOT emitted here — Gradle
  # re-prints "> Task :app:testXDebugUnitTestFresh" every time test output
  # interleaves, so we can't distinguish task start from task completion on a
  # successful run. Reading XMLs at the start marker races with the actual
  # test execution and can report stale (previous-run) results. The post-loop
  # below reads XMLs after Gradle exits, which is authoritative.
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
}

for bucket in "${BUCKETS[@]}"; do
  case "$bucket" in
    Short|Medium|Long) ;;
    *)
      echo "Unknown bucket: $bucket" >&2
      echo "Usage: $0 [--fresh|--cached] [--single-invocation] [Short] [Medium] [Long]" >&2
      exit 2
      ;;
  esac
done

# Single-invocation mode: run all buckets in one Gradle process (avoids ASM races).
# Gradle's own parallel executor handles concurrent test tasks safely.
if [ "$SINGLE_INVOCATION" = true ]; then
  if [ ${#BUCKETS[@]} -eq 3 ] && [ "$RUN_MODE" = "Fresh" ]; then
    task_name="testByDurationDebugUnitTestFresh"
  else
    # Build individual task names for the requested buckets
    task_name=""
    for bucket in "${BUCKETS[@]}"; do
      task_name="$task_name :app:test${bucket}DebugUnitTest${RUN_MODE}"
    done
  fi

  gradle_log="${LOG_FILE}"
  is_temp_log=false
  if [ -z "$gradle_log" ]; then
    gradle_log=$(mktemp)
    is_temp_log=true
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
    SINGLE_INVOCATION_MONITOR_PID=""
  fi

  # Report per-bucket results from JUnit XML
  total_tests=0
  total_failures=0
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

  overall_elapsed=$(( $(date +%s) - OVERALL_START ))
  if [ "$overall_status" -eq 0 ] && [ "$total_failures" -eq 0 ]; then
    log_and_echo "${total_tests} tests passed in $(format_seconds "$overall_elapsed")."
  else
    if [ "$total_failures" -gt 0 ] && [ "$overall_status" -eq 0 ]; then
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
fi

# Multi-process mode (default): spawn a separate Gradle process per bucket.
for bucket in "${BUCKETS[@]}"; do
  log_file=$(mktemp)
  task_name="test${bucket}DebugUnitTest${RUN_MODE}"
  results_dir="$ROOT_DIR/app/build/test-results/${task_name}"

  logs["$bucket"]="$log_file"
  results_dirs["$bucket"]="$results_dir"
  starts["$bucket"]=$(date +%s)

  (
    cd "$ROOT_DIR"
    "$GRADLEW" ":app:${task_name}" --console=plain
  ) >"$log_file" 2>&1 &
  pids["$bucket"]=$!
  start_bucket_progress_monitor "$log_file" "$bucket" "$task_name" "${starts[$bucket]}"
done

overall_status=0
remaining=${#BUCKETS[@]}
total_tests=0
declare -A completed

while [ "$remaining" -gt 0 ]; do
  for bucket in "${BUCKETS[@]}"; do
    if [ -n "${completed[$bucket]:-}" ]; then
      continue
    fi

    if kill -0 "${pids[$bucket]}" 2>/dev/null; then
      continue
    fi

    exit_code=0
    if ! wait "${pids[$bucket]}"; then
      exit_code=$?
    fi

    completed["$bucket"]=1
    remaining=$((remaining - 1))

    if [ "$exit_code" -ne 0 ]; then
      overall_status=1
    fi

    if [ "$exit_code" -eq 0 ]; then
      IFS='|' read -r test_count failures errors skipped bucket_duration <<<"$(bucket_result_summary "${results_dirs[$bucket]}")"
      total_tests=$((total_tests + test_count))
      elapsed=$(( $(date +%s) - ${starts[$bucket]} ))
      if [ "$skipped" -gt 0 ]; then
        echo "${test_count} ${bucket,,} tests passed (${skipped} skipped) in $(format_seconds "$elapsed")."
      else
        echo "${test_count} ${bucket,,} tests passed in $(format_seconds "$elapsed")."
      fi
    else
      echo "===== $bucket ====="
      cat "${logs[$bucket]}"
      echo
      echo "$bucket bucket failed with exit code $exit_code" >&2
    fi

    if [ -n "${monitor_pids[$bucket]:-}" ] && kill -0 "${monitor_pids[$bucket]}" 2>/dev/null; then
      kill "${monitor_pids[$bucket]}" 2>/dev/null || true
    fi
    rm -f "${logs[$bucket]}"
  done

  if [ "$remaining" -gt 0 ]; then
    sleep 0.2
  fi
done

overall_elapsed=$(( $(date +%s) - OVERALL_START ))

if [ "$overall_status" -eq 0 ]; then
  log_and_echo "${total_tests} tests passed in $(format_seconds "$overall_elapsed")."
else
  log_and_echo "One or more unit test buckets failed."
fi

exit "$overall_status"
