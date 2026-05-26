# Plan: Implement DailyViewHandler Code Review Findings

## Goal

Address the 11 findings from the code review of `DailyViewHandler.kt` with focused, low-risk changes. Ordered by impact and risk (highest impact, lowest risk first).

## Phases

### Phase 1: Quick wins — dead code, unused imports, stale comments [low risk]

**Findings addressed:** #1 (dead `showDualButton`), #2 (unused `floor` import), stale blank lines/comment at 84-87

Changes:
1. Delete `import kotlin.math.floor` (line 56)
2. Delete the 4 unused lines 274-279 (`nextSourceForButton`, `hasDistinctSecondSource`, `showDualButton`) — the actual dual button logic lives in `headerRenderData.showDualButton` at line 1109
3. Remove the empty comment block at lines 84-87 (`// Intent actions from WidgetActions` plus two blank lines)

Verification: `./gradlew assembleDebug` (compilation check)

---

### Phase 2: Fix redundant DB call in `logDailyRenderSummary` [low risk]

**Finding addressed:** #3

Changes:
1. Add `appLogDao: AppLogDao` parameter to `logDailyRenderSummary` (line 495)
2. Replace `WeatherDatabase.getDatabase(context).appLogDao().log(...)` with `appLogDao.log(...)` (line 510)
3. Update all 3 call sites to pass the existing `appLogDao`:
   - Line 183 (warning path)
   - Line 888 (text mode path)
   - Line 1076 (graph mode path)

Verification: `./gradlew assembleDebug`

---

### Phase 3: Extract `hideUnusedDailyViews` into `DailyVisibilityManager` [low risk]

**Finding addressed:** #7 (repetitive visibility toggling, lines 306-317)

Changes:
1. Add `fun hideUnusedDailyViews(views: RemoteViews)` to `DailyVisibilityManager` that hides all 12 view IDs from lines 306-317
2. Replace the 12-line block in `DailyViewHandler.updateWidget` (lines 306-317) with `DailyVisibilityManager.hideUnusedDailyViews(views)`
3. Note: the same views are also hidden individually in `setGraphModeViews` and `setTextModeViews`. After this change, `hideUnusedDailyViews` should be called once in `updateWidget` before the graph/text branch, while the existing mode-specific methods continue to handle their own mode-specific views.

Verification: `./gradlew assembleDebug`

---

### Phase 4: Extract nav button symmetry helper [low risk]

**Finding addressed:** #8

Changes:
1. Create a private helper `bindNavDirection` in `setupNavigationButtons` (or as a sibling private fun) that handles the repeated pattern:
   ```kotlin
   private fun bindNavDirection(
       views: RemoteViews,
       buttonId: Int, zoneId: Int,
       context: Context, appWidgetId: Int,
       requestCodeProvider: (Int) -> Int,
       navAction: String,
       canNavigate: Boolean,
       toastMessage: String,
   )
   ```
2. Replace the ~50 lines of left/right duplication (548-598) with two calls to the helper

Verification: `./gradlew assembleDebug`

---

### Phase 5: Introduce `HeaderResolution` data class [low-medium risk]

**Finding addressed:** #6 (`Pair<HeaderState, HeaderPrecipPlacement>`)

Changes:
1. Add a private data class inside `DailyViewHandler`:
   ```kotlin
   private data class HeaderResolution(
       val state: HeaderState,
       val precipPlacement: DailyHeaderBinder.HeaderPrecipPlacement,
   )
   ```
2. Change `resolveAndBindHeader` return type from `Pair<...>` to `HeaderResolution`
3. Change `resolveHeaderState` return type from `Pair<...>` to `HeaderResolution`
4. Update the destructuring at line 230 from `val (headerState, headerPrecipPlacement) = ...` to `val resolution = ...` and update references (`resolution.state`, `resolution.precipPlacement`)

Verification: `./gradlew assembleDebug`

---

### Phase 6: Fix text-mode missing data refresh bug [medium risk]

**Finding addressed:** #11 (text mode silently skips 2 of 3 refresh checks)

Changes:
1. Refactor `computeMissingDataRefreshes` to accept a mode-agnostic representation instead of `List<DayData>`:
   ```kotlin
   internal fun computeMissingDataRefreshes(
       today: LocalDate,
       displaySource: WeatherSource,
       dailyActuals: Map<LocalDate, DailyActual>,
       visibleDates: Set<LocalDate> = emptySet(),
       todayHasSnapshot: Boolean = true,
   ): List<MissingDataRefreshDecision>
   ```
