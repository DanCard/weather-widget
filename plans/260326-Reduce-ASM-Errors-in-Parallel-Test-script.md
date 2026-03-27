# Plan: Reduce ASM Errors in Parallel Test Infrastructure

## Context

`scripts/parallel-tests.sh` frequently hits ASM bytecode transformation errors. The root cause: despite a pre-build step, `test-unit-by-duration.sh` spawns **3 separate Gradle processes** (Short/Medium/Long buckets) that race on shared `app/build/intermediates` files. Combined with the emulator's Gradle process, that's 4 concurrent daemons hitting the same build directory.

A `testByDurationDebugUnitTestFresh` Gradle task already exists (build.gradle.kts:234-238) that runs all 3 buckets in a single invocation — but it's unused by the parallel script.

## Changes

### 1. `gradle.properties` — enable Gradle parallel + build cache
Add:
```
org.gradle.parallel=true
org.gradle.caching=true
```
With `parallel=true`, Gradle safely runs the 3 bucket tasks concurrently within a single daemon (proper task-level locking). Build cache improves rebuild performance.

### 2. `scripts/test-unit-by-duration.sh` — add `--single-invocation` mode
- New `--single-invocation` flag runs `testByDurationDebugUnitTestFresh` in one Gradle process instead of spawning 3
- After completion, parse JUnit XML results per bucket for reporting (test counts, pass/fail)
- Default (no flag) keeps existing 3-process behavior for standalone use

### 3. `scripts/parallel-tests.sh` — use single-invocation + stale lock cleanup
- Pass `--single-invocation` to unit test script (reduces 4 concurrent daemons → 2)
- Add stale `.lock` file cleanup in ASM cache dirs before pre-build (handles post-crash state)
- Keep pre-build step (ensures both unit + emulator ASM transforms are done before either daemon starts)

## Files to modify
- `/home/dcar/projects/weather-widget/gradle.properties`
- `/home/dcar/projects/weather-widget/scripts/test-unit-by-duration.sh`
- `/home/dcar/projects/weather-widget/scripts/parallel-tests.sh`

## Tradeoffs
- **Per-bucket wall-clock timing lost**: All 3 buckets run concurrently in one JVM, so individual wall-clock times overlap. Mitigated by reporting per-bucket test counts + total wall-clock time.
- **Memory**: 3 parallel test tasks × up to 4 forks each = 12 test runners. May need to bump `-Xmx2048m` → `-Xmx3072m` if OOMs appear.
- **Remaining 2-daemon race is low-risk**: Unit daemon touches `transformDebugUnitTestClassesWithAsm`, emulator touches `transformDebugAndroidTestClassesWithAsm` — different directories, and the pre-build ensures both are UP-TO-DATE.

## Verification
1. Run `./scripts/parallel-tests.sh` 3-5 times consecutively — should see zero ASM errors
2. Verify per-bucket test count reporting still works
3. Run `./scripts/test-unit-by-duration.sh` standalone (without `--single-invocation`) — should behave identically to before
4. Monitor for OOM errors; bump JVM heap if needed
