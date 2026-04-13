# Session Log: Fix Current Temp Changes on Graph Scroll

**Date:** April 13, 2026
**Objective:** Fix the temperature graph view so the current temperature label (top-left corner) stays constant when the user scrolls left/right through history/forecast days.

## User Prompts & Critical Decisions

### 1. Bug Report
> **User:** Emulator: temperature view graph: Current temp near top left, changes as I scroll left and right. Should not change with scrolling.

Diagnosis: `WidgetIntentRouter.updateHourlyViewWithData()` already loads two distinct hourly windows:
1. `hourlyForecasts` — graph-window centered on the user's scrolled `centerTime`
2. `currentTempHourlyForecasts` — NOW-centered, loaded specifically for current-temp resolution

The NOW-centered list is used correctly for `graphStyleObs` (the observation-based current temp). But the graph-window list is then passed into `TemperatureViewHandler.updateWidget()`, which forwards it to `TemperatureStateResolver`, which passes it to `CurrentTemperatureResolver.resolve()`. When scrolled to yesterday or +2 days, the graph-window list doesn't contain the current hour, so the resolver returns null or extrapolates from the wrong end of the window.

### 2. Test Strategy Discussion
> **User:** Should we consider an automated test plan for this. These types of issues occur frequently.

Proposed two tiers:
1. **Pure-function invariance test** on `CurrentTemperatureResolver` — guards that the resolver itself never takes `centerTime` as input
2. **`TemperatureStateResolver` wiring test** — the real bug is one layer up: `TemperatureStateResolver` passes the wrong list to the resolver

> **User:** yes (add tests to plan)

Added to plan. Discovered a critical finding during planning: an existing test `TemperatureViewHandlerCenterTimeTest` is named `"updateWidget uses now for header temp while keeping centerTime day label"` and already intends to guard this exact bug — but it passes today despite the bug. Reason: it uses a **super-set fixture** (both NOW-hour points and center-time points merged into one list). The resolver finds NOW inside that combined list. In production, `WidgetIntentRouter` passes a graph-window-restricted list that genuinely excludes the current hour. The existing test is a false-safety assertion.

> **User:** Is there a Robolectric / integration test in the plan? Should there be?

Confirmed Robolectric is already heavily used (60+ `*RoboTest.kt` files in the project, `TestDatabase` helper, `WidgetIntentRouterRobolectricTest.kt`). Added a required Robolectric DB-level regression test that:
- Seeds the DB with two DISJOINT hourly clusters (NOW=66°, scrolled-day=52°)
- Performs the same DB queries `refreshGraphView` would perform for each scroll offset
- Asserts the rendered `current_temp` is invariant and never shows the wrong cluster

> **User:** Copy plan to plans/ dir and implement

Plan copied to `plans/260413-fix-current-temp-scrolling.md`, then implemented.

## Root Cause (Detailed)

`WidgetIntentRouter.kt` (~line 856–949): `updateHourlyViewWithData()` correctly loads two separate hourly lists but routes only the graph-window list to the handler:

```kotlin
// ✅ Loaded correctly at line 875-881:
val currentTempHourlyForecasts = loadCurrentTempResolutionHourlyForecasts(...)

// ✅ Used correctly for graphStyleObs (observation-based temp):
val graphStyleObs = resolveGraphStyleCurrentTemp(hourlyForecasts = currentTempHourlyForecasts, ...)

// ❌ But the graph-window list is passed to the handler:
TemperatureViewHandler.updateWidget(hourlyForecasts = hourlyForecasts, ...)
//                                                   ^^^^ graph-window, not NOW-centered
```

Inside `TemperatureStateResolver.resolve()` (~line 153), `CurrentTemperatureResolver.resolve(hourlyForecasts = hourlyForecasts)` is called with the graph-window list. When scrolled to ±2 days, this list contains no current-hour entries and the resolver falls back to an extrapolated or null value.

The same issue affected `smoothedForecasts` (the blending map used by the resolver) which was also computed from the graph-window data, so even if the resolver was given the right list, the smoothing context was wrong.

A secondary call site existed in `scheduleCurrentTempRefinement` (`TemperatureViewHandler.kt:202`) — the async defer path — which also used the graph-window `hourlyForecasts`.

## Technical Implementation

### Fix: Thread `currentTempHourlyForecasts` through the call chain

All changes are plumbing only — no new logic, no new DB queries.

#### 1. `TemperatureStateResolver.kt`
- Added `currentTempHourlyForecasts: List<HourlyForecastEntity>` parameter to `resolve()`
- Added `val currentTempSmoothedForecasts = computeSmoothedForecasts(currentTempHourlyForecasts, displaySource)` after the existing `smoothedForecasts` line
- Changed `CurrentTemperatureResolver.resolveQuick()` call: `hourlyForecasts = currentTempHourlyForecasts`, `smoothedForecasts = currentTempSmoothedForecasts`
- Changed `CurrentTemperatureResolver.resolve()` call: same substitution
- Changed `getCurrentHourForecast(...)` call to use `currentTempHourlyForecasts` (header weather icon also reflects NOW, not the scrolled day)

#### 2. `TemperatureViewHandler.kt`
- Added `currentTempHourlyForecasts: List<HourlyForecastEntity>` parameter to `updateWidget()`
- Forwarded to `TemperatureStateResolver.resolve(currentTempHourlyForecasts = ...)`
- Renamed `hourlyForecasts` → `currentTempHourlyForecasts` in `CurrentTempRefinementParams` data class
- Fixed `scheduleCurrentTempRefinement()` to use `params.currentTempHourlyForecasts` (was `params.hourlyForecasts`)

