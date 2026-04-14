# Plan: Streamline unit-tests.sh and Remove Multi-Process Mode

## Context
`scripts/unit-tests.sh` currently has two execution modes:
1.  **Multi-Process Mode (default):** Spawns a separate Gradle process for each test bucket (`Short`, `Medium`, `Long`).
2.  **Single-Invocation Mode (`--single-invocation`):** Runs all specified test buckets concurrently within a single Gradle daemon.

### The Problem
The legacy multi-process mode is demonstrably slower due to Gradle daemon overhead, resource contention, and cache locking. It is also the root cause of frequent `ASM` bytecode transformation errors when run alongside other builds (which necessitated the `--single-invocation` workaround for `staggered-tests.sh`). Furthermore, maintaining two completely separate execution and reporting paths led to subtle bugs, such as exit codes being swallowed in multi-process mode.

Since Gradle already correctly parallelizes independent test tasks when `org.gradle.parallel=true` is set (which it is), the single-invocation mode is both faster and safer. 

## Goals
1.  Remove the legacy multi-process logic from `scripts/unit-tests.sh`.
2.  Make the single-invocation approach the *only* way the script runs.
3.  Remove the `--single-invocation` flag from `scripts/unit-tests.sh` and any calling scripts (e.g., `scripts/staggered-tests.sh`).
4.  Retain the live, per-bucket reporting and XML parsing features that currently exist in the single-invocation path.

## Implementation Steps

### 1. Simplify `scripts/unit-tests.sh`
*   Remove the `--single-invocation` flag parsing.
*   Remove the `pids`, `logs`, `starts`, and `results_dirs` associative arrays used exclusively by the multi-process polling loop.
*   Remove the entire multi-process `for` loop that spawns background Gradle processes.
*   Remove the multi-process polling loop (`while [ "$remaining" -gt 0 ]; do ...`).
*   Elevate the single-invocation block (`if [ "$SINGLE_INVOCATION" = true ]; then ...`) to be the primary execution flow for the script.
*   Ensure the `start_single_invocation_summary_monitor` is always called before the main Gradle execution.

### 2. Update `scripts/staggered-tests.sh`
*   Remove the `--single-invocation` flag from the invocation of `unit-tests.sh`.

### 3. Verification
*   Run `./scripts/unit-tests.sh` and verify it executes all three buckets in a single Gradle run, reporting results correctly.
*   Run `./scripts/staggered-tests.sh` and verify it successfully runs both unit and emulator tests concurrently.
