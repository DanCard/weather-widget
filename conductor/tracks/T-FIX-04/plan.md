# Plan: Create Staggered Test Execution Script to Avoid ASM Errors

The current `parallel-tests.sh` script suffers from frequent ASM (Abstract Service Method / Instrumentation) errors because it launches multiple independent `gradlew` processes concurrently. Each process attempts to perform ASM transformations on the same class files in the `build/` directory, leading to race conditions and artifact corruption.

This plan introduces a **new script** `scripts/staggered-tests.sh` that implements a staggered execution strategy:
1.  **Boot-Only Emulator**: Launch the emulator early without starting any Gradle tasks.
2.  **Unit Test Build**: Start the unit tests (using a single Gradle process).
3.  **Delayed Emulator Test Build**: Start the emulator tests only after the unit test suite has transitioned from the "build" phase to the "execution" phase.

This ensures that only one Gradle process is performing heavy transformations at any given time, while still allowing the tests themselves (host vs. emulator) to run in parallel.

## Changes

### 1. Scripts

#### `scripts/emulator-tests.sh`
- Add a `-b` (boot-only) flag.
- When `-b` is passed:
    - Find or launch the specified emulator.
    - Wait for it to be ready (including the 10s system stability sleep).
    - Exit successfully without running any tests or invoking Gradle.

#### `scripts/staggered-tests.sh` (NEW)
- **Log Setup**: Create a new log directory `logs/staggered-tests`.
- **Cleanup**: Clear the ASM cache directories and lock files.
- **Boot Phase**: Start `scripts/emulator-tests.sh -b` in the background immediately.
- **Unit Phase**: Start `scripts/unit-tests.sh --single-invocation` in the background.
- **Stagger Phase**: 
    - Monitor the unit test log file.
    - Wait for a line starting with `> Task :app:test` (which indicates Gradle has finished the build/transform phase and is starting the actual test execution).
    - If the unit test process exits before this signal is found, proceed to the next phase (as the build part is done regardless).
- **Emulator Phase**: Once the unit test build is done, start the main `scripts/emulator-tests.sh` test run in the background.
- **Wait**: Wait for all background processes (boot-only script, unit tests, emulator tests) to complete.
- **Result Parsing**: Implement robust exit code logic and failure reporting similar to `parallel-tests.sh`.

## Verification Plan

### Automated Verification
1.  **Boot Only**: Run `./scripts/emulator-tests.sh -b` and verify it exits after the emulator is ready.
2.  **Staggered Run**: Run the new `./scripts/staggered-tests.sh`.
    - Verify that unit tests and emulator boot start together.
    - Verify that emulator tests only start after unit tests have finished building.
    - Verify that no ASM errors occur.
    - Verify that a failure in one suite (e.g., a unit test) does not prevent the other suite (e.g., instrumented tests) from running.

### Manual Verification
- Intentional Failure: Temporarily break a unit test and an instrumented test. Verify that `staggered-tests.sh` reports both failures correctly and exits with a non-zero code.
- Multi-Emulator: If multiple emulators are connected, verify that `connectedDebugAndroidTest` still targets them correctly.
