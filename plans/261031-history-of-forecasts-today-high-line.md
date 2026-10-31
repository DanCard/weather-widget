# History of Forecasts: today's high actual line at 5pm

## Goal

On today's "History of Forecasts" graph, draw the Location actual line for the high from 17:00, the
moment the widget's today column switches its high to the observed value. This mirrors the low line
(user, 2026-10-13: the line appears when the column stops printing the forecast).

Every rule lives in `:shared`. Android and desktop only read rows, map them to a shared value, and
draw the result.

## Evidence (desktop DB, Mountain View, read-only, 2026-10-31 13:09)

1. `ForecastHistoryViewLogic.locationActuals` hard-codes `high = null` for today.
2. The widget's rule already exists: `DailyDayValueResolver.ACTUAL_HIGH_CUTOFF_HOUR = 17`.
3. Timing: over Oct 16-30 (15 days), the day's hottest site reading came between 12:30 and 15:50.
   One site and two autumn weeks only, so this supports the rule but does not prove it for summer.
4. **Today's per-source rows lag.** A source row only advances when that source's actuals run:

   | Source | computedHighTemp | computedHighAt | updatedAt |
   | --- | --- | --- | --- |
   | Google, NWS, Open-Meteo, Silurian, Tomorrow.io | 64.3-64.4 | 11:53-11:55 | 13:02 |
   | OpenWeatherMap | 52.7 | 00:00 | 08:28 |
   | WeatherAPI | 52.8 | 00:00 | 08:28 |

   Those two rows stopped at 08:28, when midnight was still the warmest reading of the day. If they
   were still stale at 17:00, the line would be about 12° low on those two sources. They can agree
   once settled: all seven show 68.8 for Oct 30.
5. Platforms pick the row differently. Android sorts by distance and takes the source's row; desktop
   takes `firstOrNull`, so a stale fragment can win on desktop only.

## Shared-code audit

| Rule | Today | Plan |
| --- | --- | --- |
| Is the high settled (hour >= 17) | Written twice in `DailyDayValueResolver` | One predicate, also used by History |
| Which `daily_history` row supplies the actual | Separate logic per platform | Shared pick inside `locationActuals` |
| Footer card visibility and contents | Android and desktop disagree | Shared `ActualsDisplay` |
| Legend visibility | Separate logic per platform | Shared `ActualsDisplay` |
| Error graph availability | Desktop only | Shared `ActualsDisplay`, both platforms |
| `shouldLaunchTemperature`, `shouldShowTemperatureButton`, `resolveButtonMode` | Android companion copies the shared functions | Delete the copies, call shared |
| Decision logging | None for History | One shared on-change log line |

## Changes

### `:shared`

1. `DailyDayValueResolver`: add `isHighSettled(nowHour: Int?)`. `effectiveHighForLabel` and
   `isHighTrackingActual` call it instead of repeating the comparison.
2. `ForecastHistoryViewLogic`: add `LocationActualRow(sourceId, locationLat, locationLon, high, low,
   highAt, lowAt)`, following the `ForecastEvolutionCutoff.Row` pattern.
3. `locationActuals`: replace the `computed*` parameters with `rows` and `sourceId`.
   1. Collapse `rows` to the nearest site with `LocationMatch.selectNearestSite`.
   2. Past day: the source's row, both extremes, as now.
   3. Today, low: the source's row and `TodayLowSettle`, unchanged.
   4. Today, high: when `isHighSettled(now.hour)`, the **hottest** row at that site, with that row's
      `highAt`. A running maximum only grows as observations arrive, so a lagging row can't win.
      When the source's own row has no high but another row at the site does, the line still shows.
   5. Return `highReachedAt` with the high, so `ForecastEvolutionCutoff.points` drops today's later
      high hindcasts, as on past days.
4. Add `ActualsDisplay` and `actualsDisplay(isPast, apiHigh, apiLow, actuals)`:
   1. `showApiActualLegend`: past day.
   2. `showLocationActualLegend`: past day, or any location actual is drawn.
   3. `apiFooter` and `locationFooter`: the high/low pair on a past day when both exist, else null.
      Today's values appear only as graph labels.
   4. `showFooterCard`: either footer is present.
   5. `errorGraphAvailable`: past day with a complete pair. Today stays out, because the desktop
      toggle is reachable there and today would otherwise start drawing an error graph at 5pm.
