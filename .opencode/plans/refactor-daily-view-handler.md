# Refactor DailyViewHandler — Implementation Plan

**Goal**: Implement all code review findings for `DailyViewHandler.kt` (1569 lines), decomposing the god object, fixing DI, removing overloads, and addressing medium/low issues.

**Scope**: All priority levels (Critical, High, Medium, Low)

**Decomposition style**: Conservative (3-4 extractions)

---

## Phases

### Phase 1: Move `hideIconWidthControls` to shared helper
- **Status**: pending
- **Files**:
  - `handlers/HeaderRemoteViewsBinder.kt` — add `hideIconWidthControls(views: RemoteViews)`
  - `handlers/DailyViewHandler.kt` — remove `hideIconWidthControls`, update self-call
  - `handlers/TemperatureViewHandler.kt` — update call site
  - `handlers/PrecipViewHandler.kt` — update call site
  - `handlers/CloudCoverViewHandler.kt` — update call site
- **Risk**: Very low — pure move, no logic change
- **Tests**: Existing tests pass; `hideIconWidthControls` has no dedicated tests

### Phase 2: Remove `now`-defaulting overloads
- **Status**: pending
- **Files**:
  - `handlers/DailyViewHandler.kt` — remove 2 overloads, keep only the `@VisibleForTesting` overload with explicit `now: LocalDateTime`
  - `widget/WidgetRenderer.kt` — pass `LocalDateTime.now()` explicitly
  - `widget/WidgetIntentRouter.kt` — pass `LocalDateTime.now()` explicitly
- **Risk**: Low — callers already have `now` in scope at call sites
- **Tests**: Update any test that relied on the 2-param overload

### Phase 3: Extract `DailyHeaderBinder`
- **Status**: pending
- **Extracted from DailyViewHandler**: Lines ~187-434 (header binding block)
  - Current temp resolution + delta binding
  - Precip probability binding
  - Header date placement resolution
  - Header precip placement resolution
  - Disclosure level resolution
  - API source indicator
  - Weather icon resolution
  - Header state logging
  - `bindHeaderDate()`, `resolveHeaderDatePlacement()`, `resolveHeaderPrecipPlacement()`, `currentTempTextSizePx()`, `dailyDeltaHiddenReason()`, `buildHeaderStateLog()`, `formatLocation()`, `formatTemp()`
  - Inner enums/data classes: `HeaderDatePlacement`, `HeaderPrecipPlacement`
- **New file**: `handlers/DailyHeaderBinder.kt` (~300 lines)
- **Remaining in DailyViewHandler**: `updateWidget` orchestration, graph/text mode dispatch, click handlers, night rain grid, navigation, visibility toggling
- **Risk**: Medium — many parameters cross the boundary; need a data class for header render state
- **Tests**: Move `DailyViewHeaderDatePlacementTest` to test `DailyHeaderBinder`; add unit tests for `dailyDeltaHiddenReason`

### Phase 4: Extract `NightRainGridMapper`
- **Status**: pending
- **Extracted from DailyViewHandler**: Lines ~1338-1465
  - `nightRainGridZoneIds` constant grid
  - `setupNightRainClickHandlers()`
  - `computeNightRainGridCells()`
  - Constants: `NIGHT_RAIN_GRID_ROWS`, `NIGHT_RAIN_GRID_COLS`
- **New file**: `handlers/NightRainGridMapper.kt` (~130 lines)
- **Remaining in DailyViewHandler**: calls `NightRainGridMapper.setupNightRainClickHandlers()`
- **Risk**: Low — self-contained click-wiring logic
- **Tests**: Add pure JVM tests for `computeNightRainGridCells` (no Android deps needed)

### Phase 5: Extract `DailyClickHandlerFactory`
- **Status**: pending
- **Extracted from DailyViewHandler**: Lines ~1233-1520
  - `buildDayClickIntent()`
  - `setupGraphDayClickHandlers()`
  - `setupGraphBottomDayClickHandlers()`
  - `setupGraphZoneClickHandlers()`
  - `setupTextDayClickHandlers()`
- **New file**: `handlers/DailyClickHandlerFactory.kt` (~200 lines)
- **Remaining in DailyViewHandler**: calls factory methods
- **Risk**: Low — intent construction is stateless
- **Tests**: Move `DailyViewHandlerIntentContractTest` to test `DailyClickHandlerFactory`; existing `buildDayClickIntent` tests relocate

### Phase 6: Extract `DailyVisibilityManager`
- **Status**: pending
- **Extracted from DailyViewHandler**: Lines ~710-786
  - `setGraphModeViews()`
  - `setTextModeViews()`
  - `setSingleRowControlsVisible()`
