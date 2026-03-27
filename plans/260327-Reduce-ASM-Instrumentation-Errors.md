# Plan: Reduce ASM Instrumentation Errors

## Context

The `parallel-tests.sh` script runs unit tests and emulator (instrumented) tests in parallel. Intermittently, the emulator test build fails with an ASM error (`transformDebugClassesWithAsm`). The current retry logic handles it (clear cache + rebuild), but costs ~40s of wall time. The root cause is stale/corrupted incremental build cache for AGP's bytecode transformation tasks — typically from prior interrupted builds or parallel Gradle process contention.

## Recommended Approach

Three complementary changes, ordered by expected impact:

### 1. Pre-clear ASM cache before the pre-build step (high impact)

Currently, stale `.lock` files are cleaned (line 162-165), but the actual stale transformation outputs are not. If a previous build was interrupted, the incremental cache can be corrupted. Clear the ASM cache **before** the pre-build, not just on retry.

**File:** `scripts/parallel-tests.sh`
- Move `clear_asm_cache` call to just before the pre-build Gradle invocation (before line 169)
- Keep the existing retry logic as a safety net

### 2. Add `--no-build-cache` to the pre-build for ASM tasks (medium impact)

The Gradle build cache (`org.gradle.caching=true`) can serve stale ASM artifacts across builds. Adding `--no-build-cache` to the pre-build step forces fresh transformation. This is safe because the pre-build only runs two lightweight tasks.

**File:** `scripts/parallel-tests.sh` line 171-174
- Add `--no-build-cache` flag to the pre-build Gradle command

### 3. Disable incremental transforms via Gradle property (medium impact)

AGP's ASM transforms support incremental processing, which is the main source of staleness bugs. Disabling it forces a full transform each time (adds ~1-2s but eliminates the error class entirely).

**File:** `gradle.properties`
- Add: `android.enableIncrementalTransforms=false`
- **Trade-off:** Slightly slower clean builds (~1-2s), but eliminates the entire category of ASM cache corruption errors

## Verification

1. Run `scripts/parallel-tests.sh` multiple times in succession
2. Intentionally corrupt state: run a build, kill it mid-way (`kill -9`), then run tests — should not trigger ASM retry
3. Check that total test time doesn't regress significantly (expect <2s increase from non-incremental transforms)

## Files to Modify

- `scripts/parallel-tests.sh` — changes #1 and #2
- `gradle.properties` — change #3
