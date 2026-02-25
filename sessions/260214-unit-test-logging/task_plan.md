# Task Plan - Unit Testing Improvements

## Goal
Improve the unit testing experience by providing clear pass/fail counts and making it easy for the user to run tests themselves.

## Phases

- [x] Phase 1: Enhance Gradle Test Logging <!-- id: 0 -->
- [x] Phase 2: Create Unit Test Wrapper Script <!-- id: 1 -->
- [x] Phase 3: Verification & Documentation <!-- id: 2 -->

## Detailed Steps

### Phase 1: Enhance Gradle Test Logging
- [x] Modify `app/build.gradle.kts` to add `testLogging` block.
- [x] Support verbose logging with `FULL` exception formatting and `standardOut`.
- [x] Verify that running `./gradlew :app:test` now shows passed/failed tests.

### Phase 2: Create Unit Test Wrapper Script
- [x] Create `scripts/run-unit-tests.sh`.
- [x] Remove hardcoded `JAVA_HOME` (trust environment).
- [x] Implement parsing of test results to show a summary (Total, Passed, Failed).
- [x] Add `--force` flag for `--rerun-tasks`.
- [x] Compact output by stripping blank lines while preserving colors (`--console=rich`).
- [x] Make the script executable.

### Phase 3: Verification & Documentation
- [x] Run the new script and verify output.
- [x] Update `CLAUDE.md` to clarify `JAVA_HOME` requirements.
- [x] Provide instructions to the user.
