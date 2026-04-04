# Plan: Add Persistent Project-Local Logging for Unit Tests

The goal is to ensure that full logs from unit test runs are persisted to a project-local `logs/` directory. This facilitates debugging across different environments and avoids cluttering system `/tmp/` or losing logs on reboot.

## Proposed Changes

### Scripts

#### [scripts/unit-tests.sh](../../scripts/unit-tests.sh)
- Define a `LOG_DIR` as `logs/unit-tests` in the project root.
- Create the `LOG_DIR` if it doesn't exist.
- Define a `UNIT_LOG` path within this directory using a timestamp: `unit-tests-$(date +%Y%m%d-%H%M%S).log`.
- Use `tee` to save the **complete, unfiltered** `gradlew` output to this log file while still piping the filtered output to the terminal via `awk`.
- Ensure `EXIT_CODE` is correctly captured from the `gradlew` command using `${PIPESTATUS[0]}`.
- Print the location of the unit test log at the end of the script.

#### [scripts/emulator-tests.sh](../../scripts/emulator-tests.sh)
- Update logging paths to use `logs/emulator-tests/` instead of `/tmp/` for consistency.
- Ensure the directory exists before starting the tests.

## Verification Plan

### Manual Verification
- Run `./scripts/unit-tests.sh` and verify that a log file is created in `logs/unit-tests/` and its path is printed.
- Run `./scripts/emulator-tests.sh -q` and verify that logs are now created in `logs/emulator-tests/`.
- Run `./scripts/parallel-tests.sh` and verify that the combined output shows the new log locations.
- Verify that both scripts return the correct exit codes on failure.
