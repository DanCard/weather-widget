# WeatherWidgetWorker Review

## Summary

`WeatherWidgetWorker.kt` is 1,059 lines — the sole `@HiltWorker` in the project, acting as a "kitchen sink" that handles all background work modes (full sync, current-temp-only, non-primary, observation backfill) plus data loading, widget painting, location handoff, GPS resampling, and loop lifecycle management. It has structural issues in cohesion, duplication, and method sprawl that make it harder to maintain and reason about than necessary.

Below are actionable findings, ordered by impact.

---

## 1. Duplicate Data Loading Pipeline (HIGH)

### Problem
Both `doWork()` (lines 189–217) and `refreshWidgetsFromCache()` (lines 920–971) repeat an identical sequence:
1. Load `weatherList` from repository
2. Fetch `forecastSnapshots`
3. Fetch `hourlyForecasts` (with source list from `hourlySourceIds`)
4. Resolve `activeSourceList` from `currentDisplaySourceIds`
5. Fetch `dailyActuals`
6. Load `currentTemps`
7. Call `updateAllWidgets(...)`

This is ~80 lines of duplication. If the loading order or a source-filtering rule changes, both copies must be updated in lockstep.

### Recommendation
Extract a **data bundle class** and a single loading method:

```kotlin
internal data class WidgetDataBundle(
    val weatherList: List<ForecastEntity>,
    val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
    val hourlyForecasts: List<HourlyForecastEntity>,
    val dailyActuals: DailyActualsBySource,
    val currentTemps: List<com.weatherwidget.data.local.ObservationEntity>,
    val activeSourceIds: List<String>,
)

// In a new file, e.g. WidgetDataBundleLoader.kt:
internal suspend fun loadWidgetDataBundle(
    weatherRepository: WeatherRepository,
    widgetStateManager: WidgetStateManager,
    hourlyForecastLoader: HourlyForecastLoader,
    db: WeatherDatabase,
    location: Pair<Double, Double>,
    networkAllowed: Boolean,
    recomputeActuals: Boolean,
): WidgetDataBundle { ... }
```

Then `doWork()` and `refreshWidgetsFromCache()` both call this single method.

**Lines saved**: ~80  
**Cohesion gain**: Single source of truth for the "what data does the widget need" question.

---

## 2. InputData Parsing Sprawl (HIGH)

### Problem
Lines 55–71 extract 17+ individual keys from `inputData` with repeated `getBoolean`/`getString`/`getDouble`/`getInt` calls. This:
- Pollutes `doWork()` with 17 lines of mechanical parsing
- Makes it hard to see what parameters a work mode actually needs
- Spreads the default values across `DEFAULT_LAT`, `DEFAULT_LON`, `DEFAULT_OBSERVATION_BACKFILL_HOURS`, etc.

### Recommendation
Extract a **`WorkInput` data class** with a companion factory:

```kotlin
internal data class WorkInput(
    val uiOnlyRefresh: Boolean,
    val forceRefresh: Boolean,
    val candidateLocationRefresh: Boolean,
    val currentTempOnly: Boolean,
    val nonPrimaryCurrentTempOnly: Boolean,
    val opportunisticCurrentTemp: Boolean,
    val currentTempReason: String,
    val targetSourceId: String?,
    val observationBackfillMode: Boolean,
    val backfillLat: Double,
    val backfillLon: Double,
    val backfillHours: Long,
    val backfillReason: String,
    val noHourlyWidgetId: Int,
    val noHourlyDate: String?,
    val noHourlyLat: Double,
    val noHourlyLon: Double,
    val shouldBroadcastNoHourlyComplete: Boolean,
) {
    companion object {
        fun from(data: Data): WorkInput = WorkInput(
            uiOnlyRefresh = data.getBoolean(KEY_UI_ONLY_REFRESH, false),
            // ... each key once
        )
    }
}
```

The keys remain in the `Companion` for callers, but `doWork()` collapses from 17 lines to 1: `val input = WorkInput.from(inputData)`.

**Lines saved**: ~15 from doWork  
**Cohesion gain**: All input schema knowledge in one type.

---

## 3. Duplicate Exception Handling Pattern (HIGH)

### Problem
Four separate handlers repeat the same `CancellationException` → log + rethrow, `Exception` → log + retry (or failure) pattern:

| Method | Lines | Retry/Failure |
|---|---|---|
| `doWork()` | 375–381 | `Result.retry()` |
| `handleCurrentTempOnlyWork()` | 779–789 | `Result.retry()` |
| `handleNonPrimaryCurrentTempOnlyWork()` | 859–868 | `Result.retry()` |
| `handleObservationBackfillWork()` | 910–917 | `Result.failure()` |

