# Plan: Add Project-Local Logging with Universal 2-Week Retention

The goal is to provide persistent logging for test runs while ensuring the `logs/` directory remains manageable. All files in the `logs/` directory (including subdirectories) will be automatically pruned if they are older than 14 days.

## Proposed Changes

### Scripts

#### [scripts/unit-tests.sh](../../scripts/unit-tests.sh)
- **Setup Logging**: 
    - Create `logs/unit-tests/` if it doesn't exist.
    - Generate a timestamped log path: `logs/unit-tests/unit-tests-$(date +%Y%m%d-%H%M%S).log`.
- **Automatic Pruning**:
    - Before starting tests, find and delete **any** files (and empty subdirectories) in `logs/` older than 14 days using `find logs/ -mindepth 1 -mtime +14 -delete`.
- **Capture Output**:
    - Use `2>&1 | tee "$UNIT_LOG"` to capture all Gradle output (including stderr) into the log file.
    - Preserve existing output filtering for the terminal via `awk`.
- **Reporting**:
    - Print the location of the unit test log at the end of execution.

#### [scripts/emulator-tests.sh](../../scripts/emulator-tests.sh)
- **Log Location**:
    - Update `DEBUG_LOG` and `TEST_RESULTS_LOG` to use `logs/emulator-tests/` instead of `/tmp/`.
- **Cleanup**:
    - Implement the same universal 14-day pruning logic as `unit-tests.sh`.

## Verification Plan

### Manual Verification
1.  **Retention Logic**: Create dummy files with different extensions (e.g., `.log`, `.bak`, `.tmp`) in `logs/` with a modified timestamp (>14 days) and verify they are deleted when running `./scripts/unit-tests.sh`.
2.  **Log Creation**: Run `./scripts/unit-tests.sh` and verify the log is written to `logs/unit-tests/`.
3.  **Emulator Logging**: Run `./scripts/emulator-tests.sh -q` and verify logs appear in `logs/emulator-tests/`.
4.  **Exit Codes**: Verify both scripts correctly exit with non-zero codes on failure.
