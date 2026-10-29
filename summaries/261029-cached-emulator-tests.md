# Cached emulator tests — summary (2026-10-29)

Plan: [plans/261029-cached-emulator-tests.md](../plans/261029-cached-emulator-tests.md)

## User prompts

1. "Sometimes I don't know if an ai ran staggered-tests or emulator-tests. What do you think of making the emulator tests work like unit-tests and if they have already been run than just report cached? results?"
2. "Will it work if different emulators are stopped / started ?"
3. "implement"
4. "write above summary of changes to summaries/"
5. "I started another emulator. emulator tests should only run on new emulator not old. Do you want to verify?"

## Problem

- When testing or verifying changes, users and agents often did not know whether `emulator-tests.sh` or `staggered-tests.sh` was already run for the current state.
- `unit-tests.sh` supports caching by default (since 2026-10-03): if code inputs haven't changed, it reports "cached" in ~2–4 s instead of 60+ seconds.
- `emulator-tests.sh` had no caching: every run booted an emulator, assembled APKs, installed them, and ran all tests via `am instrument`, taking 40–90 s even if the exact same commit and tree was already verified.
- In `staggered-tests.sh`, even if all unit tests were cached, an emulator was booted in the background and the emulator test phase executed every time.

## Summary of Changes

1. **Cache Engine (`scripts/lib/emulator-cache.sh`)**:
   - Computes a sub-10ms source tree fingerprint based on `git rev-parse HEAD`, tracked modifications/diffs across `app/`, `shared/`, `gradle/`, and build configuration files, plus untracked files in `app/` and `shared/` (`git ls-files --others`).
   - Records metadata on 100% clean test passes in `logs/emulator-tests/.last-successful-run` (source fingerprint, test class scope, AVD/device target, pass/skip/failure counts, duration, and test log path).
   - Validates cache hits against source changes, test scope (`ALL` vs specific `-c Class`), and target AVDs (if explicitly requested via `-e`).
   - Clears cache on test failures or device disconnects.

2. **Emulator Test Runner (`scripts/emulator-tests.sh`)**:
   - Added `--cached` (default), `--fresh` / `-f`, and `--check-cache` flags.
   - When cached, checks inputs **before booting any emulator** or calling Gradle, reporting cached results in **< 0.1 s** (e.g. `109 emulator tests: cached (2 skipped) (up to date; passed on Generic_Foldable_API36 at 12:22).`).
   - Writes cache entries upon successful completion in both single-emulator and multi-emulator modes.

3. **Staggered Orchestrator (`scripts/staggered-tests.sh`)**:
   - Added `--fresh` and `--cached` options (default is cached).
   - Checks if emulator tests are cached before launching: if cached, skips background emulator boot and APK builds, and reports cached emulator tests as soon as unit tests finish.
   - Forwards `--fresh` to both `unit-tests.sh` and `emulator-tests.sh` when requested.

4. **Documentation**:
   - Added plan in `plans/261029-cached-emulator-tests.md`.
   - Updated `arch/testing-strategy.md` documenting `--fresh` and `--cached` across test scripts.

## Verification

- **Cold run**: Executed full suite on `Generic_Foldable_API36` (109 tests, 107 passed, 2 skipped).
- **Cache hit**: Subsequent `./scripts/emulator-tests.sh` finished in **0.045 s** reporting cached results.
- **Cache invalidation**: Verified cache misses on test scope change (`-c`), on source modification (`touch app/build.gradle.kts`), and bypass with `--fresh`.
- **Multi-emulator selective execution**: Started second emulator `Medium_Phone_API_36` (`emulator-5556`) alongside `Generic_Foldable_API36` (`emulator-5554`). `./scripts/emulator-tests.sh` detected `Generic_Foldable_API36` was already verified, skipped it, and ran only on `emulator-5556`. Once passed, both are cached and subsequent runs report `verified on emulator-5554 (Generic_Foldable_API36) emulator-5556 (Medium_Phone_API_36)` in **0.08 s**.
- **End-to-end staggered run**: `./scripts/staggered-tests.sh` verified all 6,358 unit tests and 109 emulator tests in **4 seconds**.
- **Process management**: Verified `scripts/tests/ctrl-c-stops-children.sh` passed.
