# Centralize Header Constants + Refactoring Plan

**Date:** 2026-04-23
**Model:** glm-5.1
**Scope:** Header constants consolidation across all view handlers, refactoring plan for remaining duplicate code

---

## Prompts

1. "daily forecast view: header row: weather indicator icon and current temp, reduce size."
2. "do it" (selected moderate reduction)
3. "Should there be more constants in HeaderConstants.kt? What about font size for API? What about gear icon size? Do you want to scan the codebase and figure that out?"
4. "do it" (execute the centralization)
5. "commit and push"
6. "write plan to plans/ dir"
7. "continue" (resume after plan was written)
8. "commit all and push"
9. "I suspect there is more duplicate code across those 9 files. Would it be easy for you to check for duplicate code and create a plan to factor out?"
10. "copy plan to plans/ dir"
11. "commit all and push"
12. "write detailed session log to session-logs/"

---

## Background

`HeaderConstants.kt` existed with only 2 constants: `CURRENT_TEMP_TEXT_SIZE_DP` and `WEATHER_ICON_SIZE_DP`. All other header-related DP values (icon margins, text sizes, spacings) were duplicated as local `private const val` across 6+ files or hardcoded inline. The initial request to reduce icon/temp sizes revealed this consolidation gap.

---

## Phase 1: Reduce Header Icon and Temp Size

### Changes

**`HeaderConstants.kt`:**
- `WEATHER_ICON_SIZE_DP`: 36f → 28f (~22% reduction)
- `CURRENT_TEMP_TEXT_SIZE_DP`: 26f → 20f (~23% reduction)

Note: the file on disk ended up at 26f/24f after a subsequent session nudged these values.

### Verification

`./gradlew assembleDebug` passed. Change propagated automatically to all view handlers (Daily, CloudCover, Precip, Temperature) since they all reference `HeaderConstants`.

### Commit

```
(previous session — values may differ slightly due to subsequent nudge commits)
```

---

## Phase 2: Scan for Centralizable Constants

Used a `very thorough` explore agent to scan all header-related code across the codebase. Found **14 candidate constants** duplicated across 2-5 files each, plus a duplicated `apiTextSizeDp()` function copied into 5 view handlers.

### Findings Summary

| Constant | Value | Duplicated across |
|----------|-------|-------------------|
| Delta text size | 14f | 5 files |
| API text size (tiered) | 18/16/14f | 5 files |
| Weather icon end margin | 2f | 3 files |
| Delta margin start | 4f | 3 files |
| Precip margin start | 8f | 3 files |
| API margin end | 32f | 3 files |
| API container padding | 14f | 3 files |
| Date text size | 20f | 2 files |
| Date horizontal gap | 6f | 3 files |
| Date right margin | 112f | 2 files |
| Date min columns | 6 | 1 file |
| Settings icon size | 18f | 1 file |
| Settings margin end | 0f | 1 file |
| Precip text base size | 26f | 3 files |

---

## Phase 3: Execute Centralization

### HeaderConstants.kt Expansion

Grew from 2 constants to 17 constants + 1 shared function:

```kotlin
object HeaderConstants {
    const val CURRENT_TEMP_TEXT_SIZE_DP = 24f
    const val WEATHER_ICON_SIZE_DP = 26f
    const val DELTA_TEXT_SIZE_DP = 14f
    const val WEATHER_ICON_END_MARGIN_DP = 2f
    const val DELTA_MARGIN_START_DP = 4f
    const val PRECIP_MARGIN_START_DP = 8f
    const val API_SOURCE_MARGIN_END_DP = 32f
    const val API_SOURCE_CONTAINER_PADDING_DP = 14f
    const val DATE_TEXT_SIZE_DP = 20f
    const val DATE_HORIZONTAL_GAP_DP = 6f
    const val DATE_RIGHT_MARGIN_DP = 112f
    const val DATE_MIN_COLUMNS = 6
    const val SETTINGS_ICON_SIZE_DP = 18f
    const val SETTINGS_ICON_MARGIN_END_DP = 0f
    const val PRECIP_TEXT_BASE_SIZE_DP = 26f
    const val API_TEXT_SIZE_LARGE_DP = 18f
    const val API_TEXT_SIZE_MEDIUM_DP = 16f
    const val API_TEXT_SIZE_SMALL_DP = 14f

    fun apiTextSizeDp(numRows: Int): Float = when {
        numRows >= 3 -> API_TEXT_SIZE_LARGE_DP
        numRows >= 2 -> API_TEXT_SIZE_MEDIUM_DP
        else -> API_TEXT_SIZE_SMALL_DP
    }
}
```

### Files Updated (11 total)

