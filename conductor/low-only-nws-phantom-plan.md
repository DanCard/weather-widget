# Plan: Low-Only NWS "Phantom" Day Support

Enable the display of the final NWS forecast night even when the subsequent day's high temperature is not yet available. This avoids losing valid forecast data and replaces "phantom day" deletion with an honest UI representation.

## Objective
- Stop deleting future days from NWS that only contain a low temperature.
- Update `DailyViewLogic` to allow future days with partial data (low-only) to remain attributed to their original source rather than falling back to Climate Normals.
- Ensure the Daily Graph renders a minimal bar and the correct labels for these "low-only" columns.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt`: Currently deletes future days with missing highs.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`: Currently forces Climate Normal fallback if either high or low is missing for a future day.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Renders the bars and labels.

## Implementation Steps

### 1. Remove Phantom Day Deletion
Modify `NwsForecastMapper.kt` to preserve all returned forecast days.

- **File:** `app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt`
- **Action:** Remove the call to `removePhantomFutureDays(acc.temperatureMap, todayDate)` around line 108.
- **Action:** Delete the `removePhantomFutureDays` function from the `companion object`.
- **Action:** Delete the corresponding test file `app/src/test/java/com/weatherwidget/data/repository/ForecastRepositoryPhantomDayTest.kt`.

### 2. Relax Display Logic Fallbacks
Modify `DailyViewLogic.kt` to allow days with only a low temperature to skip the Climate Normal fallback.

- **File:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- **Action:** Change the condition `if (finalHigh == null || finalLow == null)` to `if (finalHigh == null && finalLow == null)` for future days (around line 332).
- **Result:** If NWS provides a low but no high, the high remains `null`, and we keep the NWS low and the NWS icon/condition instead of overwriting both with Climate Normals.

### 3. Update Unit Tests
Ensure our logic tests reflect the new "Honest Data" philosophy.

- **File:** `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`
- **Action:** Update the test `future day with null highTemp is filtered out` (or similar) to assert that the day is **now present** in the output list with its low temp preserved.

## Verification & Testing
- **Visual Audit**: Look at the last column of the daily forecast on the emulator when using NWS. It should now show a label (e.g., "Sat") with a tiny bar/dot at the low temperature, a low temp label, but no high temp label.
- **Automated Tests**:
    - Run `./gradlew testDebugUnitTest` to ensure no regressions in mapping or logic.
    - Specifically verify `DailyForecastGraphRendererTest` to ensure it still handles `high = null` gracefully.
