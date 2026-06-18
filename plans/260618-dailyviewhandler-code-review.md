# Plan: Implement DailyViewHandler.kt Code Review Findings

## Goal
Apply actionable code review findings to `DailyViewHandler.kt` (1559 lines) to reduce duplication, improve debuggability, and eliminate redundant work. Each phase runs tests and commits before moving on.

## Current Phase
Phase 1

## Already Done (from prior plans)
- `bindNavDirection` helper extracted (Phase 4 of 260525 plan)
- `HeaderResolution` data class exists (Phase 5)
- `DailyRenderContext` exists (Phase 8)
- `logDailyRenderSummary` takes `appLogDao` param (Phase 2)
- `hideUnusedDailyViews` extracted to `DailyVisibilityManager` (Phase 3)

## Phases

### Phase 1: Extract Sun Info into DailyRenderContext
**Finding:** `SunPositionUtils.getSunInfo(ctx.now, lat, lon)` called twice — once in `resolveHeaderState` (line 1329) and once in `renderGraphMode` (line 1145). Same inputs, same result.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [ ] Add `sunInfo` field to `DailyRenderContext`
- [ ] Compute it once in `updateWidget` before constructing the context
- [ ] Remove the two duplicate calls; use `ctx.sunInfo` in `renderGraphMode` and pass it to `resolveHeaderState`
- [ ] Run `./gradlew test`
- [ ] Commit

---

### Phase 2: Gate loadTodaySourceObservations Behind Debug Check
**Finding:** `loadTodaySourceObservations` runs a DB query on every graph-mode render (line 1058) solely for provenance logging. Adds unnecessary latency in production.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [ ] Wrap the `loadTodaySourceObservations` + `buildTodayHighProvenanceMessage` block (lines 1058–1081) in a `Log.isLoggable(TAG, Log.DEBUG)` or `BuildConfig.DEBUG` guard
- [ ] Run `./gradlew test`
- [ ] Commit

---

### Phase 3: Consolidate WeatherDatabase.getDatabase Calls
**Finding:** `WeatherDatabase.getDatabase(context)` called at lines 181, 514, and 996. Room's `getDatabase` is singleton-cached, but the pattern is inconsistent — `appLogDao` is already in `DailyRenderContext` but the database itself is not.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [ ] Add `database` field to `DailyRenderContext` (computed once at construction)
- [ ] Replace inline `WeatherDatabase.getDatabase(context)` calls in `maybeBackfillIncompleteHistory` and `renderGraphMode` with `ctx.database` or pass as parameter
- [ ] Run `./gradlew test`
- [ ] Commit

---

### Phase 4: Extract Magic Strings to Constants
**Finding:** Log tags `"WIDGET_ACTUAL"`, `"TODAY_BAR_DEBUG"`, `"TODAY_HIGH_PROVENANCE"`, `"DAILY_RENDER"`, `"DAILY_RENDER_EMPTY"` are scattered inline strings.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [ ] Add `private const val` entries for each magic string at the top of `DailyViewHandler`
- [ ] Replace inline usages with the constants
- [ ] Run `./gradlew test`
- [ ] Commit

---

### Phase 5: Extract Formatting Utilities
**Finding:** `formatTempValue`, `formatDistance`, `formatLocalTime` (lines 875–888) are private formatting helpers that could serve other diagnostic code.
**Risk:** Low
**Files:** DailyViewHandler.kt, possibly a new small file

- [ ] Decide target: keep as private in `DailyViewHandler` (simplest) or move to a shared `WidgetFormatUtils` object
- [ ] If extracting: create the file, move the 3 functions, update imports
- [ ] If keeping: no-op, document as intentional
- [ ] Run `./gradlew test`
- [ ] Commit

---

### Phase 6: Add Diagnostic Log for Dropped Dates in weatherByDate
**Finding:** `weatherByDate` construction (lines 251–276) silently drops dates via `mapNotNull { chosen?.let { date to it } }`. When debugging missing days, there's no trace of why a date was excluded.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [ ] Add a `Log.d` inside the `mapNotNull` lambda when `chosen` is null, logging the date and reason (no preferred source, or incomplete temps with no gap fallback)
- [ ] Run `./gradlew test`
- [ ] Commit

---

### Phase 7: Document GENERIC_GAP in Navigation Dates
**Finding:** `buildAvailableNavigationDates` (line 622) includes `WeatherSource.GENERIC_GAP` dates, which means nav buttons may claim availability for climate-normal-only dates. This may be intentional (allow navigating to see climate normals) or a bug.
**Risk:** Low (documentation only)
**Files:** DailyViewHandler.kt

- [ ] Add a comment to `buildAvailableNavigationDates` explaining why `GENERIC_GAP` is included
- [ ] If it's a bug, fix it and update tests; if intentional, document the rationale
- [ ] Run `./gradlew test`
- [ ] Commit

---

### Phase 8: Fix logGraphDayIconDetails Column Index Fallback
**Finding:** `logGraphDayIconDetails` (line 454) uses `day.columnIndex ?: index` as fallback. If `columnIndex` differs from list position, the log output would be misleading.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [ ] Verify whether `columnIndex` can differ from the list index
- [ ] If always the same: remove the `?: index` fallback and use `day.columnIndex` directly (or `index` directly)
- [ ] If can differ: keep as-is, add a comment explaining the fallback
- [ ] Run `./gradlew test`
- [ ] Commit

---

### Phase 9 (Optional, Larger): Extract renderGraphMode
**Finding:** `renderGraphMode` is ~240 lines (985–1227). It handles DB queries, bitmap rendering, click handler setup, and logging.
**Risk:** Medium
**Files:** DailyViewHandler.kt, possibly a new file

- [ ] Extract into a `DailyGraphRenderer` object or private function group
- [ ] Keep `DailyRenderContext` as the shared input
- [ ] Run `./gradlew test`
- [ ] Commit

---

## Key Questions
1. Is `GENERIC_GAP` in nav dates intentional or a bug? (Phase 7)
2. Does `columnIndex` in `DayData` ever differ from list position? (Phase 8)
3. Should formatting utilities be extracted or stay private? (Phase 5)

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Extract sun info into DailyRenderContext | Eliminates duplicate computation with zero behavior change |
| Gate provenance logging behind debug | Production renders don't need it; saves a DB query per render |
| Keep phases small and independently committable | Each phase is safe, testable, and reversible |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
|       |         |            |
