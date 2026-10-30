#!/usr/bin/env bash
#
# scripts/lib/test-lock.sh: runs in one checkout are serialized, nested scripts pass through, a full
# queue fails distinctly, a hung run is stopped with its children, and nothing the run leaves behind
# keeps the lock. Runs in a throwaway repo, so a live test run's lock is not involved.
#
# Usage: scripts/tests/test-run-lock.sh

set -u
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
git -C "$WORK" init -q
LOCK="$WORK/.git/test-run.lock"

cat > "$WORK/run.sh" <<STUB
#!/usr/bin/env bash
set -u
source "$ROOT_DIR/scripts/lib/test-lock.sh"
acquire_test_lock "$WORK/run.sh" "\$@"
case "\${1:-}" in
  record) echo "start" >> "$WORK/order"; sleep 1; echo "end" >> "$WORK/order" ;;
  nested) acquire_test_lock "$WORK/run.sh" record ;;
  hold) sleep 3 ;;
  hang) sleep 300 & echo \$! > "$WORK/child"; sleep 300 ;;
  leave) nohup sleep 20 > /dev/null 2>&1 & ;;
esac
STUB
chmod +x "$WORK/run.sh"

failures=0
fail() { echo "FAIL $1"; failures=$((failures + 1)); }
ok() { echo "ok   $1"; }

: > "$WORK/order"
"$WORK/run.sh" record 2> /dev/null &
first=$!
"$WORK/run.sh" record 2> /dev/null &
second=$!
wait "$first" "$second"
order=$(tr '\n' ' ' < "$WORK/order")
[ "$order" = "start end start end " ] && ok "concurrent runs serialize" || fail "concurrent runs overlapped: $order"

timeout 10 "$WORK/run.sh" nested 2> /dev/null
[ $? -eq 0 ] && ok "nested script does not deadlock" || fail "nested script blocked"

"$WORK/run.sh" hold 2> /dev/null &
holder=$!
sleep 1
WW_TEST_LOCK_WAIT=1 "$WORK/run.sh" 2> "$WORK/busy"
status=$?
wait "$holder"
if [ "$status" -eq 75 ] && grep -q "held by: run.sh pid" "$WORK/busy"; then
  ok "full queue exits 75 and names the holder"
else
  fail "full queue: exit $status, $(cat "$WORK/busy")"
fi

WW_TEST_TIMEOUT=2 timeout 20 "$WORK/run.sh" hang 2> "$WORK/hang"
sleep 1
if grep -q "stopped after 2s" "$WORK/hang" && ! kill -0 "$(cat "$WORK/child")" 2> /dev/null; then
  ok "hung run is stopped with its children"
else
  fail "hung run: $(cat "$WORK/hang")"
fi
flock -n "$LOCK" true && ok "lock is free after a stopped run" || fail "lock held after a stopped run"

"$WORK/run.sh" leave 2> /dev/null
flock -n "$LOCK" true && ok "process left behind does not keep the lock" || fail "lock inherited by a leftover process"

[ "$failures" -eq 0 ] && echo "PASS" || { echo "$failures case(s) failed"; exit 1; }
