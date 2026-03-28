# Plan: Recenter Daily Day Clicks on Noon

## Summary

Change daily forecast day-click behavior so selecting a day opens the hourly graph centered on that day's noon instead of the current 8:00 AM anchor.

Apply the same behavior in both places that share the helper:

- Daily widget day taps
- Forecast History screen `Hourly` button

## Implementation

- Update `DayClickHelper.calculatePrecipitationOffset()` so future days target `targetDay.atTime(12, 0)` instead of `8:00`.
- Keep today's behavior unchanged: return `0` so the graph still centers on the current hour.
- Keep past-day behavior unchanged: past days still open `ForecastHistoryActivity`.
- Leave graph width, zoom defaults, navigation jumps, and hourly rendering logic unchanged.
- Preserve the existing shared call sites in `DailyViewHandler` and `ForecastHistoryActivity` so both entry paths stay aligned through one helper.

## Tests

- Update unit tests for `DayClickHelper` to reflect the noon anchor.
- Keep coverage that today still returns offset `0`.
- Keep or add coverage that `DailyViewHandler.buildDayClickIntent()` emits the shared helper's hourly offset for future-day taps.

## Assumptions

- "Centers around noon" means an exact local-time anchor of `12:00 PM`.
- The change should apply to both the widget's daily day taps and the Forecast History screen's `Hourly` button.
