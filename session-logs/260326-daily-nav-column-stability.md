# Daily Navigation Column Count Stability

**Branch:** main (started on `daily-nav-column-stability-option`, stashed and moved to main)

## Problem

In the daily forecast graph view on the emulator, tapping the left (back) navigation arrow changed the visible column count from 8 to 9. The column count should remain constant regardless of navigation direction.

### Root Cause

The widget has space for 9 columns (`numColumns = 9` from widget width ~600dp). `NavigationUtils.getDayOffsets(9)` returns 9 date offsets `[-1, 0, 1, 2, 3, 4, 5, 6, 7]`. However, data availability varies by navigation position:

- **At offset 0 (home):** The farthest future day (today+7) typically has no forecast data (NWS/Open-Meteo provide ~7 days). That day gets skipped in `DailyViewLogic.prepareGraphDays()` -> `days.size = 8`.
- **At offset -1 (back):** The window shifts so the farthest future day is today+6 (has data), and past dates have observations -> `days.size = 9`.

Since the renderer and click handlers used `days.size` for layout width, the bars got narrower and an extra column appeared when navigating backward.

## First Attempt (Reverted)

Changed `columnIndex = days.size` to `columnIndex = index` in `DailyViewLogic.kt:350`, and changed DailyViewHandler to pass `numColumns` (instead of `days.size`) to the renderer and click handlers. This kept all 9 grid slots visible with bars at their true grid positions.

**Problem:** Created an empty 9th column when only 8 days had data. User reported the widget "seemed broken" with a blank last column.

**Lesson:** The fix preserved grid positions correctly but the empty trailing column was worse than the original instability. The user wanted either filled columns or fewer columns -- not empty ones.

## Final Fix (Implemented on main)

**Approach:** Store the baseline column count at offset 0 and cap subsequent renders to that baseline.

### Changes

#### 1. `WidgetStateManager.kt` -- new per-widget state
- Added `KEY_DAILY_COLUMN_COUNT_PREFIX` constant
- Added `getDailyColumnCount(widgetId)` / `setDailyColumnCount(widgetId, count)` methods
- Added key to `clearWidgetState()` so widget resize resets it

#### 2. `DailyViewHandler.kt` -- capping logic (lines ~370-380)
After `prepareGraphDays()` returns:
```kotlin
val displayDays = if (dateOffset == 0) {
    stateManager.setDailyColumnCount(appWidgetId, days.size)
    days
} else {
    val baseline = stateManager.getDailyColumnCount(appWidgetId)
    if (baseline > 0 && days.size > baseline) days.take(baseline) else days
}
```
- At offset 0: stores `days.size` (e.g., 8) as baseline
- At other offsets: caps to baseline via `days.take(baseline)`
- All downstream code (`renderGraph`, `setupGraphDayClickHandlers`, logging) uses `displayDays` instead of `days`

**Design choice:** `days.take(baseline)` trims from the END (drops farthest future day). When navigating backward, the user gains a past day but loses the farthest forecast -- reasonable since far-out forecasts are lowest value.

### Tests Added

#### Unit test (Robolectric): `DailyViewGraphClickAlignmentTest.kt`
- `daily graph column count stays stable when navigating backward gains extra data`
  - Renders at offset 0 with 3 days of data (baseline = 3)
  - Renders at offset -1 with 9 days of data
  - Asserts visible zone count is identical across both renders

#### Instrumented test: `DailyGraphTouchZoneAlignmentInstrumentedTest.kt`
- `columnCount_staysStable_whenNavigatingChangesPopulatedDayCount`
  - Tests at the `setupGraphDayClickHandlers` level (avoids mockk native agent issue on emulator)
  - Two renders with different day counts but same capped `numColumns`
  - Asserts visible zone count matches and equals the baseline

**Note:** Full-pipeline instrumented tests can't use mockk on the emulator due to missing `libmockkjvmtiagent.so`. The Robolectric test covers the full `updateWidget` path; the instrumented test validates zone layout on real Android.

## Parallel Test Script Fix

### Problem
`scripts/emulator-tests.sh` had two ASM retry paths that ran `gradlew clean` on transient `transformDebugClassesWithAsm` errors. When running in parallel with unit tests via `scripts/parallel-tests.sh`, the `clean` task deleted `app/build/` while the unit test Gradle was writing to it, causing both builds to fail.

### Fix -- New Contract

#### `emulator-tests.sh`
- Added `--no-retry` flag (long option via `getopts -:`)
- When `--no-retry` is set: ASM errors produce **exit code 2** immediately (no cleanup, no retry)
- When run standalone (no flag): handles retries itself with targeted ASM cache deletion (not `gradlew clean`)
- Both ASM retry sites (early build at ~line 381 and main test run at ~line 748) respect the flag

#### `parallel-tests.sh`
- Passes `--no-retry` to emulator script
- On exit code 2: clears ASM caches (targeted `rm -rf` of 4 directories), waits for unit tests to finish, retries emulator script WITHOUT `--no-retry` (so the retry can self-handle if needed)
- ASM cache directories extracted to `ASM_CACHE_DIRS` array and `clear_asm_cache()` helper

**Targeted ASM cache directories:**
```
app/build/intermediates/classes/debug/transformDebugClassesWithAsm
app/build/intermediates/classes/debugAndroidTest/transformDebugClassesWithAsm
app/build/intermediates/incremental/transformDebugClassesWithAsm
app/build/intermediates/incremental/transformDebugAndroidTestClassesWithAsm
```

### Design Rationale
- Subordinates don't clean -- they report the error and let the orchestrator decide
- The parallel script has full visibility into both processes and can coordinate safely
- Standalone usage retains self-healing behavior (no flag = retry internally)
- The retry runs without `--no-retry`, so if it hits ASM again, the emulator script handles it (no parallel build to race against)

## Test Results

All tests pass:
- **532** unit tests (Short: 286, Medium: 138, Long: 108)
- **159** instrumented tests on emulator
- Parallel execution completes in ~50s

## Files Modified

| File | Change |
|------|--------|
| `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt` | Added `dailyColumnCount` storage + cleanup |
| `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt` | Baseline capping logic, `days` -> `displayDays` |
| `app/src/test/java/.../DailyViewGraphClickAlignmentTest.kt` | Added navigation stability unit test |
| `app/src/androidTest/java/.../DailyGraphTouchZoneAlignmentInstrumentedTest.kt` | Added column stability instrumented test, removed mockk dependency |
| `scripts/emulator-tests.sh` | Added `--no-retry` flag, exit 2 on ASM errors when set |
| `scripts/parallel-tests.sh` | Handles exit 2 with targeted ASM cleanup + retry |