- **New file**: `handlers/DailyVisibilityManager.kt` (~80 lines)
- **Remaining in DailyViewHandler**: calls `DailyVisibilityManager.setGraphModeViews(views)` etc.
- **Risk**: Very low — pure RemoteViews visibility toggling
- **Tests**: Minimal — these are one-line visibility calls

### Phase 7: Make `WidgetStateManager` injectable
- **Status**: pending
- **Files**:
  - `handlers/DailyViewHandler.kt` — accept `WidgetStateManager` as parameter to `updateWidget()` instead of creating it inline
  - `widget/WidgetRenderer.kt` — pass injected `WidgetStateManager`
  - `widget/WidgetIntentRouter.kt` — pass injected `WidgetStateManager`
- **Risk**: Medium — all call sites need updating; `WidgetStateManager` is already Hilt-provided
- **Note**: Keep as `object` singleton for now (consistent with other handlers); inject deps via method params
- **Tests**: Tests already create `WidgetStateManager` instances; pass them explicitly

### Phase 8: Pass `AppLogDao` as parameter instead of inline DB access
- **Status**: pending
- **Files**:
  - `handlers/DailyViewHandler.kt` — add `appLogDao: AppLogDao` parameter to `updateWidget()`
  - `widget/WidgetRenderer.kt` — pass `appLogDao` from Hilt entry point
  - `widget/WidgetIntentRouter.kt` — pass `appLogDao`
  - `handlers/DailyHeaderBinder.kt` — accept `appLogDao` as param
- **Risk**: Low — `AppLogDao` is already Hilt-provided
- **Tests**: Pass mock/test `AppLogDao` directly

### Phase 9: Fix medium-priority issues
- **Status**: pending
- **Items**:
  1. Add `import kotlin.math.abs` — remove qualified `kotlin.math.abs()` calls
  2. Replace emoji in `populateDay` line 1226 (`"💧 ${data.rainSummary}"`) with drawable or plain text
  3. Extract magic `24` at line 516 to `GRAPH_CONTENT_PADDING_DP` constant
  4. Remove commented-out `setupTextDayClickHandlers` call (line 679)
  5. Extract `stabilizeDisplayDays` from local function to companion function with explicit params
  6. Cache `resolveHeaderDatePlacement` result in `resolveHeaderPrecipPlacement` to avoid triple computation
  7. Fix `stateManager` nullable inconsistency in `updateTextMode` — make non-null (matching actual usage)
  8. Deduplicate `computeMissingDataRefreshes` calls — merge early + graph refresh decisions into single pass

### Phase 10: Fix low-priority issues
- **Status**: pending
- **Items**:
  1. Add `if (Log.isLoggable(TAG, Log.DEBUG))` guards for verbose log statements in hot paths
  2. Validate `nightRainGridZoneIds` size matches `NIGHT_RAIN_GRID_ROWS` × `NIGHT_RAIN_GRID_COLS` with runtime assert
  3. Address `headerDateFormatter` locale staleness — recreate per-update or document as acceptable

### Phase 11: Update and run tests
- **Status**: pending
- **Actions**:
  - Move relocated test files to match new class names
  - Add new pure JVM tests for `NightRainGridMapper.computeNightRainGridCells()`
  - Add new pure JVM tests for `DailyHeaderBinder.dailyDeltaHiddenReason()`
  - Run `./gradlew test` — ensure all pass
  - Run `./gradlew assembleDebug` — ensure build succeeds

---

## Dependency Order

```
Phase 1 (hideIconWidthControls) ─── independent, do first
Phase 2 (remove overloads) ──────── independent, do early
Phase 3 (DailyHeaderBinder) ─────── depends on Phase 2 (overloads gone)
Phase 4 (NightRainGridMapper) ───── independent of Phase 3
Phase 5 (DailyClickHandlerFactory) ─ independent of Phase 3-4
Phase 6 (DailyVisibilityManager) ─── independent
Phase 7 (inject WidgetStateManager) ─ best after Phase 3-6 (cleaner param list)
Phase 8 (inject AppLogDao) ────────── best after Phase 7
Phase 9 (medium issues) ──────────── best after extractions (targets are now in smaller files)
Phase 10 (low issues) ────────────── last
Phase 11 (test updates) ──────────── after all phases
```

## Estimated Line Counts After Refactor

| File | Before | After |
|------|--------|-------|
| `DailyViewHandler.kt` | 1569 | ~550 |
| `DailyHeaderBinder.kt` | — | ~300 |
| `NightRainGridMapper.kt` | — | ~130 |
| `DailyClickHandlerFactory.kt` | — | ~200 |
| `DailyVisibilityManager.kt` | — | ~80 |

---

## Errors Encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| (none yet) | | |
