# Code Review: WidgetIntentRouter.kt (v2)

**File**: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
**Lines**: 1211
**Date**: 2026-04-12

## Issues Found

### 1. Broken indentation throughout (HIGH)
Multiple blocks have indentation at column 0 inside function bodies. Appears to be auto-merge damage.
Lines: 110-112, 125, 170-176, 330-340, 370-380, 393-394, 423-424, 448, 607-610, 627-628, 633-646, 694-719, 865-866, 876-877.

### 2. FQN references for own types (HIGH)
Many types use fully-qualified names instead of imports:
- `com.weatherwidget.data.repository.WeatherRepository?`
- `com.weatherwidget.data.local.ForecastDao`
- `com.weatherwidget.data.local.HourlyForecastDao`
- `com.weatherwidget.data.local.AppLogDao`
- `com.weatherwidget.widget.ViewMode`
- `com.weatherwidget.widget.ZoomLevel`

Makes signatures harder to read and suggests additions without updating imports.

### 3. Missing `refreshIfStale` in `handleDailyNavigation` (HIGH)
`handleDailyNavigation` (line 95) never calls `refreshIfStale`. All other handlers (graph nav, toggle API, toggle view, toggle precip, set view, cycle zoom) do. Navigating the daily view never triggers a stale data fetch, while navigating the hourly view does.

### 4. Duplicate lat/lon/fetch pattern (MEDIUM)
Identical ~6-line block repeated 8 times across handler methods:
```kotlin
val latestWeather = forecastDao.getLatestWeather()
refreshIfStale(context, latestWeather?.fetchedAt, "...")
val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
```
Extract to a data class + helper.

### 5. Hardcoded numbers instead of constants (MEDIUM)
`sourceDataMissingForCurrentWindow` (lines 423-424) uses hardcoded `30` and `14` instead of `DAILY_LOOKBACK_DAYS` and `DAILY_FORECAST_DAYS`.

### 6. `handleSetView` zoom reset should use else-if (LOW)
Lines 608-621: Two sequential `if` blocks checking `targetMode`. The second `if (targetMode.isGraphMode)` is mutually exclusive with `targetMode == DAILY`, so it should be `else if` for clarity.

### 7. `setDisableRefreshForTesting` lacks `@VisibleForTesting` (LOW)
Public `fun` without `@VisibleForTesting` annotation; could be accidentally called from production code.

### 8. WidgetStateManager singleton bypass (MEDIUM)
`WidgetStateManager(context)` is called 11+ times, always using the companion-object factory which skips Hilt injection. The `@Inject` constructor takes an optional `AppLogDao`, but the companion-object instantiation passes `null`, skipping all DB logging through WidgetStateManager.

### 9. Unused variable (LOW)
`afterLatestMs` at line 366 is declared but never used in `handleToggleView`.

## Fixes Implemented

1. Reformat broken indentation (lines at column 0 inside function bodies)
2. Replace all FQN references (`com.weatherwidget.X`) with imports
3. Add missing `refreshIfStale` call in `handleDailyNavigation`
4. Extract lat/lon resolution into `LocationResult` data class + `resolveLocation()` helper (8 call sites)
5. Replace hardcoded `30`/`14` with `DAILY_LOOKBACK_DAYS`/`SOURCE_CHECK_FORECAST_DAYS` constants
6. Change sequential `if` to `else if` in `handleSetView` zoom reset logic
7. Add `@VisibleForTesting` to `setDisableRefreshForTesting`
8. Remove unused `afterLatestMs` variable from `handleToggleView`
9. Add `SOURCE_CHECK_FORECAST_DAYS = 14L` constant (separate from `DAILY_FORECAST_DAYS = 30L` since they serve different purposes)
10. Remove unused `kotlinx.coroutines.Dispatchers` and `kotlinx.coroutines.withContext` imports
11. Replace `ViewMode.TEMPERATURE || PRECIPITATION || CLOUD_COVER` with `.isGraphMode`

## Not Fixed (deferred)

- WidgetStateManager singleton bypass via companion-object factory (architectural; requires Hilt refactoring)
- `handleResize` debounce via `kotlinx.coroutines.delay` — fragile but functional
- Dead fallback code in `updateHourlyViewWithData` line 889 (`ObservationResolver.resolveObservedCurrentTemp` when `repository` is null)
- No error handling around database operations (requires larger architectural change)