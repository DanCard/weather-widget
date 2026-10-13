# Forecast History: today's settled low on the low evolution graph

User, 2026-10-13: "When the low is settled for the day, and viewing History of forecasts for current
day, can we get the actual low line on low forecast evolution."

## Rule (`:shared` `ForecastHistoryViewLogic.locationActuals`, both platforms)

| Day | Location actual lines | Hindcast trim times |
|---|---|---|
| Past | high + low (daily_history computed) | `computedHighAt` / `computedLowAt` |
| Today | low only, once `TodayLowSettle.decide(...).settled` | low: `computedLowAt`; high: fixed cutoffs only |
| Future | none | fixed cutoffs only |

- Same rule and inputs as the widget's today column (`DailyTodayResolver` / desktop
  `DesktopDailyForecastModel`): source + `GENERIC_GAP` hourly for today drives the 3 h look-ahead.
  The line appears exactly when the column's label stops printing the forecast stand-in.
- A settled low's time is final, so low points fetched after it (hindcasts) drop, as on a past day.
- API actual: still past days only (the provider's today low is not final). Error mode still needs
  past-day API actuals. The actuals footer card still needs a full high/low pair, so it stays hidden
  today; the legend shows "Location actual" when the line is drawn.

## Code
- `ForecastHistoryViewLogic.LocationActuals`, `locationActuals`, `needsTodayHourly`.
- Android `ForecastHistoryActivity.loadData` loads today's hourly (`getHourlyForecastsForSources`);
  `displayData` takes `LocationActuals`. The raw proximity-box read is wrapped in
  `GraphDataLoader.unifyToNearestSite` (allowlisted in `HourlyProximityQueryAllowlistTest`), so a
  stale GPS fragment's hours never drive the settle look-ahead.
- Desktop `ForecastHistoryWindow.loadHistory` loads today's daily_history row + hourly; legend gains
  `hasLocationActual`. Hourly collapsed with `LocationMatch.selectNearestSite` (same hazard).

## Tests
- `shared` `ForecastHistoryLocationActualsTest`: past, today after 09:00, before cutoff, cutoff–09:00
  falling vs rising forecast, no observed low, future.
