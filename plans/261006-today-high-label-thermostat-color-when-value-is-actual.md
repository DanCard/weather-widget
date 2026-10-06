# Today's high label: thermostat color whenever the printed value is the actual

Companion to `261006-today-low-label-thermostat-color-when-value-is-actual.md`. That change moved
the low to a "where did the printed number come from?" rule; the high kept the clock rule.

## Report (user, 2026-10-06, emulator)

Daily view, today column, 4:24 pm: high label `84.5°` drawn yellow/cream, low `60.8°` pink.
Render log: `trueHigh=84.52346` (observed peak), `obsHigh=84.22` (current temp), `fHigh=81.2`.
The printed high `max(84.22, 81.2, 84.52) = 84.52` **is** the observed actual — the day ran
3° hotter than forecast — but the label wears the forecast color until 5 pm.

## Cause

Label color = `DailyDayValueResolver.isHighTrackingActual`, true only when
`nowHour >= ACTUAL_HIGH_CUTOFF_HOUR (17)` and an actual exists. Before 5 pm the value is
`max(solid, forecast, ghost)`; when the observed peak already beat the forecast, that max is the
actual, yet the color says "forecast".

## Fix

1. `:shared` `DailyDayValueResolver.isHighLabelActual(isToday, printedHigh, solidHigh, ghostHigh)`:
   `isToday && printedHigh == max(solidHigh, ghostHigh)`. Exact `==` is safe — `effectiveHighForLabel`
   returns one of its inputs unchanged via `max`. Tie with the forecast counts as actual.
   "Actual" = `max(solid, ghost)`, the same definition `isHighTrackingActual` and the settled
   branch of `effectiveHighForLabel` already use.
2. **Color only.** `isHighTrackingActual` stays as the 5 pm gate for the dual (actual + forecast)
   high label; a hot afternoon does not start printing the forecast label before 5 pm.
3. Android: `HighLabelPlan.todayHighIsActual`, computed in `DailyHighLabelPlanner` from `effective`;
   `DailyBarRenderer` single-label recolor reads it instead of `todayHighSettled`.
4. Desktop `DailyForecastGraph.kt` single-high color: `isHighLabelActual(…, printedHigh = singleHigh, …)`.
5. Tests (`DailyDayValueResolverTest`), each run through the real `effectiveHighForLabel`:
   before 5 pm observed hotter than forecast → actual (the report's numbers); before 5 pm forecast
   hotter → not; after 5 pm → actual; tie → actual; no observations → never; non-today → false.

## Verification

- `./gradlew :shared:test --tests "*DailyDayValueResolverTest*"`, `testDebugUnitTest`, `:desktop:test`
- Emulator: `installDebug`, screenshot — today's high pink before 5 pm.
- Desktop: `scripts/buildStart-desktop.sh`.
