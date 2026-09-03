# Header Rain Chance %: Switch Rolling Window to Next 6 Hours

**Date:** 2026-09-03
**Status:** Complete

## Problem & Motivation

The widget header across Android (`:app`) and Linux Desktop (`:desktop`) previously computed precipitation probability using a rolling 8-hour window (`LOOKAHEAD_HOURS = 8L` via `PrecipProbabilityCalculator.getNext8HourPrecipProbability` and `isNext8HourPrecipPredominantlyNight`).

However, the today-column tap routing gate was unified to use a rolling **6-hour window** (`TODAY_LOOKAHEAD_HOURS = 6L` in `DayClickResolver`), matching `ZoomStage.WIDE.window().forwardHours` (the actual span displayed when opening the graph).

Using an 8-hour window for the header caused a mismatch:
- If rain was forecast at +7 hours, the header displayed a prominent rain chance (e.g., `60%`), but tapping today's column routed to the temperature graph because the visible 6-hour forecast window had 0% rain.
- Switching both the header precipitation lookahead and the predominantly-night detection to 6 hours brings the entire application into consistency: header display, tap routing gate, and wide graph window all share the identical 6-hour forward span.

## Summary of Changes

### 1. Shared Logic (`:shared`)
- **`PrecipProbabilityCalculator.kt`**:
  - Updated `DEFAULT_LOOKAHEAD_HOURS` from `8L` to `6L`.
  - Added `getNext6HourPrecipProbability(...)` and `isNext6HourPrecipPredominantlyNight(...)` as the primary functions.
  - Retained `getNext8HourPrecipProbability(...)` and `isNext8HourPrecipPredominantlyNight(...)` as backward-compatible delegates.
  - Parameterized `isNextPrecipPredominantlyNightWithin(...)` with `lookaheadHours` so nighttime probability weighting evaluates the same 6-hour span (360 minutes).

### 2. Android Widget (`:app`)
- **`HeaderPrecipCalculator.kt`**:
  - Added `getNext6HourPrecipProbability(...)` and `isNext6HourPrecipPredominantlyNight(...)` delegating to `:shared`.
  - Preserved existing 8-hour function signatures as backward-compatible delegates.
- **Resolvers & Handlers**:
  - `DailyHeaderResolver.kt`: Uses `getNext6HourPrecipProbability` and `isNext6HourPrecipPredominantlyNight`.
  - `DailyGraphRenderer.kt`: Uses `isNext6HourPrecipPredominantlyNight`.
  - `TemperatureStateResolver.kt`: Uses `getNext6HourPrecipProbability`.
  - `PrecipViewHandler.kt`: Uses `getNext6HourPrecipProbability`.
  - `CloudCoverViewHandler.kt`: Uses `getNext6HourPrecipProbability`.
- **`DailyViewLogic.kt`**:
  - Renamed parameter `todayNext8HourPrecipProbability` to `todayPrecipProbability`, providing an overload for existing callers and test cases.

### 3. Linux Desktop Companion (`:desktop`)
- **`Main.kt` (`WidgetHeader`)**:
  - Updated `precipProb` calculation to call `PrecipProbabilityCalculator.getNext6HourPrecipProbability(...)`.
  - Updated `precipFontScale` night check to call `PrecipProbabilityCalculator.isNext6HourPrecipPredominantlyNight(...)`.

### 4. Tests & Parity
- **`PrecipProbabilityCalculatorTest.kt`**: Verified `getNext6HourPrecipProbability` delegates to a 6-hour horizon and excludes rain spikes at +7 hours.
- **`PrecipProbabilityCalculatorNightTest.kt`**: Verified `isNext6HourPrecipPredominantlyNight` functions over the 6-hour span.
- **`HeaderPrecipCalculatorTest.kt`**: Updated Android test fixtures to verify 6-hour rolling window inclusion/exclusion boundaries.
- **`DesktopUiTest.kt`**: Updated desktop UI tests to verify rain within 6 hours is displayed in the header and rain beyond 6 hours (+7h) is excluded.

## Verification
- **Unit & Robolectric Tests**: `./gradlew test` passed 100% across all modules (`:shared`, `:desktop`, `:app`).
- **Live Device Verification**:
  - Installed debug build to connected emulator via `./gradlew installDebug`.
  - Triggered widget update and verified header resolution and day-column tap behavior in `app_logs` and logcat.
