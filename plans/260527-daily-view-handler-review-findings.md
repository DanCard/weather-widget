# Implement DailyViewHandler Review Findings

## Summary
Fix two `DailyViewHandler` behavior bugs:

1. Daily navigation should only consider dates renderable by the selected display source or `GENERIC_GAP`.
2. Text-mode missing-data refresh checks should use the dates actually visible in text mode, not every loaded date.

## Key Changes
- In `DailyViewHandler.updateWidget`, replace the current `availableDates` calculation with source-scoped dates:
  - Include `weatherList` rows where `source == displaySource.id` or `source == GENERIC_GAP`.
  - Include `dailyActuals.keys` for the selected source.
  - Keep existing `setupNavigationButtons(...)` behavior unchanged after this input is corrected.
- Move text-mode missing-data refresh evaluation until after text-mode rendering returns visible day data.
  - Pass only `visibleDaysInfo.map { it.date }.toSet()` into `computeMissingDataRefreshes`.
  - Preserve existing today actuals and today snapshot refresh behavior.
  - Do not trigger `actuals_history` for past dates that are merely loaded but not visible.

## Tests
- Add/update a Robolectric test in `DailyViewHandlerTest` for navigation source scoping.
- Add/update a Robolectric test for text-mode refresh scoping.
- Run focused verification:
  - `./gradlew testDebugUnitTest --tests '*DailyViewHandlerTest*' --tests '*DailyViewHandlerUnitTest*'`

## Assumptions
- Keep fixes scoped to `DailyViewHandler`; no DAO/query changes are needed.
- Keep `computeMissingDataRefreshes` semantics unchanged: callers are responsible for passing true visible dates.
- `GENERIC_GAP` remains a valid render/navigation fallback for the selected source.
