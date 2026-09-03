# Header rain chance %: switch rolling window from next-8-hours to next-6-hours

**Date:** 2026-09-03
**Status:** proposed

## Problem

The widget header across Android (`:app`) and Linux Desktop (`:desktop`) currently computes precipitation probability using a rolling 8-hour window (`LOOKAHEAD_HOURS = 8L` via `PrecipProbabilityCalculator.getNext8HourPrecipProbability` and `isNext8HourPrecipPredominantlyNight`).

However, the today-column day click routing gate was just unified to use a rolling **6-hour window** (`TODAY_LOOKAHEAD_HOURS = 6L` in `DayClickResolver`), which exactly matches `ZoomStage.WIDE.window().forwardHours` (the span the graph actually displays upon opening).

Using an 8-hour window for the header causes a mismatch:
1. At 06:00, if rain is forecast at 13:00 (+7 hours), the header displays the rain chance (e.g. `60%`), but tapping today's column routes to the temperature graph because the visible 6-hour forecast window has 0% rain.
2. The user sees a prominent rain probability in the header, taps the widget or today's column, and lands on a view that has no rain in its primary window.
3. Switching the header lookahead and its corresponding night-detection window to 6 hours brings the entire application into complete coherence: header display, tap routing gate, and wide graph window all share the identical 6-hour forward span.

## Proposed Change

Update the default rolling header precipitation window from **8 hours** to **6 hours** across `:shared`, Android (`:app`), and Linux Desktop (`:desktop`).

### 1. `:shared` (`PrecipProbabilityCalculator.kt`)
- Update `DEFAULT_LOOKAHEAD_HOURS` from `8L` to `6L`.
- Add `getNext6HourPrecipProbability(...)` and `isNext6HourPrecipPredominantlyNight(...)` as the primary functions.
- Keep `getNext8HourPrecipProbability(...)` and `isNext8HourPrecipPredominantlyNight(...)` either as deprecated aliases or update all call sites to `getNext6HourPrecipProbability` / `isNext6HourPrecipPredominantlyNight`.
- Ensure `isNext6HourPrecipPredominantlyNight` iterates over `lookaheadHours * MINUTES_PER_HOUR` (6 hours = 360 minutes) instead of 8 hours.

### 2. Android (`:app`)
- In `HeaderPrecipCalculator.kt`:
  - Provide `getNext6HourPrecipProbability` and `isNext6HourPrecipPredominantlyNight` delegating to `:shared`'s 6-hour functions (and keep 8-hour aliases if needed for test compatibility).
- In `DailyHeaderResolver.kt`, `DailyGraphRenderer.kt`, `TemperatureStateResolver.kt`, `PrecipViewHandler.kt`, and `CloudCoverViewHandler.kt`:
  - Use `HeaderPrecipCalculator.getNext6HourPrecipProbability` and `HeaderPrecipCalculator.isNext6HourPrecipPredominantlyNight`.
- In `DailyViewLogic.kt`:
  - Rename/update parameter `todayNext8HourPrecipProbability` to `todayNext6HourPrecipProbability` (with backward compatibility overload/default if appropriate).

### 3. Linux Desktop (`:desktop`)
- In `Main.kt`:
  - Call `PrecipProbabilityCalculator.getNext6HourPrecipProbability` and `PrecipProbabilityCalculator.isNext6HourPrecipPredominantlyNight` in `WidgetHeader`.

### 4. Tests
- Update and extend unit tests in:
  - `PrecipProbabilityCalculatorTest.kt` (verify 6-hour delegate and horizon).
  - `PrecipProbabilityCalculatorNightTest.kt` (verify predominantly-night detection over 6h).
  - `HeaderPrecipCalculatorTest.kt` (Android tests).
  - `DesktopUiTest.kt` (desktop UI tests checking header rain chance within next 6 hours).

## Verification
- Run `./gradlew test` to ensure all tests pass in `:shared`, `:desktop`, and `:app`.
- Test on live emulator / desktop to verify the header displays rain chance for rain within the next 6 hours.
