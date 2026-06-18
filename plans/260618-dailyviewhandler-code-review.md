# Plan: Implement DailyViewHandler.kt Code Review Findings

## Goal
Apply actionable code review findings to `DailyViewHandler.kt` (1559 lines) to reduce duplication, improve debuggability, and eliminate redundant work. Each phase runs tests and commits before moving on.

## Current Phase
Complete (Phase 9 deferred)

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

- [x] Add `sunInfo` field to `DailyRenderContext`
- [x] Compute it once in `updateWidget` before constructing the context
- [x] Remove the two duplicate calls; use `ctx.sunInfo` in `renderGraphMode` and pass it to `resolveHeaderState`
- [x] Run `./gradlew test`
- [x] Commit: `6c99b6b6`

---

### Phase 2: Gate loadTodaySourceObservations Behind Debug Check
**Finding:** `loadTodaySourceObservations` runs a DB query on every graph-mode render (line 1058) solely for provenance logging. Adds unnecessary latency in production.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [x] Wrap the `loadTodaySourceObservations` + `buildTodayHighProvenanceMessage` block (lines 1058–1081) in a `Log.isLoggable(TAG, Log.DEBUG)` guard
- [x] Run `./gradlew test`
- [x] Commit: `7de0a938`

---

### Phase 3: Consolidate WeatherDatabase.getDatabase Calls
**Finding:** `WeatherDatabase.getDatabase(context)` called at lines 181, 514, and 996. Room's `getDatabase` is singleton-cached, but the pattern is inconsistent — `appLogDao` is already in `DailyRenderContext` but the database itself is not.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [x] Add `database` field to `DailyRenderContext` (computed once at construction)
- [x] Replace inline `WeatherDatabase.getDatabase(context)` calls in `maybeBackfillIncompleteHistory` and `renderGraphMode` with `ctx.database` or pass as parameter
- [x] Run `./gradlew test`
- [x] Commit: `c0962edf`

---

### Phase 4: Extract Magic Strings to Constants
**Finding:** Log tags `"WIDGET_ACTUAL"`, `"TODAY_BAR_DEBUG"`, `"TODAY_HIGH_PROVENANCE"`, `"DAILY_RENDER"`, `"DAILY_RENDER_EMPTY"` are scattered inline strings.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [x] Add `private const val` entries for each magic string at the top of `DailyViewHandler`
- [x] Replace inline usages with the constants
- [x] Run `./gradlew test`
- [x] Commit: `c63edf1a`

---

### Phase 5: Extract Formatting Utilities
**Finding:** `formatTempValue`, `formatDistance`, `formatLocalTime` (lines 875–888) are private formatting helpers that could serve other diagnostic code.
**Risk:** Low
**Files:** DailyViewHandler.kt, possibly a new file

- [x] Decided: keep private — only used within DailyViewHandler, no extraction needed
- [x] Run `./gradlew test`
- [x] No commit needed (no-op)

---

### Phase 6: Add Diagnostic Log for Dropped Dates in weatherByDate
**Finding:** `weatherByDate` construction (lines 251–276) silently drops dates via `mapNotNull { chosen?.let { date to it } }`. When debugging missing days, there's no trace of why a date was excluded.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [x] Add a `Log.d` inside the `mapNotNull` lambda when `chosen` is null, logging the date and reason
- [x] Run `./gradlew test`
- [x] Commit: `307aafd4`

---

### Phase 7: Document GENERIC_GAP in Navigation Dates
**Finding:** `buildAvailableNavigationDates` (line 622) includes `WeatherSource.GENERIC_GAP` dates, which means nav buttons may claim availability for climate-normal-only dates. This may be intentional (allow navigating to see climate normals) or a bug.
**Risk:** Low (documentation only)
**Files:** DailyViewHandler.kt

- [x] Added comment explaining why GENERIC_GAP is included (intentional — allows navigating to far-future climate normals)
- [x] Run `./gradlew test`
- [x] Commit: `b9b2f49d`

---

### Phase 8: Fix logGraphDayIconDetails Column Index Fallback
**Finding:** `logGraphDayIconDetails` (line 454) uses `day.columnIndex ?: index` as fallback. If `columnIndex` differs from list position, the log output would be misleading.
**Risk:** Low
**Files:** DailyViewHandler.kt

- [x] Verified: columnIndex is always set by DailyViewLogic; added comment documenting the safety fallback
- [x] Run `./gradlew test`
- [x] Commit (included in prior commit)

---

### Phase 9 (Optional, Larger): Extract renderGraphMode
**Finding:** `renderGraphMode` is ~267 lines (1011–1278). It handles DB queries, bitmap rendering, click handler setup, and logging.
**Risk:** Medium
**Files:** DailyViewHandler.kt, possibly a new file

- [ ] Extract into a `DailyGraphRenderer` object or private function group
- [ ] Keep `DailyRenderContext` as the shared input
- [ ] Run `./gradlew test`
- [ ] Commit
- **Status:** cancelled (deferred — medium risk, function is well-structured internally)

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
