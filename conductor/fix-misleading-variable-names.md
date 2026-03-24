# Plan: Fix Misleading Variable Names in `DailyForecastGraphRenderer.kt`

The `DailyForecastGraphRenderer.kt` currently uses misleading variable names for the "Today" triple-bar component. Specifically, variables named "Yellow" are used for red-colored elements (observed range), and "Orange" is used for yellow-colored elements (snapshot/history). This plan refactors these names to match their visual color and semantic meaning.

## Objective
Update variable names in `DailyForecastGraphRenderer.kt` to accurately reflect their rendered colors and functional roles.

## Key Changes

### 1. Refactor "Today" Bar Paints
- Rename `todayTripleYellowPaint` (color `#FF3366`) to `todayObservedRedPaint`.
- Rename `todayTripleYellowBulbPaint` (color `#FF3366`) to `todayObservedRedBulbPaint`.
- Rename `todayTripleOrangePaint` (color `#FFFF00`) to `todaySnapshotYellowPaint`.
- Rename `todayTripleBluePaint` (color `#5AC8FA`) to `todayForecastBluePaint`.

### 2. Update All Usages
Update all call sites within the `renderGraph` method to use the new variable names.

## Implementation Steps

### 1. Renaming and Usage Update in `DailyForecastGraphRenderer.kt`
- **File**: `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **Update Paint Declarations**: Rename variables at lines 152, 160, 168, and 174.
- **Update Rendering Logic**: Replace old names in the `isToday` branch of the `days.forEachIndexed` loop (lines 350-430).

## Verification
- **Compilation**: Ensure the project builds successfully using `./gradlew assembleDebug`.
- **Visual Regression**: Verify that the "Today" bar still renders with the correct colors (Red center, Yellow left, Blue right) on the emulator.
- **Log Verification**: Ensure the logs still output correctly with the new logic if any logs were using these variables (though the current logs use hardcoded tags).
