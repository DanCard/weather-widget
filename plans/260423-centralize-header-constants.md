# Centralize Header Constants into HeaderConstants.kt

## Problem
Header rendering constants (icon sizes, text sizes, margins, spacing) are duplicated across 6+ files. Changing any value requires hunting down every copy. Recent size reduction of icon/temp required changing only 2 values because they were already in HeaderConstants; all other header values are scattered.

## Goal
Move all header-related DP constants into `HeaderConstants.kt` and replace every local copy and inline literal with a reference to the centralized constant.

## Constants to Add

| Constant | Value | Was duplicated across |
|----------|-------|-----------------------|
| `DELTA_TEXT_SIZE_DP` | 14f | 5 files |
| `WEATHER_ICON_END_MARGIN_DP` | 2f | 3 files |
| `DELTA_MARGIN_START_DP` | 4f | 3 files |
| `PRECIP_MARGIN_START_DP` | 8f | 3 files |
| `API_SOURCE_MARGIN_END_DP` | 32f | 3 files |
| `API_SOURCE_CONTAINER_PADDING_DP` | 14f | 3 files |
| `DATE_TEXT_SIZE_DP` | 20f | 2 files |
| `DATE_HORIZONTAL_GAP_DP` | 6f | 3 files |
| `DATE_RIGHT_MARGIN_DP` | 112f | 2 files |
| `DATE_MIN_COLUMNS` | 6 | 1 file |
| `SETTINGS_ICON_SIZE_DP` | 18f | 1 file |
| `SETTINGS_ICON_MARGIN_END_DP` | 0f | 1 file |
| `PRECIP_TEXT_BASE_SIZE_DP` | 26f | 3 files |
| `API_TEXT_SIZE_LARGE_DP` | 18f | 5 files |
| `API_TEXT_SIZE_MEDIUM_DP` | 16f | 5 files |
| `API_TEXT_SIZE_SMALL_DP` | 14f | 5 files |
| `apiTextSizeDp(numRows)` | function | 5 files |

Also add `apiTextSizeDp(numRows)` as a shared function on `HeaderConstants` to eliminate the duplicated `when` block in 5 view handlers.

## Files to Update

### Already completed
1. **HeaderConstants.kt** - Add all new constants and `apiTextSizeDp()` function
2. **DailyForecastGraphRenderer.kt** - Remove 9 local header consts, replace inline literals (14f, 6f, 112f, 32f) with HeaderConstants refs

### In progress
3. **DailyViewHandler.kt** - Remove 6 local consts (`HEADER_DATE_MIN_COLUMNS`, `HEADER_DATE_TEXT_SIZE_DP`, `HEADER_DATE_RIGHT_MARGIN_DP`, `CURRENT_TEMP_DELTA_TEXT_SIZE_DP`, `WEATHER_ICON_END_MARGIN_DP`, `HEADER_DATE_HORIZONTAL_GAP_DP`), replace `apiTextSizeDp()` calls, replace inline 4f/8f/14f/32f in `resolveLeftHeaderClusterRightPx`/`resolveApiLeftPx`, remove local `apiTextSizeDp()` function

### Remaining
4. **HeaderWidthChecker.kt** - Remove 7 local consts (`WEATHER_ICON_END_MARGIN_DP`, `HEADER_DATE_HORIZONTAL_GAP_DP`, `API_SOURCE_MARGIN_END_DP`, `API_SOURCE_CONTAINER_PADDING_DP`, `DELTA_MARGIN_START_DP`, `PRECIP_MARGIN_START_DP`, `CURRENT_TEMP_DELTA_TEXT_SIZE_DP`), replace with HeaderConstants refs
5. **HeaderPrecipCalculator.kt** - Replace 7 occurrences of `26f` with `HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP`
6. **TemperatureViewBinder.kt** - Replace inline `14f` with `HeaderConstants.DELTA_TEXT_SIZE_DP`, replace local `apiTextSizeDp()` with `HeaderConstants.apiTextSizeDp()`
7. **TemperatureViewHandler.kt** - Replace inline `14f` with `HeaderConstants.DELTA_TEXT_SIZE_DP`
8. **TemperatureTouchTargets.kt** - Replace inline `18f/16f/14f` when-block with `HeaderConstants.apiTextSizeDp()`
9. **TemperatureStateResolver.kt** - No changes needed (already uses `HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP`)
10. **CloudCoverViewHandler.kt** - Replace inline `18f/16f/14f` in `setupApiToggle` with `HeaderConstants.apiTextSizeDp()`, remove local `apiTextSizeDp()` function
11. **PrecipViewHandler.kt** - Same as CloudCoverViewHandler

## DailyViewHandler.kt remaining edits
- Line 1066: `dpToPx(context, 14f)` -> `dpToPx(context, HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP)`
- Line 1067: `dpToPx(context, 32f)` -> `dpToPx(context, HeaderConstants.API_SOURCE_MARGIN_END_DP)`
- Line 601: `else 26f` -> `else HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP`
- Remove local `apiTextSizeDp()` function (lines ~855-860)
- `val textSizeDp = apiTextSizeDp(numRows)` -> `val textSizeDp = HeaderConstants.apiTextSizeDp(numRows)` in `setupApiToggle`

## Verification
- `./gradlew assembleDebug` must pass
- No behavior change — purely structural refactor
