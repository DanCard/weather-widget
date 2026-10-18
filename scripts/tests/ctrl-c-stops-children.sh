#!/usr/bin/env bash
#
# Ctrl-C on unit-tests.sh / staggered-tests.sh must stop every Gradle run they started.
# Background jobs (`&`) start with SIGINT ignored, so they used to outlive the script.
# plans/261018-staggered-tests-ctrl-c-leaves-unit-tests-running.md
#
# A stub gradlew records its pid and sleeps (`:shared:jar` succeeds at once, so the parallel
# :shared/:desktop run starts). Each script runs in its own process group, which gets SIGINT the
# way a terminal delivers Ctrl-C; then no stub may survive.
#
# Usage: scripts/tests/ctrl-c-stops-children.sh

set -u
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/gradlew" <<'STUB'
#!/usr/bin/env bash
echo $$ >> "$STUB_PIDS"
case " $* " in *" :shared:jar "*) exit 0 ;; esac
exec sleep 300
STUB
chmod +x "$WORK/gradlew"

failures=0

# run_case NAME EXPECTED_STUBS COMMAND...
run_case() {
  local name=$1 expected=$2
  shift 2
  local pids_file="$WORK/$name.pids"
  : > "$pids_file"
  # Started as a terminal starts a foreground job: its own process group, SIGINT at its default.
  # (This launcher's `&` would otherwise hand the script SIGINT already ignored — the bug itself.)
  STUB_PIDS="$pids_file" GRADLEW="$WORK/gradlew" python3 -c '
import os, signal, sys
signal.signal(signal.SIGINT, signal.SIG_DFL)
signal.signal(signal.SIGQUIT, signal.SIG_DFL)
if os.getpgrp() != os.getpid():  # already a group leader when the caller has job control
    os.setsid()
os.execvp(sys.argv[1], sys.argv[1:])
' "$@" > "$WORK/$name.out" 2>&1 &
  local leader=$!

  local waited=0 running
  while :; do
    running=$(while read -r p; do kill -0 "$p" 2>/dev/null && echo "$p"; done < "$pids_file" | wc -l)
    [ "$running" -ge "$expected" ] && break
    if [ "$waited" -ge 60 ]; then
      echo "FAIL $name: only $running of $expected stub Gradle runs started"; cat "$WORK/$name.out"
      failures=$((failures + 1)); kill -KILL -- "-$leader" 2>/dev/null; return
    fi
    sleep 0.5; waited=$((waited + 1))
  done

  kill -INT -- "-$leader"
  wait "$leader"
  local status=$?

  local survivors="" p
  for _ in $(seq 10); do
    survivors=""
    while read -r p; do kill -0 "$p" 2>/dev/null && survivors+=" $p"; done < "$pids_file"
    [ -z "$survivors" ] && break
    sleep 0.5
  done
  if [ -n "$survivors" ]; then
    echo "FAIL $name: stub Gradle still running after Ctrl-C:$survivors"
    ps -o pid,ppid,stat,cmd -p ${survivors// /,} 2>/dev/null
    kill -KILL $survivors 2>/dev/null
    failures=$((failures + 1))
  elif [ "$status" -ne 130 ]; then
    echo "FAIL $name: exit $status, expected 130"; tail -5 "$WORK/$name.out"
    failures=$((failures + 1))
  else
    echo "ok   $name: $expected Gradle runs stopped, exit 130"
  fi
}

run_case unit-tests 2 "$ROOT_DIR/scripts/unit-tests.sh"
run_case staggered-tests 2 "$ROOT_DIR/scripts/staggered-tests.sh" --sequential

[ "$failures" -eq 0 ] && echo "PASS" || { echo "$failures case(s) failed"; exit 1; }
