# Testing Strategy: Pure Function Extraction (No Mocking)

## Approach

This project avoids mocking frameworks (Mockito, MockK, etc.) and instead extracts testable logic into pure functions. Unit tests exercise these functions directly with plain JUnit.

## Why It Works Here

- **Single-developer widget app** — the codebase is small enough that pure function extraction keeps things simple without the overhead of maintaining a mocking setup.
- **Android widget APIs are painful to mock** — `RemoteViews`, `AppWidgetManager`, and `Context` are notoriously difficult to mock. You end up writing more mock setup than actual test logic.
- **Better code as a side effect** — extracting decisions into pure functions (e.g., `shouldLaunchHourly(hasDate, snapshotsEmpty)`) produces code that's both more testable and more readable.

## The Tradeoff

- **Only extracted logic is testable** — we can test that `resolveButtonMode()` returns the right enum, but not that `updateModeUi()` actually sets the right button text.
- **Repository/DAO interactions go untested at unit level** — instrumented tests cover some of this via `run-emulator-tests.sh`.
- **Scaling limit** — if the codebase grew significantly or added contributors, something like MockK or Turbine (for coroutine/Flow testing) would become worthwhile.

## Pattern

1. Identify decision logic embedded in Android callbacks or UI code.
2. Extract it into a `companion object` function (or top-level function) that takes primitive/enum inputs and returns a primitive/enum result.
3. Write JUnit tests against the extracted function.
4. Have the original UI code delegate to the extracted function.

### Example: ForecastHistoryActivity

**Before** (untestable — logic embedded in click listener):
```kotlin
if (date != null && !date.isBefore(LocalDate.now())) {
    launchWidgetHourlyMode(date)
}
```

**After** (testable pure function):
```kotlin
// Companion object
fun shouldLaunchHourly(hasDate: Boolean, snapshotsEmpty: Boolean): Boolean =
    hasDate && snapshotsEmpty

// Click listener delegates
if (shouldLaunchHourly(date != null, cachedSnapshots.isEmpty())) {
    launchWidgetHourlyMode(date!!)
}
```

## Bottom Line

Mocking frameworks add complexity. For a project this size, extracting pure functions gives ~80% of the testing value with ~20% of the complexity.