5. Log `HISTORY_TODAY_ACTUALS high=… highAt=… highRowSource=… low=…` with `Log.i`, once per change of
   decision, using the `TodayLowSettle.logOnChange` pattern.

### `:app`

1. `ForecastHistoryActivity.loadData`: map rows to `LocationActualRow` and remove the local sort and
   find.
2. `displayData`: drive the legend, footer card, and error branch from `ActualsDisplay`.
3. Delete the companion copies of the shared functions and update their callers and tests.

### `:desktop`

1. `ForecastHistoryWindow`: map rows to `LocationActualRow` and remove `sourceHistory`.
2. `Content` and `Legend`: drive the legend, footer card, and error message from `ActualsDisplay`.

### Docs

1. `arch/daily-history-extremes.md`: add when History draws today's actuals and why today's high
   reads the hottest row at the site.
2. Update the comments in `ForecastHistoryViewLogic`, `ForecastHistoryActivity`, and
   `ForecastHistoryWindow` that say today's high is never shown.

## Tests

Extend `ForecastHistoryLocationActualsTest` (`:shared`), except where noted. Each case below fails on
the old code.

1. 16:59: no high line. 17:00: high line with its reached time.
2. Today's real rows: a 52.7 @ 00:00 source row next to a 64.4 @ 11:55 row gives 64.4 @ 11:55.
3. The source row has no high but a site row does: line shows.
4. A row from another site never wins, even when hotter.
5. Low is independent: at 10:00 the low shows and the high doesn't; the low's value and time still
   come from the source's row at 17:00.
6. `ForecastEvolutionCutoffTest`: after 17:00, a high fetched after `highReachedAt` is dropped.
7. New `ForecastHistoryActualsDisplayTest`: legend, footer, and error flags for past, today before
   5pm, today after 5pm, and future.
8. `DailyDayValueResolver` tests stay green on the extracted predicate.
9. `ForecastHistoryActualsVisibilityTest` stays green; the footer rule also keeps it from failing
   when run after 5pm.

## Verification

1. `./scripts/unit-tests.sh`, then `./scripts/emulator-tests.sh -c` on any instrumented test touched.
2. Rebuild and restart desktop. Open today's History on OpenWeatherMap after 17:00, then confirm the
   line matches a fresh source, the footer is absent, and `HISTORY_TODAY_ACTUALS` is logged.
3. Check the same screen on an emulator, screenshot and logcat. Temporarily setting the emulator
   clock past 17:00 is fine if the real time is earlier. A past day must look unchanged on both.

## Decisions to confirm

1. **Hottest row across sources.** Memory `no_cross_source_fallback` bans borrowing another
   provider's data. Location actual is one site-level blend stored per source, not provider data,
   and the source rows match once settled, so this plan treats the pick as allowed. The API actual
   line is untouched.
2. **Low stays on the source's row.** It lags too (47.6 vs 47.9 today), but a minimum can't reject a
   stale row: the stale 47.6 is lower than the fresh value. Fixing that is out of scope.

## Implementation notes

0. **Superseded (user, 2026-10-31): today's high uses the source's own row.** The hottest-row pick in
   Changes 3.4, Evidence 4, Tests 2-3, and Decision 1 was dropped. The lagging rows belong to inactive
   sources (desktop `visibleSources` excludes OpenWeatherMap and WeatherAPI), and History only shows
   visible sources. A hottest pick also let one bad row win (Oct 7: Tomorrow.io 90.0° vs 64.3°).
1. `errorGraphAvailable` means a past day with either complete pair, desktop's old rule. Android used
   to require the API pair, so a past day with only location actuals now gets its error graph.
2. The Android companion also duplicated `normalizeSource`, `resolveActualLookupMode`, and the three
   enums; those copies are gone too.

## Out of scope

- Past-day rows where the source lagged and never caught up (Oct 29: 66.2 @ 18:15 vs 67.7 @ 14:47).
- Sep 26-Oct 15 rows with highs reached at 20:00-23:55 at implausibly cool values, and the Oct 7
  90.0° row. Cause unknown; they deserve their own investigation.
- Why lagging source rows are not refreshed along with the others during the day.
