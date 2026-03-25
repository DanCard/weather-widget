#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
GRADLEW="$ROOT_DIR/gradlew"
RUN_MODE="Fresh"
BUCKETS=()
OVERALL_START=$(date +%s)

for arg in "$@"; do
  case "$arg" in
    --fresh)
      RUN_MODE="Fresh"
      ;;
    --cached)
      RUN_MODE=""
      ;;
    *)
      BUCKETS+=("$arg")
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

for xml_file in sorted(results_dir.glob("TEST-*.xml")):
    suite = ET.parse(xml_file).getroot()
    test_count += int(suite.attrib.get("tests", "0"))
    failures += int(suite.attrib.get("failures", "0"))
    errors += int(suite.attrib.get("errors", "0"))
    skipped += int(suite.attrib.get("skipped", "0"))

print(f"{test_count}|{failures}|{errors}|{skipped}")
PY
}

for bucket in "${BUCKETS[@]}"; do
  case "$bucket" in
    Short|Medium|Long) ;;
    *)
      echo "Unknown bucket: $bucket" >&2
      echo "Usage: $0 [--fresh|--cached] [Short] [Medium] [Long]" >&2
      exit 2
      ;;
  esac

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
    IFS='|' read -r test_count failures errors skipped <<<"$(bucket_result_summary "${results_dirs[$bucket]}")"
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
