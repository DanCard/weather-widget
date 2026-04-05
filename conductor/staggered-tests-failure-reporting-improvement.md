# Plan: Improve Test Failure Reporting in Staggered Tests

## Objective
Enhance the staggered test execution scripts to provide immediate failure details (class/method names) and use colored output (RED) for failure notifications to improve developer visibility.

## Background & Motivation
Currently, unit test failures in `scripts/staggered-tests.sh` can be easily missed because:
1.  Failure notifications are not colored.
2.  Specific details of which tests failed are not shown immediately; the developer must manually inspect the full log.
3.  The overall script might report success even if some unit test buckets failed (due to exit code handling in `unit-tests.sh`).

## Proposed Changes

### 1. `scripts/unit-tests.sh` Improvements

- **Color Support**: Define `RED`, `GREEN`, `NC` constants.
- **Enhanced Failure Detection**:
    - Add a `list_failed_tests` function (Python helper) to extract failed test names from JUnit XML files.
    - Update `emit_bucket_summary` to print failed test names in RED.
- **Robust Exit Status**:
    - Ensure `single-invocation` mode returns a non-zero exit code if `total_failures > 0`, even if the Gradle process exited with 0.
    - Accumulate failures correctly across all buckets.
- **Teed Summaries**:
    - Ensure bucket summaries are written to the log file (if provided) so that `staggered-tests.sh` monitor can pick them up consistently.

### 2. `scripts/staggered-tests.sh` Improvements

- **Colorize Monitor**:
    - Update `start_unit_summary_monitor` to colorize failure messages it finds in the log file.
- **Clearer Reporting**:
    - Ensure failure details from `unit-tests.sh` are visible in the terminal during parallel execution.

## Implementation Steps

### Phase 1: `scripts/unit-tests.sh` Enhancements

1.  Add color variables at the top of the script.
2.  Implement `list_failed_tests` Python helper.
3.  Update `emit_bucket_summary` to:
    - Use `${RED}` for "X failed" text.
    - Call `list_failed_tests` and print results in RED.
4.  Update the `single-invocation` block to:
    - Ensure it uses the teed log for summaries if possible, or manually append them.
    - Verify `total_failures` and force exit 1 if > 0.

### Phase 2: `scripts/staggered-tests.sh` Enhancements

1.  Modify `start_unit_summary_monitor` to use `sed` or similar to add color to "failed" messages.
2.  Ensure that failure details from `unit-tests.sh` are correctly displayed even when teed.

## Verification
- Mock failing unit test results (JUnit XML) and run `scripts/staggered-tests.sh`.
- Verify that failing test names are printed immediately in RED.
- Verify that "X failed" is printed in RED.
- Verify that the final exit code of `staggered-tests.sh` is non-zero when tests fail.
- Verify that `staggered-tests.sh` correctly reports "Unit tests failed" at the end.
