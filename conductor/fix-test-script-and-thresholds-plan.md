# Plan: Fix unit-tests.sh and update test thresholds

## Problem 1: `scripts/unit-tests.sh` reports success on failure
`scripts/staggered-tests.sh` calls `scripts/unit-tests.sh --single-invocation`, which runs in a single process and accurately reports test failures. However, when `scripts/unit-tests.sh` runs without that flag (in its default multi-process mode), it masks test failures. 

The bug is in the polling loop:
```bash
    exit_code=0
    if ! wait "${pids[$bucket]}"; then
      exit_code=$?
    fi
```
Due to the `!` operator, if `wait` fails, the condition evaluates to true (exit status 0). The `$?` captured inside the `then` block will be `0` (the exit status of `!`), so `exit_code` incorrectly becomes 0.

### Solution 1
Modify `scripts/unit-tests.sh` to correctly capture the exit code without using `!`:
```bash
    exit_code=0
    wait "${pids[$bucket]}" || exit_code=$?
```

## Problem 2: Failing tests in `DailyViewLogicTest.kt`
The new logic in `getMinimumPrecipProbability` calculates thresholds differently:
```kotlin
return (7.0 / 3.0 * daysFromToday + 16).toInt().coerceIn(0, 33)
```
- Day 1 threshold: `18`
- Day 2 threshold: `20`

Two tests in `DailyViewLogicTest.kt` are failing because they expect labels to be suppressed using the old thresholds:
1. `rainy future day just below threshold returns null label` (tests Day 1): uses `19`, which is > `18` (no longer suppressed). We need to change this to `17`.
2. `rain label suppressed for near term day with 30 percent probability` (tests Day 2): uses `30`, which is > `20` (no longer suppressed). We need to change this to `19`.

### Solution 2
Update the assertions in `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt` to match the new thresholds.

## Verification
- Run `./scripts/unit-tests.sh` and ensure it accurately reports success and fails if tests fail.
- Run `./gradlew testDebugUnitTest` and ensure all tests pass.
