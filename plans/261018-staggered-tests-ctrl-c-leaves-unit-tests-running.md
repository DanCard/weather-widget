# Ctrl-C on staggered-tests.sh leaves the unit tests running

## Report (user, 2026-10-18)
`scripts/staggered-tests.sh`: Ctrl-C returned to the prompt, but the unit tests kept running.

## Root cause
A non-interactive bash starts every `cmd &` with **SIGINT and SIGQUIT ignored** (POSIX: no job
control ⇒ background jobs must not see the terminal's interrupt). An ignored signal is inherited
across `exec`, and a non-interactive shell cannot trap a signal that was ignored on entry.

Verified here: a `sleep 300 &` from a script shows `SigIgn: 0000000000000006` (bits 2 and 3) and
survives `kill -INT`.

- `staggered-tests.sh` runs `"$UNIT_SCRIPT" … &` (line 287) and, during the Long bucket, the emulator
  phase as `( … ) &` (line 337). It has **no trap**. Ctrl-C kills the staggered script (it is in the
  foreground `wait`), and the unit script — plus its `gradlew` clients — ignore the same SIGINT and
  carry on, now orphaned.
- `unit-tests.sh` has the same hole on its own: the parallel `:shared:test :desktop:test` Gradle run
  is `( … ) &` (line 439), so Ctrl-C on a plain `unit-tests.sh` stops the `:app` run but leaves that one
  going. Its `trap cleanup EXIT` stops only the monitor and poller, not `SHARED_DESKTOP_PID`.
- `emulator-tests.sh` already has `trap cleanup EXIT INT TERM`; out of scope unless testing shows a gap.

The Gradle daemon is not the problem: killing a `gradlew` client cancels its build in the daemon
(the daemon cancels when the client disconnects). The client just never received a signal it acted on.

## Fix
1. `staggered-tests.sh`: `trap on_interrupt INT TERM`. It lists the script's whole descendant tree
   **before** signalling (same race `stop_pid_tree` documents: orphans are reparented the moment a
   parent dies), sends **SIGTERM** (children ignore SIGINT, not TERM), prints
   "Interrupted — stopped unit/emulator tests", and exits 130.
2. `unit-tests.sh`: `trap … INT TERM` that stops `SHARED_DESKTOP_PID`'s tree along with the monitor and
   poller, then exits 130. TERM can be trapped even when the script was started with INT ignored,
   which is how (1) reaches it.
3. A small shared helper, `scripts/lib/proc-tree.sh` (`descendant_pids`, `stop_tree`), sourced by
   both, so the recursive enumeration is written once.

## Tests
- `scripts/tests/ctrl-c-stops-children.sh`: runs each script under a stub `gradlew` (a long `sleep`
  that writes its PID; `GRADLEW` override), sends SIGINT to the script's process group as a terminal
  would, and asserts no stub PID survives within 5 s. Covers staggered → unit → shared/desktop.
- By hand: `scripts/staggered-tests.sh`, Ctrl-C during the Short bucket, then
  `pgrep -af 'GradleWrapperMain|unit-tests.sh'` is empty and `./gradlew --status` shows the daemon IDLE.
