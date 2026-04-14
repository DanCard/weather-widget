# Code Review: WidgetIntentRouter.kt (v3)

**File**: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
**Lines**: 1220
**Date**: 2026-04-14
**Scope**: Full-file review after prior v1/v2 fixes

## Structural Issues

### 1. God Object — 6+ responsibilities in one singleton (CRITICAL)

`WidgetIntentRouter` is a 1220-line `object` doing at least six distinct jobs:

1. Intent routing & navigation validation
2. Data loading (DB queries, forecast windowing, hourly loading)
3. Business logic (stale refresh decisions, current temp resolution, navigation bounds)
4. Widget view dispatch (delegating to DailyViewHandler / TemperatureViewHandler / etc.)
5. Diagnostics & timing (slow threshold logging, resize diagnostics, staleness debug)
6. Work scheduling (forced refresh enqueue, debounce tracking)

This makes the class hard to test in isolation, hard to reason about, and hard to refactor incrementally. Each responsibility pull on different dependencies (SharedPreferences, WorkManager, Room DAOs, AppWidgetManager).

**Recommendation**: Extract into focused classes behind interfaces:
- `NavigationHandler` — nav validation + offset management
- `RefreshScheduler` — stale checks, debounce, WorkManager enqueue (injectable for tests)
- `GraphDataLoader` — `loadGraphWindowHourlyForecasts`, `loadCurrentTempResolutionHourlyForecasts`, `buildGraphQueryWindow`
- `CurrentTempResolver` — `resolveGraphStyleCurrentTemp*` methods
- Keep `WidgetIntentRouter` as thin dispatcher coordinating the above

### 2. No error handling around DB operations (HIGH)

Most handler methods make 3–5+ DAO calls with no try-catch. If any query throws (disk I/O error, migration issue, corrupted data), the entire widget refresh fails silently or crashes the BroadcastReceiver. This is especially dangerous in `goAsync()` contexts where an unhandled exception can leave `pendingResult.finish()` unreachable.

**Recommendation**: Wrap the critical path in try-catch with a fallback:
```kotlin
try {
    refreshDailyView(...)
} catch (e: Exception) {
    Log.e(TAG, "Failed to refresh daily view for widget $appWidgetId", e)
    appLogDao.log("REFRESH_ERROR", "widget=$appWidgetId error=${e.message}")
}
```

### 3. Repeated handler boilerplate (HIGH)

Nearly every `handle*` method follows the same pattern:
```
startMs → StateManager → DB → getLatestWeather → resolveLocation → refreshIfStale → refreshView → logTiming
```

This ~10-line block is copy-pasted across `handleDailyNavigation`, `handleGraphNavigation`, `handleToggleApi`, `handleToggleView`, `handleTogglePrecip`, `handleSetView`, `handleCycleZoom`, `handleResize` (8 call sites).

**Recommendation**: Extract a shared orchestration skeleton:
```kotlin
private suspend fun withRefresh(
    context: Context,
    appWidgetId: Int,
    reason: String,
    block: suspend (db: WeatherDatabase, lat: Double, lon: Double, stateManager: WidgetStateManager) -> Unit
)
```

This was deferred in v2 review as "complex due to varying signatures; low ROI" — but with 8 sites now sharing the pattern, the ROI has increased.

## Medium Issues

### 4. Redundant instantiation of StateManager and Database (MEDIUM)

`WidgetStateManager(context)` is created in nearly every method, including inside `updateHourlyViewWithData` (line 872) which is called from `refreshGraphView` where it already exists. Same for `WeatherDatabase.getDatabase(context)`. While `getDatabase` returns a singleton, `WidgetStateManager(context)` allocates a new object each time (SharedPreferences wrapper).

**Recommendation**: Pass these as parameters through the call chain, or inject them.

### 5. Test flag in production code — `isRefreshDisabledForTesting` (MEDIUM)

`@Volatile var isRefreshDisabledForTesting` is a test escape hatch embedded in production code. It's checked in `refreshIfStale` and `enqueueForcedRefresh`. This violates dependency inversion.

**Recommendation**: Extract refresh scheduling into a `RefreshScheduler` interface. Production impl uses WorkManager; test impl is a no-op. Eliminates the need for runtime flags.

### 6. Duplicated query methods (MEDIUM)

`loadGraphWindowHourlyForecasts` (line 1102) and `loadGraphWindowHourlyForecastsBySource` (line 1137) are nearly identical — they differ only in calling `getHourlyForecasts` vs `getHourlyForecastsBySource`. The merge/dedupe/sort logic at lines 1132–1134 is duplicated verbatim at lines 1170–1172.