2. Update check #2 (today snapshot): test `!todayHasSnapshot` instead of iterating `displayDays`
3. Update check #3 (past actuals): test `visibleDates.any { it.isBefore(today) && dailyActuals[it] == null }` instead of iterating `displayDays`
4. Update graph-mode call site (line 1050) to derive `visibleDates` and `todayHasSnapshot` from `displayDays`
5. Update text-mode call site (line 368) to pass `visibleDates` from `updateTextMode` results and `todayHasSnapshot` from `DailyViewLogic.prepareTextDays` output
6. If `TextDayData` doesn't expose snapshot info, add a field or compute it from `forecastSnapshots`

Verification: `./gradlew assembleDebug`, then `./gradlew test` to run existing unit tests for `MissingDataRefreshHelper`

---

### Phase 7: Reduce destructuring noise in `updateWidget` [low risk, cosmetic]

**Finding addressed:** #9

Changes:
1. Remove the 8-line destructuring block (lines 106-113) from `updateWidget`
2. Pass `weatherData` and `observationData` directly to sub-methods where possible
3. Where sub-methods still need individual fields, access them inline (e.g., `weatherData.weatherList`) or add `WeatherData`/`ObservationData` parameters to those methods

Note: This may overlap with Phase 8 (render context). If Phase 8 is implemented first, this phase can be skipped or merged.

Verification: `./gradlew assembleDebug`

---

### Phase 8 (optional, larger scope): Introduce `DailyRenderContext` [medium-high risk]

**Finding addressed:** #5 (parameter explosion), #4 (updateWidget length)

This is the highest-impact change but also the highest risk. Recommend deferring unless the team wants to invest in a larger refactor.

Changes:
1. Create a `DailyRenderContext` data class holding the ~15 common parameters
2. Construct it once in `updateWidget`
3. Refactor `renderGraphMode`, `renderTextMode`, `resolveAndBindHeader`, etc. to accept `DailyRenderContext` instead of 15-30 individual parameters
4. Consider extracting `renderGraphMode` into a `GraphModeRenderer` object and `renderTextMode` into a `TextModeRenderer` object

Verification: `./gradlew assembleDebug`, `./gradlew test`

---

### Phase 9 (optional): Defer logging-only DB query [low risk]

**Finding addressed:** #10 (`loadTodaySourceObservations` adds render latency)

Changes:
1. Wrap the `loadTodaySourceObservations` + `buildTodayHighProvenanceMessage` block (lines 1007-1028) in `CoroutineScope(Dispatchers.IO).launch { ... }` so it runs after the widget is painted
2. Or gate it behind `BuildConfig.DEBUG` / a verbose-logging flag

Verification: `./gradlew assembleDebug`, visual check that provenance logs still appear

---

## Execution Order

| Phase | Risk | Effort | Files Changed |
|-------|------|-------|---------------|
| 1 | Low | Small | DailyViewHandler.kt |
| 2 | Low | Small | DailyViewHandler.kt |
| 3 | Low | Small | DailyViewHandler.kt, DailyVisibilityManager.kt |
| 4 | Low | Small | DailyViewHandler.kt |
| 5 | Low-Med | Small | DailyViewHandler.kt |
| 6 | Medium | Medium | MissingDataRefreshHelper.kt, DailyViewHandler.kt, possibly DailyViewLogic.kt |
| 7 | Low | Small | DailyViewHandler.kt |
| 8 | Med-High | Large | DailyViewHandler.kt + new files |
| 9 | Low | Small | DailyViewHandler.kt |

**Recommended:** Phases 1-6 are high-value and should be done. Phases 7-9 are optional polish.

## Execution Status

All 9 phases completed and verified:

- **Build:** `./gradlew assembleDebug` — BUILD SUCCESSFUL
- **Tests:** `./gradlew test` — 1280 tests passed, 0 failures

### Files Modified

1. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt` — Phases 1, 2, 4, 5, 7, 8, 9
2. `app/src/main/java/com/weatherwidget/widget/handlers/DailyVisibilityManager.kt` — Phase 3
3. `app/src/main/java/com/weatherwidget/widget/handlers/MissingDataRefreshHelper.kt` — Phase 6
4. `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewHandlerTest.kt` — Phase 6 (test updates)
