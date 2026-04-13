# Restore live per-bucket unit-test summaries in staggered-tests.sh

## Context

`scripts/staggered-tests.sh` used to print per-bucket unit-test summaries (e.g. `172 medium tests passed in 18 seconds.`) as each bucket finished, while emulator tests ran in parallel. After commit `358957c` ("Update unit-tests.sh live feedback and add scheduled_tasks.lock") earlier today, those summaries no longer appear during execution — they're now batched at the very end, right before `unit-tests.sh` exits.

The commit removed the per-bucket summary emission from `start_single_invocation_summary_monitor()` in `scripts/unit-tests.sh:224`. The stated reason was a real race: the monitor was triggering on the log line `> Task :app:test{Bucket}DebugUnitTestFresh`, but Gradle re-prints that marker every time parallel-task output interleaves, so the monitor couldn't distinguish task start from task completion and could read stale (previous-run) JUnit XMLs.

The post-Gradle loop at `scripts/unit-tests.sh:304-326` still emits summaries from XML — but only after `gradlew` exits, which is exactly the batching the user is complaining about. `staggered-tests.sh:99` simply tails `unit-tests.sh`'s log file for summary lines, so it can only show what `unit-tests.sh` writes during execution.

We need to restore live summaries with an *authoritative* per-bucket completion signal that doesn't race with Gradle's log re-prints.

## Approach

Use a file-existence signal instead of a log-line signal. Gradle writes `app/build/reports/tests/<task>/index.html` only after a Test task's full lifecycle (test execution + report generation) completes. Verified locally: each bucket has its own report directory (`testShortDebugUnitTestFresh`, `testMediumDebugUnitTestFresh`, `testLongDebugUnitTestFresh`) and `index.html` exists once the task finishes.

Modify `start_single_invocation_summary_monitor()` to, in addition to its current "build finished" announcement, poll for each bucket's `index.html` modification time. Once the file exists and is newer than `OVERALL_START`, call the existing `emit_bucket_summary()` helper (already defined at `scripts/unit-tests.sh:176`) and mark the bucket as reported (touch a marker file under `SINGLE_INVOCATION_REPORTED_DIR` so the post-loop at lines 313-324 skips re-printing it).

This avoids the original race because:
- `index.html` is written exactly once per task lifecycle, at the end.
- It's not affected by Gradle log interleaving.
- A stale `index.html` from a previous run is filtered out by the mtime-vs-`OVERALL_START` check.

## Changes

### `scripts/unit-tests.sh`

In `start_single_invocation_summary_monitor()` (currently lines 224-251), add a parallel polling loop alongside the existing `tail -F` loop:

1. Keep the existing tail-based loop that announces `<bucket> bucket build finished`.
2. Add a second background subshell that, every ~1 second:
   - For each bucket not yet reported, checks whether `app/build/reports/tests/test${bucket}DebugUnitTest${RUN_MODE}/index.html` exists and has mtime ≥ `OVERALL_START`.
   - If so, calls `emit_bucket_summary "$bucket" "$results_dir"` and `touch "$SINGLE_INVOCATION_REPORTED_DIR/$bucket"`.
   - Exits once all buckets have been reported.
3. Track this second subshell's PID alongside `SINGLE_INVOCATION_MONITOR_PID` so `cleanup()` kills both.

Update the comment block at lines 228-234 to reflect the new strategy (file-existence signal, not log signal).

The post-Gradle loop at lines 304-326 already checks `SINGLE_INVOCATION_REPORTED_DIR/$bucket` markers and skips already-reported buckets, so no changes are needed there. The total-tests summary at line 330 still aggregates from XMLs.

### Critical files to modify

- `scripts/unit-tests.sh` — modify `start_single_invocation_summary_monitor()` and `cleanup()`.

### Files to leave alone

- `scripts/staggered-tests.sh` — its monitor already greps for the right summary patterns (`scripts/staggered-tests.sh:97`); no change needed once `unit-tests.sh` writes the lines live.

## Reused helpers

- `emit_bucket_summary()` (`scripts/unit-tests.sh:176`) — already does the XML read + colored output via `log_and_echo`.
- `bucket_result_summary()` / `list_failed_tests()` (`scripts/unit-tests.sh:103, 151`) — called transitively.
- `SINGLE_INVOCATION_REPORTED_DIR` (`scripts/unit-tests.sh:13, 226`) — already created and consumed by the post-loop.

## Verification

1. Run `./scripts/staggered-tests.sh` from the project root. Confirm that as each bucket finishes (Short, Medium, Long), a summary line appears live — not all three at the end.
   - Expected output interleaved with emulator output:
     - `Short bucket build finished in Ns.`
     - `<N> short tests passed in Ms.` ← this is the line that was missing
     - `Medium bucket build finished...` etc.
2. Force a unit-test failure (e.g. temporarily edit one assertion in a fast test) and re-run. Confirm the failing-bucket summary still appears live and includes the `✗ Class > Method` lines, and that `staggered-tests.sh`'s red-coloring still kicks in (it greps for `failed` / `✗` at `scripts/staggered-tests.sh:97-104`).
3. Run `./scripts/unit-tests.sh --single-invocation --fresh` directly (no staggered wrapper) and confirm summaries still appear exactly once per bucket — the marker-file dedup between the live monitor and the post-Gradle loop should prevent duplicates.
4. Cancel the run mid-execution with Ctrl+C and confirm the new polling subshell is reaped by `cleanup()` (no orphaned background processes via `ps -ef | grep unit-tests`).