**Recommendation**: Collapse into one method with an optional `source` parameter, or pass the DAO query as a lambda:
```kotlin
private suspend fun loadGraphWindowHourlyForecasts(
    hourlyDao: HourlyForecastDao,
    lat: Double, lon: Double,
    centerTime: LocalDateTime, zoom: ZoomLevel, now: LocalDateTime,
    source: WeatherSource? = null,
): List<HourlyForecastEntity>
```

### 7. Silent default location fallback (MEDIUM)

`resolveLocation()` (line 82) silently falls back to `DEFAULT_LAT`/`DEFAULT_LON` with no logging. A null `latestWeather` (empty DB on fresh install) should be logged, as it means the widget will show weather for an arbitrary default location without the user knowing.

**Recommendation**: Add a log line when falling back:
```kotlin
if (latestWeather == null) {
    Log.w(TAG, "resolveLocation: no weather data, falling back to default coordinates")
}
```

### 8. Inconsistent time capture across method boundaries (LOW-MEDIUM)

`refreshGraphView` captures `now = LocalDateTime.now()` at line 834, but down the call chain `updateHourlyViewWithData` → `loadCurrentTempResolutionHourlyForecasts` (line 882) allocates a *new* `LocalDateTime.now()`. Across midnight boundaries, these could differ, causing the current temp resolution window to not align with the graph window.

**Recommendation**: Thread the already-captured `now` parameter through `updateHourlyViewWithData` and its callees.

### 9. SharedPreferences for debounce — untestable without Robolectric (LOW)

`refreshIfStale` reads/writes `SharedPreferences("widget_refresh")` directly (line 543) for debounce tracking. This bypasses Hilt DI used elsewhere and requires Robolectric to test.

### 10. `handleDailyNavigation` is too long (~100 lines, lines 119–222) (LOW-MEDIUM)

It does navigation boundary validation, app log writing, data loading, and view refreshing. The boundary-check logic (lines 143–183) should be extracted to `NavigationUtils` or a standalone function for testability.

## Minor Issues

### 11. Inconsistent Log tag (line 619)
```kotlin
Log.d("ActualsDebug", "handleSetView: ...")
```
Uses hardcoded `"ActualsDebug"` instead of `TAG`. Inconsistent with all other log calls in the file.

### 12. Same-valued constants with different semantics
`DAILY_LOOKBACK_DAYS = 30L` (line 47) and `DAILY_FORECAST_DAYS = 30L` (line 48) happen to share the same value but semantically represent different ranges (history vs forecast). Consider distinct names like `HISTORY_RANGE_DAYS` / `FORECAST_RANGE_DAYS` to make intent clear, or at minimum add a comment noting the coincidence is intentional.

### 13. `distinctBy` key construction is verbose (lines 1133, 1171)
```kotlin
.distinctBy { "${it.dateTime}|${it.source}|${it.locationLat}|${it.locationLon}" }
```
String concatenation for composite keys is error-prone. Extract to `HourlyForecastEntity.key()` extension or a data-class `equals`/`hashCode` approach.

### 14. String-typed refresh reason (line 508)
`reason == "manual_refresh"` — magic string comparison. Consider a sealed class or enum for refresh reasons for type safety and exhaustiveness checking.

## Positives (carried forward from v1)

- Good use of `@VisibleForTesting` for internal APIs
- `buildRefreshScheduleDecision` is well-tested and pure (good separation)
- Comprehensive `appLogDao` logging provides audit trail
- Debounce logic in `refreshIfStale` prevents API hammering
- `LocationResult` data class extracted from v2 review reduces lat/lon boilerplate

## Delta from v2 Review

| v2 Issue | Status |
|----------|--------|
| Broken indentation | **Fixed** |
| FQN references for own types | **Fixed** (imports now used) |
| Missing `refreshIfStale` in `handleDailyNavigation` | **Fixed** (line 136) |
| Duplicate lat/lon/fetch pattern | **Fixed** → `LocationResult` + `resolveLocation()` |
| Hardcoded `30`/`14` | **Fixed** → `DAILY_LOOKBACK_DAYS` / `SOURCE_CHECK_FORECAST_DAYS` constants |
| `setDisableRefreshForTesting` lacks `@VisibleForTesting` | **Fixed** → renamed to `setIsRefreshDisabledForTesting` with annotation |
| Unused `afterLatestMs` variable | **Fixed** (removed) |
| WidgetStateManager singleton bypass | **Deferred** (architectural) |
| `ViewMode.TEMPERATURE \|\| PRECIPITATION \|\| CLOUD_COVER` | **Fixed** → `.isGraphMode` |