Additionally, `handleCurrentTempOnlyWork` and `handleNonPrimaryCurrentTempOnlyWork` both call their respective lifecycle manager (`manageCurrentTempLoopAfterRun` / `manageNonPrimaryLoopAfterRun`) in the `Exception` catch block and at the end of the `try` block.

### Recommendation
Extract a generic exception handler:

```kotlin
// In a new file, e.g. WorkerExceptionHandler.kt
internal inline fun <T> handleWorkerExceptions(
    appLogDao: AppLogDao,
    cancellationTag: String,
    cancellationMessage: String,
    errorTag: String,
    errorMessage: String,
    onException: (Exception) -> Result,
    block: () -> Result,
): Result = try {
    block()
} catch (e: CancellationException) {
    appLogDao.log(cancellationTag, "${cancellationMessage} stopReason=${e.message}", "INFO")
    throw e
} catch (e: Exception) {
    appLogDao.logException(errorTag, errorMessage, e)
    onException(e)
}
```

Each handler then becomes:

```kotlin
private suspend fun handleCurrentTempOnlyWork(...): Result =
    handleWorkerExceptions(
        appLogDao = appLogDao,
        cancellationTag = "CURR_FETCH_CANCELLED",
        cancellationMessage = "CurrentTemp fetch cancelled.",
        errorTag = "CURR_FETCH_EXCEPTION",
        errorMessage = "CurrentTemp fetch failed (reason=$reason, duration=${durationMs}ms)",
        onException = { e ->
            manageCurrentTempLoopAfterRun(isPlugged, isScreenInteractive, ignoreRunningWorkId = id)
            Result.retry()
        },
    ) { /* actual work */ }
```

**Lines saved**: ~30  
**Cohesion gain**: One place to change error handling policy (e.g., adding a crash reporter).

---

## 4. `fetchHourlyForecasts` Is 75 Lines of Stitching Logic (MEDIUM)

### Problem
`fetchHourlyForecasts()` (lines 530–606) queries two DAOs, finds the closest location pair, filters by `sameSite`, maps history rows to current entities, and stitches/dedups. This is pure data access logic independent of the worker lifecycle.

### Recommendation
Extract into `HourlyForecastLoader` class:

```kotlin
// widget/HourlyForecastLoader.kt
internal class HourlyForecastLoader(
    private val db: WeatherDatabase,
    private val widgetStateManager: WidgetStateManager,
) {
    suspend fun load(
        lat: Double, lon: Double,
        sources: List<String>,
    ): List<HourlyForecastEntity> { ... }
}
```

The `hourlySourceIds()` method moves with it. Inject into the worker's constructor.

**Lines moved**: ~80  
**Cohesion gain**: Hourly data access has a single home.

---

## 5. Location Candidate Logic Inline in `doWork()` (MEDIUM)

### Problem
Lines 221–272 contain a 52-line block of:
- Candidate superseded check (compare current vs loaded)
- Data usability evaluation
- Promotion decision

This interrupts the main `doWork()` flow with a complex nested conditional that has its own early-return paths.

### Recommendation
Extract into a top-level function in `LocationHandoffPolicy.kt` (which already owns `evaluateCandidateUsability`):

```kotlin
internal suspend fun tryPromoteLocationCandidate(
    context: Context,
    appLogDao: AppLogDao,
    widgetStateManager: WidgetStateManager,
    candidateAtLoad: LocationHandoffStore.Candidate,
    weatherList: List<ForecastEntity>,
    hourlyForecasts: List<HourlyForecastEntity>,
    activeSourceIds: Collection<String>,
    appWidgetIds: IntArray,
): LocationCandidateOutcome { ... }

internal sealed class LocationCandidateOutcome {
    data object CandidateSuperseded : LocationCandidateOutcome()
    data object WaitingForData : LocationCandidateOutcome()
    data object Promoted : LocationCandidateOutcome()
}
```

`doWork()` then calls this and returns `Result.success()` for non-Promoted outcomes.

**Lines moved**: ~45 from doWork  
**Cohesion gain**: Location handoff logic is co-located in `LocationHandoffPolicy.kt`.

---

## 6. Method Ordering (MEDIUM)

### Problem
Methods are in no obvious order: `logStage` (399), `fetchForecastSnapshots` (448), `hourlySourceIds` (492), `fetchDailyActuals` (503), `fetchHourlyForecasts` (530), `updateAllWidgets` (608), `handleCurrentTempOnlyWork` (676), `handleNonPrimaryCurrentTempOnlyWork` (792), `manageNonPrimaryLoopAfterRun` (871), `handleObservationBackfillWork` (890), `refreshWidgetsFromCache` (920), `manageCurrentTempLoopAfterRun` (973).