1. **HeaderConstants.kt** — Added 15 new constants + `apiTextSizeDp()` function
2. **DailyForecastGraphRenderer.kt** — Removed 9 local header constants, replaced 7 inline literals with HeaderConstants refs
3. **DailyViewHandler.kt** — Removed 6 local constants + `apiTextSizeDp()` function, replaced inline 4f/8f/14f/32f literals
4. **HeaderWidthChecker.kt** — Removed 7 local constants
5. **HeaderPrecipCalculator.kt** — Replaced 7 occurrences of `26f` with `HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP`
6. **TemperatureViewBinder.kt** — Replaced inline `14f` with HeaderConstants, removed local `apiTextSizeDp()`
7. **TemperatureViewHandler.kt** — Replaced inline `14f` with `HeaderConstants.DELTA_TEXT_SIZE_DP`
8. **TemperatureTouchTargets.kt** — Replaced inline `18f/16f/14f` when-block with `HeaderConstants.apiTextSizeDp()`
9. **CloudCoverViewHandler.kt** — Replaced inline API text size when-block, removed local `apiTextSizeDp()`
10. **PrecipViewHandler.kt** — Same as CloudCoverViewHandler

### Bugs Fixed During Execution

- **Double reference bug**: An early `replaceAll` of `apiTextSizeDp(numRows)` in DailyViewHandler produced `HeaderConstants.HeaderConstants.apiTextSizeDp()` in one spot. Caught by build failure, fixed manually.
- **Missing `=` in named argument**: An edit to DailyViewHandler accidentally dropped `= formattedTemp` from a `currentTempText` parameter. Caught by visual inspection and fixed.
- **Broken method call**: An edit in TemperatureViewBinder accidentally truncated `setupHomeShortcut(context, views, appWidgetId)` to `setupHomeShortcut`. Caught and fixed.

### Build Verification

`./gradlew assembleDebug` passed after all changes. Verified no stale local constants remain with `rg` searches.

### Commit

```
10ddcc4 Centralize all header constants into HeaderConstants.kt
```

---

## Phase 4: Duplicate Code Refactoring Plan

Scanned all 14 handler/renderer files for deeper structural duplication beyond constants. Found **20 duplicated patterns** across 9 batches:

1. **Batch 1** — 7 setup functions in TemperatureTouchTargets (4 copies each) — ~350 lines
2. **Batch 2** — 6 measurement functions in HeaderWidthChecker (2 copies) — ~50 lines
3. **Batch 3** — Header RemoteViews binding patterns (4 patterns, 3-4 copies) — ~80 lines
4. **Batch 4** — Bitmap size calculation (4 copies with magic numbers) — ~18 lines
5. **Batch 5** — `getCurrentHourForecast` (3 exact copies) — ~24 lines
6. **Batch 6** — Source warning block pattern (3 copies) — ~30 lines
7. **Batch 7** — Current temp resolution + delta state (3 copies) — ~30 lines
8. **Batch 8** — Hourly data builder (Cloud/Precip, 2 copies) — ~80 lines
9. **Batch 9** — Widget init boilerplate (4 copies) — ~18 lines

**Total estimated savings: ~680 lines**

Plan written to `plans/260423-factor-out-duplicate-handler-code.md`.

### Commit

```
65fd79a Add refactoring plan for duplicate handler code (~680 lines)
```

---

## Files Modified This Session

1. `app/src/main/java/com/weatherwidget/widget/handlers/HeaderConstants.kt`
2. `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
3. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
4. `app/src/main/java/com/weatherwidget/widget/handlers/HeaderWidthChecker.kt`
5. `app/src/main/java/com/weatherwidget/util/HeaderPrecipCalculator.kt`
6. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewBinder.kt`
7. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`
8. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureTouchTargets.kt`
9. `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`
10. `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`
11. `plans/260423-centralize-header-constants.md` (plan from prior commit)
12. `plans/260423-factor-out-duplicate-handler-code.md` (new)

---

## Commits This Session

```
10ddcc4 Centralize all header constants into HeaderConstants.kt
65fd79a Add refactoring plan for duplicate handler code (~680 lines)
```

---

## Lessons

1. **`replaceAll` is dangerous with partial strings**: Replacing `apiTextSizeDp(numRows),` with `HeaderConstants.apiTextSizeDp(numRows),` across DailyViewHandler caught an unintended match that created `HeaderConstants.HeaderConstants.apiTextSizeDp`. Build errors caught it, but more specific oldString patterns would have avoided it.

2. **Incremental verification matters**: Each file was edited independently and the build was run once at the end. The 3 bugs found were all caught by the build. For a 11-file refactor, doing a build check after every 2-3 files would have made debugging easier.

3. **Explore agent is effective for duplication analysis**: The `very thorough` explore agent found all 14 duplicated constants and 20 duplicated code patterns across 14 files in a single pass. This was more efficient than manual grep-based searching.

4. **Centralized constants enable single-point size changes**: The original request ("reduce icon and temp size") only required changing 2 values in HeaderConstants. Before this session, the same change would have required editing 6+ files with risk of missing a copy.
