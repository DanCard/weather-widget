#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
GRADLEW="$ROOT_DIR/gradlew"
RUN_MODE=""
BUCKETS=()

for arg in "$@"; do
  case "$arg" in
    --fresh)
      RUN_MODE="Fresh"
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

cleanup() {
  for pid in "${pids[@]:-}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
}

trap cleanup INT TERM

for bucket in "${BUCKETS[@]}"; do
  case "$bucket" in
    Short|Medium|Long) ;;
    *)
      echo "Unknown bucket: $bucket" >&2
      echo "Usage: $0 [--fresh] [Short] [Medium] [Long]" >&2
      exit 2
      ;;
  esac

  log_file=$(mktemp)
  logs["$bucket"]="$log_file"
  (
    cd "$ROOT_DIR"
    "$GRADLEW" ":app:test${bucket}DebugUnitTest${RUN_MODE}" --console=plain
  ) >"$log_file" 2>&1 &
  pids["$bucket"]=$!
done

overall_status=0

for bucket in "${BUCKETS[@]}"; do
  status=0
  if ! wait "${pids[$bucket]}"; then
    status=$?
    overall_status=1
  fi

  echo "===== $bucket ====="
  cat "${logs[$bucket]}"
  echo

  rm -f "${logs[$bucket]}"

  if [ "$status" -ne 0 ]; then
    echo "$bucket bucket failed with exit code $status" >&2
  fi
done

exit "$overall_status"