### Recommendation
Reorganize in this order:
1. **data objects**: `Companion` (keys, constants)
2. **orchestration**: `doWork()`
3. **work-mode handlers**: `handleCurrentTempOnlyWork`, `handleNonPrimaryCurrentTempOnlyWork`, `handleObservationBackfillWork`
4. **data fetching**: `fetchForecastSnapshots`, `fetchHourlyForecasts`, `fetchDailyActuals`, `hourlySourceIds`, `currentDisplaySourceIds`
5. **widget painting**: `updateAllWidgets`, `refreshWidgetsFromCache`
6. **lifecycle helpers**: `manageCurrentTempLoopAfterRun`, `manageNonPrimaryLoopAfterRun`, `broadcastNoHourlyRefreshComplete`
7. **diagnostics**: `logStage`, `maybeScheduleDebugFastRefresh`, `isScreenInteractive`, `getLocationName`

---

## 7. `updateAllWidgets` Source-Race Logic Is Complex Inline (LOW)

### Problem
The source-race correction (lines 629–649) inside `updateAllWidgets` calculates `uncoveredSources`, logs, conditionally reloads actuals, then iterates widgets. This mixes "correct the data" with "paint the widgets."

### Recommendation
Pull the source-race correction into a separate method:

```kotlin
private suspend fun resolveEffectiveActuals(
    paintSourceIds: List<String>,
    loadedActualsSourceIds: Collection<String>,
    reloadActuals: (suspend (List<String>) -> DailyActualsBySource)?,
    dailyActuals: DailyActualsBySource,
): DailyActualsBySource { ... }
```

Then `updateAllWidgets` becomes:
1. Resolve effective actuals
2. Iterate widgets and paint

---

## 8. `WorkContext` for Battery/Screen State (LOW)

### Problem
`isPlugged: Boolean`, `batteryLevel: Int`, `isScreenInteractive: Boolean` are passed through multiple methods as individual parameters (e.g., `handleCurrentTempOnlyWork` takes 7 params, 3 of which are this trio).

### Recommendation
Bundle into a simple data class:

```kotlin
internal data class DeviceContext(
    val isCharging: Boolean,
    val batteryLevel: Int,
    val isScreenInteractive: Boolean,
    val lastFullFetchAgeSeconds: Long,
)
```

Pass `DeviceContext` as a single parameter instead of 3–4 individual booleans + ints.

---

## 9. `doWork()` Is Still ~250 Lines After Extraction (LOW)

### Problem
Even after the above extractions, `doWork()` will be ~250 lines. The `result.fold(onSuccess = { ... })` block alone is ~150 lines with deeply nested structure.

### Recommendation (future, lower priority)
Consider splitting into mode-specific delegate classes:

```kotlin
internal interface WorkModeHandler {
    suspend fun handle(input: WorkInput, device: DeviceContext): Result
}

internal class FullSyncHandler @Inject constructor(...) : WorkModeHandler
internal class CurrentTempHandler @Inject constructor(...) : WorkModeHandler
internal class NonPrimaryHandler @Inject constructor(...) : WorkModeHandler
internal class BackfillHandler @Inject constructor(...) : WorkModeHandler
```

The worker becomes a thin dispatcher: parse `WorkInput` → select handler → delegate. This is a larger refactor and should be done after the above extractions have proven stable.

---

## 10. Widget Rendering Runs for Invisible Widgets (HIGH — correctness of design)

### Problem
`updateAllWidgets()` (line 652) iterates every installed `appWidgetId` and calls `WidgetRenderer.updateWidgetWithData()` for each — bitmap rendering (canvas drawing, text measurement) runs even for widgets on non-visible home screen pages. With `partialPush = true`, the RemoteViews push itself is cheap (`partiallyUpdateAppWidget` patches data without launcher re-inflation), but the rendering work *before* the push is wasted on invisible widgets.

`isScreenInteractive()` is already available at the top of `doWork()` but is only used for fetch policy decisions — never as a render gate.

Android provides no public API to detect which home screen page is active, which page a widget is on, or whether an `appWidgetId` is currently visible. The launcher owns that state and doesn't expose it.

### Recommendation

**Screen-off gate (simple, high impact):** Skip widget rendering entirely when the screen is off. Data still gets fetched and stored; the next screen-on or user-triggered event repaints from cache.

```kotlin
// In updateAllWidgets(), before the for loop:
if (!DeviceContext.isScreenInteractive) {
    appLogDao.log("WIDGET_PAINT_SKIP", "reason=screen_off", "INFO")
    return@coroutineScope
}
```

This costs nothing (already measured) and avoids all bitmap rendering when nobody is looking. Workers running on screen-off (e.g., periodic WorkManager ticks, opportunistic updates) still fetch data but skip the paint pass.