#### 3. `WidgetIntentRouter.kt`
- Passed `currentTempHourlyForecasts = currentTempHourlyForecasts` to `TemperatureViewHandler.updateWidget()` (already loaded at line 875-881)
- Made `refreshGraphView()` `@VisibleForTesting internal` (needed for Robolectric test access)

#### 4. `WidgetRenderer.kt`
- Hoisted the NOW-window filter outside the `if (repository != null)` block:
  ```kotlin
  val nowResolutionWindow = WidgetIntentRouter.buildCurrentTempResolutionWindow(now)
  val nowCenteredHourlyForecasts = hourlyForecasts.filter { row ->
      row.locationLat == locationLat && row.locationLon == locationLon &&
          row.dateTime in nowMinEpoch..nowMaxEpoch
  }
  ```
- Passed `currentTempHourlyForecasts = nowCenteredHourlyForecasts` to `TemperatureViewHandler.updateWidget()`
- Reused the same window variables for `graphStyleObs` computation (removing duplicate filter logic)

**Note:** `PrecipViewHandler` and `CloudCoverViewHandler` were not modified. Those handlers receive `lastObservedTemp: Float?` directly from `observation?.temperature`, which is already correctly resolved using `currentTempHourlyForecasts` in `updateHourlyViewWithData`. They don't call `CurrentTemperatureResolver` internally.

### Tests

#### Updated: `TemperatureViewHandlerCenterTimeTest.kt`
The existing test was a false-safety assertion (super-set fixture). Split into two separate lists:

```kotlin
// Before (bug passes): one combined list with both NOW and center-time data
val hourly = listOf(
    hourly(nowHour, 66f),      // NOW
    hourly(centerTime, 52f),   // center-time (+2d)
)
// TemperatureViewHandler.updateWidget(hourlyForecasts = hourly, ...)

// After (real regression guard): disjoint lists
val graphWindowHourly = listOf(hourly(centerTime, 52f), ...)   // scrolled-day only
val nowCenteredHourly = listOf(hourly(nowHour, 66f), ...)      // NOW only
// TemperatureViewHandler.updateWidget(
//     hourlyForecasts = graphWindowHourly,
//     currentTempHourlyForecasts = nowCenteredHourly,
// )
```

If a future refactor accidentally collapses the two lists back into one, this test fails (52° ≠ 66°).

#### New: `WidgetIntentRouterHeaderTempRoboTest.kt`
Router-level DB regression test. Seeds an in-memory Room DB with two genuinely disjoint clusters, performs the same DB queries that `refreshGraphView` would perform (using the actual `HOURLY_LOOKBACK_HOURS`/`HOURLY_LOOKAHEAD_HOURS` constants and `buildCurrentTempResolutionWindow`), then calls `TemperatureViewHandler.updateWidget` with the resulting lists. Asserts the `current_temp` TextView value is:
- Identical across all five offset positions: `{-48h, -24h, 0, +24h, +48h}`
- Not blank
- Never shows "52" (the scrolled-day cluster's value)

This test verifies the full data-separation contract: the DB queries return truly different lists when scrolled ±2 days, and the handler correctly keeps them separate.

#### Updated: 7 other test files
All other tests that call `TemperatureViewHandler.updateWidget()` or `TemperatureStateResolver.resolve()` directly with `centerTime = now` were updated to pass `currentTempHourlyForecasts = <same hourly list>` (correct because their `centerTime == now` makes graph-window and NOW-window identical):
- `TemperatureTouchRoutingRoboTest.kt`
- `WeatherObservationsShortcutTest.kt`
- `HistoryIconVisibilityRoboTest.kt`
- `TemperatureFetchDotUpdateRoboTest.kt` (3 call sites)
- `PrecipProbabilityTouchRoutingRoboTest.kt`
- `CurrentTempTouchRoutingRoboTest.kt`
- `TemperatureDeltaVisibilityRoboTest.kt` (calls `TemperatureStateResolver.resolve()` directly)

## Files Modified

**Production:**
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
- `app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt`

**Tests:**
- `app/src/test/java/com/weatherwidget/widget/handlers/WidgetIntentRouterHeaderTempRoboTest.kt` *(new)*
- `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureViewHandlerCenterTimeTest.kt` *(fixture split)*
- `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureTouchRoutingRoboTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/WeatherObservationsShortcutTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/HistoryIconVisibilityRoboTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureFetchDotUpdateRoboTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/PrecipProbabilityTouchRoutingRoboTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/CurrentTempTouchRoutingRoboTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureDeltaVisibilityRoboTest.kt`

**Plans:**
- `plans/260413-fix-current-temp-scrolling.md` *(copied from .claude/plans/)*

## Verification

- All unit tests PASSED (full `testDebugUnitTest` suite)
- `compileDebugKotlin` clean (only pre-existing `ForecastEvolutionRenderer.kt` warning)
- `compileDebugUnitTestKotlin` clean
- App installed on emulator + 2 physical devices via `./gradlew installDebug`

**Manual test steps:**
1. Switch to temperature graph (hourly) view
2. Note the current temp in the top-left corner
3. Tap left/right arrows to navigate to yesterday, -2d, +1d, +2d
4. Expected: current temp value is constant at all positions; only graph contents and day-label in top-right change
5. Repeat for precipitation and cloud-cover views (same `updateHourlyViewWithData` call path)
6. Navigate back to today and confirm the NOW-line still appears in the graph

## Key Insight: The False-Safety Test Pattern

The recurring pattern this bug illustrates: when two data sources are needed (graph-rendering data vs. current-state data), a test that passes a merged super-set fixture gives false confidence. The resolver finds the correct value in the super-set and passes — but in production, only one subset is passed, and it happens to be the wrong one. The correct test uses **disjoint fixtures** so that routing the wrong list causes an observable failure (wrong value or null), not a silent pass.

## Plan File
`plans/260413-fix-current-temp-scrolling.md`
