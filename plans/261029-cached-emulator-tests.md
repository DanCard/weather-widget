# Cached Emulator Tests

## Problem

- When testing or verifying changes, users and agents often do not know whether `emulator-tests.sh` or `staggered-tests.sh` was already run for the current state.
- `unit-tests.sh` supports caching by default (since 2026-10-03): if code inputs haven't changed, it reports "cached" in ~2-4s instead of 60+ seconds.
- `emulator-tests.sh` has no caching: every run boots an emulator, builds APKs, installs them, and runs all 158 tests via `am instrument`, taking 40-90s even if the exact same commit and tree was already verified moments earlier.
- In `staggered-tests.sh`, even if all unit tests are cached, an emulator is booted in the background and the emulator test phase executes every time.

## Solution

1. **Cache helper (`scripts/lib/emulator-cache.sh`)**:
   - Compute a source tree fingerprint using git: commit SHA (`git rev-parse HEAD`), uncommitted modifications/diffs across `app/`, `shared/`, `gradle/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, and any untracked files in `app/` and `shared/` (`git ls-files --others`).
   - Store cache metadata on clean passes in `logs/emulator-tests/.last-successful-run`:
     - source fingerprint
     - test class filter (`ALL` or specific class)
     - emulator target/AVD
     - total tests passed, duration, timestamp, test log file
   - Provide cache validation:
     - Miss if source fingerprint changed.
     - Miss if caller explicitly specified a different test class (`-c`).
     - Miss if caller explicitly requested a different AVD (`-e`).
     - Hit if source and test scope match.

2. **`scripts/emulator-tests.sh` updates**:
   - Add `--fresh` / `-f` and `--cached` flags. Default is `--cached`.
   - Add `--check-cache` (returns 0 if cache valid, 1 otherwise).
   - Before booting emulator or building APKs, check if cached.
   - If cache hits: print cached summary (e.g. `158 emulator tests: cached (up to date; passed on Generic_Foldable_API36 at 12:04).`) and exit 0 immediately (< 0.1s).
   - On full successful run (0 failures, 0 errors, all passed): write cache.
   - On failed run or incomplete device disconnect: clear cache.

3. **`scripts/staggered-tests.sh` updates**:
   - Accept `--fresh` and forward to `unit-tests.sh` and `emulator-tests.sh`. Default is cached.
   - If emulator tests are already cached:
     - Do not boot an emulator in the background.
     - Skip background APK build flag (`--also-build-apks`).
     - In the emulator phase, run `emulator-tests.sh` which prints the cached summary and exits 0 immediately.

## Verification

1. Run `scripts/emulator-tests.sh` on live emulator (or via staggered run) to establish initial cache.
2. Run `scripts/emulator-tests.sh` again: should exit in < 1s reporting cached results.
3. Run `scripts/emulator-tests.sh --fresh`: should bypass cache and execute on emulator.
4. Run `scripts/staggered-tests.sh`: should complete in ~3-4s with both unit and emulator tests reporting cached.
5. Touch a source file in `app/`: cache should miss and tests should run fresh.
