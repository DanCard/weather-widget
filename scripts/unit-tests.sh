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

cleanup() {
  for pid in "${pids[@]:-}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
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

results_dir = Path(sys.argv[1])
test_count = 0
failures = 0
errors = 0
skipped = 0
total_time = 0.0

for xml_file in sorted(results_dir.glob("TEST-*.xml")):
    try:
        suite = ET.parse(xml_file).getroot()
        test_count += int(suite.attrib.get("tests", "0"))
        failures += int(suite.attrib.get("failures", "0"))
        errors += int(suite.attrib.get("errors", "0"))
        skipped += int(suite.attrib.get("skipped", "0"))
        total_time += float(suite.attrib.get("time", "0.0"))
    except (ET.ParseError, ValueError):
        continue

print(f"{test_count}|{failures}|{errors}|{skipped}|{int(total_time)}")
PY
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
      if [ "$bucket_failures" -gt 0 ]; then
        echo "${test_count} ${bucket,,} tests: ${bucket_failures} failed."
      elif [ "$skipped" -gt 0 ]; then
        echo "${test_count} ${bucket,,} tests passed (${skipped} skipped) in $(format_seconds "$bucket_duration")."
      else
        echo "${test_count} ${bucket,,} tests passed in $(format_seconds "$bucket_duration")."
      fi
    fi
  done

  overall_elapsed=$(( $(date +%s) - OVERALL_START ))
  if [ "$overall_status" -eq 0 ]; then
    echo "${total_tests} tests passed in $(format_seconds "$overall_elapsed")."
  else
    echo "${total_tests} tests, ${total_failures} failed in $(format_seconds "$overall_elapsed")."
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
done

overall_status=0
remaining=${#BUCKETS[@]}
total_tests=0

while [ "$remaining" -gt 0 ]; do
  wait -n -p finished_pid
  exit_code=$?
  remaining=$((remaining - 1))

  if [ "$exit_code" -ne 0 ]; then
    overall_status=1
  fi

  # Find which bucket this PID belongs to
  bucket=""
  for b in "${!pids[@]}"; do
    if [ "${pids[$b]}" = "$finished_pid" ]; then
      bucket="$b"
      break
    fi
  done

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
  rm -f "${logs[$bucket]}"
done

overall_elapsed=$(( $(date +%s) - OVERALL_START ))

if [ "$overall_status" -eq 0 ]; then
  echo "${total_tests} tests passed in $(format_seconds "$overall_elapsed")."
fi

exit "$overall_status"
