# Code Review: WidgetIntentRouter.kt

**File**: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
**Lines**: 1229

## Issues Found

### 1. Inconsistent Log import (line 619)
```kotlin
android.util.Log.d("ActualsDebug", "handleSetView: target=$targetMode...")
```
Should use the imported `Log` at line 6, not the fully-qualified call.

### 2. Redundant `refreshIfStale` call in `handleGraphNavigation`
`handleGraphNavigation` (line 232) calls `refreshIfStale` before `refreshGraphView`, but `refreshGraphView` → `refreshWidget` (line 707) calls `refreshIfStale` again. Double call for graph navigation paths.

### 3. Code duplication across handler methods
Every `handle*` method follows an identical pattern:
```kotlin
val startMs = SystemClock.elapsedRealtime()
val database = WeatherDatabase.getDatabase(context)
val forecastDao = database.forecastDao()
val latestWeather = forecastDao.getLatestWeather()
refreshIfStale(context, latestWeather?.fetchedAt, "...")
val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
```
This ~10-line block is copy-pasted in 8 different handlers. Extract to a shared helper.

### 4. Potential NPE risk in `handleToggleApi`
Lines 307-316 compute `currentGraphZoom` and `currentGraphCenterTime` using `viewMode`, but then pass these to `sourceDataMissingForCurrentWindow`. Ensure callers handle the result correctly when these are null.

### 5. Testing flag thread safety
`disableRefreshForTesting` is `@Volatile` but has no synchronized access. Acceptable for testing flag but worth noting.

### 6. Missing error handling
No try-catch around database operations or repository calls. Exceptions will propagate uncaught.

### 7. Hardcoded magic numbers
- `DAILY_LOOKBACK_DAYS = 30L` duplicated in `getDailyActuals` and `sourceDataMissingForCurrentWindow`
- `SLOW_THRESHOLD_MS = 200L`
- `STALE_REFRESH_DEBOUNCE_MS = 30 * 1000L`

## Fixes to Implement

1. Fix inconsistent `android.util.Log` import on line 619 - **DONE**
2. Remove duplicate `refreshIfStale` call in `handleDailyNavigation` (not graph nav) - **DONE**
3. Extract duplicated lat/lon/refresh logic into shared helper - **NOT DONE** (complex due to varying signatures; low ROI)
4. Add error handling around database operations - **NOT DONE** (requires larger architectural change)
5. Clean up unused `startMs` variables in handler paths - **NOT DONE**