**Per-widget render throttling (secondary):** If the screen *is* on but background syncs fire frequently (e.g., force refresh, location candidate loop), the same widget may re-render rapidly. Track `lastRenderMs` per `appWidgetId` and skip renders within a minimum interval (e.g., 30 seconds) unless forced:

```kotlin
private val lastRenderMs = mutableMapOf<Int, Long>()

private fun shouldSkipWidgetRender(appWidgetId: Int, force: Boolean): Boolean {
    if (force) return false
    val last = lastRenderMs[appWidgetId] ?: return false
    return SystemClock.elapsedRealtime() - last < MIN_RENDER_INTERVAL_MS
}
```

**Active-screen prioritization:** Not feasible — no Android API for widget visibility or current launcher page. The screen-off gate is the practical ceiling.

### Impact
- Screen-off gate: eliminates 100% of wasted rendering during screen-off syncs
- Most periodic WorkManager ticks fire with screen off → this is the common case
- Screen-on background syncs still render all widgets (1–3 typically, acceptable)

---

## Action Plan

| # | Change | Impact | Effort | Order |
|---|---|---|---|---|
| 1 | Extract `WorkInput` data class | HIGH - cleans doWork | Low | 1st |
| 2 | Extract `WidgetDataBundle` + loader | HIGH - eliminates ~80 dup lines | Medium | 2nd |
| 3 | Extract exception handler helper | HIGH - eliminates 4x try/catch | Low | 3rd |
| 4 | Extract `HourlyForecastLoader` | MEDIUM - isolates complex method | Medium | 4th |
| 5 | Extract `tryPromoteLocationCandidate` | MEDIUM - shrinks doWork flow | Low | 5th |
| 6 | Reorganize method order | MEDIUM - readability | Low | 6th |
| 7 | Split source-race resolver from `updateAllWidgets` | LOW | Low | 7th |
| 8 | Bundle battery/screen state into `DeviceContext` | LOW | Low | 8th |
| 9 | Mode-specific delegate classes | LOW (future) | High | Defer |
| 10 | Screen-off render gate + per-widget throttle | HIGH — stops wasted rendering | Low | 1st (quick win) |

---

## Correctness Notes

No logic bugs found. But the design wastes rendering work:
- All installed widgets render on every background sync regardless of visibility
- No Android API exists to detect which home screen page a widget is on
- Screen-off renders are 100% wasted — `isScreenInteractive()` is already measured but unused for render gating

The code handles:
- Cancellation re-throw properly
- `CancellationException` caught before `Exception`
- Worker cancellation policy (`KEEP`/`APPEND_OR_REPLACE` per documented rules)
- Source-race correction at paint time
- GPS resample wrapped in try/catch (non-fatal)
- Debug crash-repro loop gated by `BuildConfig.DEBUG`

---

## Implementation Results (2026-08-04)

All 9 changes implemented and verified:

| # | Change | Result |
|---|---|---|
| 1 | `WorkInput` data class | Collapsed 17-line inputData parsing to 1 line: `WorkInput.from(inputData)` |
| 2 | `WidgetDataBundleLoader` | Eliminated ~80 duplicated lines between `doWork()` and `refreshWidgetsFromCache()` |
| 3 | `WorkerExceptionHandler` | Used in `handleObservationBackfillWork`. Not applied to `handleCurrentTempOnlyWork`/`handleNonPrimaryCurrentTempOnlyWork` (they need duration in cancellation messages) |
| 4 | `HourlyForecastLoader` | 80 lines moved to own class with `load()` and `currentDisplaySourceIds()` |
| 5 | `tryPromoteLocationCandidate` | 52-line candidate block extracted to `LocationHandoffPolicy.kt` with sealed class outcome |
| 6 | Method ordering | Reorganized into: orchestration → work-mode handlers → widget painting → lifecycle → diagnostics |
| 7 | `resolveEffectiveActuals` | Source-race correction pulled into its own method |
| 8 | `DeviceContext` | Battery/charging/screen state bundled into single parameter |
| 9 | Screen-off gate + render throttle | `updateAllWidgets` skips entirely when screen off; 30s minimum render interval per widget |

**Worker size**: 1,059 lines → 759 lines (-28%)

**Files created**:
- `widget/WorkInput.kt`
- `widget/DeviceContext.kt`
- `widget/WorkerExceptionHandler.kt`
- `widget/HourlyForecastLoader.kt`
- `widget/WidgetDataBundleLoader.kt`

**Files modified**:
- `widget/WeatherWidgetWorker.kt`
- `widget/LocationHandoffPolicy.kt`
- `architecture/HourlyProximityQueryAllowlistTest.kt`

**Tests**: All 879 short + medium + long + 513 shared tests pass.
