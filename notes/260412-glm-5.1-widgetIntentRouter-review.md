# Code Review: WidgetIntentRouter.kt

## Overview
Large file (1228 lines) that routes widget intent actions and handles navigation, view toggling, and data refresh. Generally well-structured with good logging and test coverage.

---

## Issues

### 1. Missing import for `computeSmoothedForecasts` (line 798)
```kotlin
val smoothedForecasts = computeSmoothedForecasts(hourlyForecasts, displaySource)
```
This function is defined in `TemperatureHourDataBuilder.kt` but there's no import. The call works only because Kotlin allows same-package calls without imports, but this function is in `handlers/` package. **This may be a compilation error.**

### 2. Inconsistent view mode checking pattern
Repeated pattern checking for graph-capable view modes:
```kotlin
// Lines 84-86, 335-337, 378-380, 622-624, 648-651
viewMode == ViewMode.TEMPERATURE || viewMode == ViewMode.PRECIPITATION || viewMode == ViewMode.CLOUD_COVER
```
**Recommendation:** Extract to extension property:
```kotlin
val ViewMode.isGraphMode: Boolean
    get() = this in listOf(ViewMode.TEMPERATURE, ViewMode.PRECIPITATION, ViewMode.CLOUD_COVER)
```

### 3. Unused variable (line 643)
```kotlin
val afterLatestMs = SystemClock.elapsedRealtime()
```
Declared but never used in `handleSetView()`.

### 4. Hardcoded delay in `handleResize` (line 674)
```kotlin
kotlinx.coroutines.delay(250) // Debounce rapid resize events
```
Magic number. Should be a named constant at file level.

### 5. Duplicate `android.util.Log.d` call (lines 630, 634)
```kotlin
android.util.Log.d(TAG, "handleSetView: RESET zoom to WIDE...")
android.util.Log.d(TAG, "handleSetView: set hourlyOffset=$targetOffset")
```
Uses fully qualified `android.util.Log.d` instead of the `Log` import used elsewhere.

### 6. Potential race condition with `disableRefreshForTesting`
The `@Volatile` annotation on `disableRefreshForTesting` (line 43) provides visibility but not atomicity for read-modify-write operations. For single-threaded test usage this is fine, but the pattern is fragile.

### 7. Large function: `refreshDailyView` (lines 742-823)
81 lines doing too much: database queries, current temp resolution, smoothing, widget update. Consider extracting helper methods.

### 8. Inconsistent null handling for `latestWeather`
Pattern repeated throughout:
```kotlin
val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
```
This is good defensive coding, but the fallback coordinates could lead to confusing UX if data is missing. Consider logging when fallbacks are used.

---

## Minor/Suggestions

- **Lines 116-117**: `DAILY_LOOKBACK_DAYS` and `DAILY_FORECAST_DAYS` constants exist but are shadowed by local hardcoded values in some places (e.g., line 405 uses `30` directly).
- **Line 505**: `reason == "manual_refresh"` string comparison — consider using a sealed class or enum for refresh reasons for type safety.
- **Lines 1140-1142, 1178-1180**: The `distinctBy` key construction is verbose and could be extracted to `HourlyForecastEntity.key()` extension.

---

## Positives

- Good use of `@VisibleForTesting` for internal APIs
- Comprehensive logging with `appLogDao` for audit trail
- Debounce logic in `refreshIfStale` prevents API hammering
- `buildRefreshScheduleDecision` is well-tested and pure
