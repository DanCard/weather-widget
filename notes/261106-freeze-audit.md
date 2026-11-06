# Freeze audit (2026-11-06)

User, 2026-11-06: "Are there other freezes? Audit the code. I asked yesterday for freezes to be
removed."

## Background

1. 2026-11-04, user: "lets get rid of the freeze rule", scope "Everywhere".
   `plans/261104-remove-hourly-pre-hour-freeze.md` removed only the hourly pre-hour freeze
   (`SettledForecastPick` = newest fetch for every hour). It explicitly left the daily `forecasts`
   cutoffs alone as "a separate mechanism, not touched", so the 06:00 low freeze survived.
2. 2026-11-06: the emulator showed Meteo's today forecast low as 57° (a Nov 4 03:32 fetch, 53 h old)
   while the hourly graph showed 46.6°. Log: `SNAPSHOT_SKIP_HINDCAST date=2026-11-06
   source=OPEN_METEO froze=low prior_low=57.0`.
3. The 06:00 low freeze was removed in commit `a920b1f4`
   (`plans/261106-remove-same-day-low-freeze.md`).

Method: grep of `freez|frozen|froze|hindcast|settle|cutoff` over `shared/`, `app/` and `desktop/`
main sources, then reading each match.

## Remaining freezes

### Holds today's live forecast

| # | Freeze | Code | Effect |
|---|---|---|---|
| 1 | Today's forecast high stops updating at 16:00 | `SameDayExtremeCutoff.HIGH_CUTOFF`, `DailyForecastWrite.plan` | A fetch after 16:00 writes its high to `hindcastHighTemp`; `highTemp` keeps the last value fetched before 16:00. The daily view can disagree with the hourly graph, which shows the newest fetch. Same kind as the low freeze that was removed. |

### Holds what past days show (`daily_history` save windows)

| # | Freeze | Code | Effect |
|---|---|---|---|
| 2 | Past-day yellow bar = last forecast fetched before the actual high/low happened | `ForecastOverlaySettle`, `DailyHistoryMaintenance.planSettledForecastOverlays` | User rule, 2026-10-04: "I don't find it useful when it is updated after." |
| 3 | Saved rain chance stops updating | `DailyHistoryFreeze.dayWindowOpen` (20:00 on the day), `nightWindowOpen` (08:00 the next day) | A later fetch never changes a past day's saved day/night rain chance. |
| 4 | Saved noon cloud and forecast bar values stop updating | `DailyHistoryFreeze.noonCloudWindowOpen` (08:00 the next day), `overlayWindowOpen` (midnight) | Same, for noon cloud and the saved forecast high/low/amount. |

### Comparisons built on a fixed earlier time

| # | Freeze | Code | Effect |
|---|---|---|---|
| 5 | "Yesterday's forecast" left bar | `PriorDayForecast`, `DailyHistoryMaintenance.planPriorForecasts` | The forecast fetched before 06:00 (low) or 16:00 (high) on the day before, saved to `priorForecast*`. A comparison by design. |
| 6 | Cloud graph's day-ago curve for past hours | `PriorDayCloudForecast`, `PriorDayBandForecast`, `CloudSeriesBuilder` | Past hours show the forecast made about 24 h earlier. A comparison by design. |
| 7 | History of Forecasts drops late points | `ForecastEvolutionCutoff.apply` | Drops points fetched after 06:00 (low) or 16:00 (high), or after the actual extreme. User rule, 2026-10-09: "remove them entirely". |

## Matches that are not freezes

None of these holds old forecast data back from a newer fetch:

- `TodayLowSettle` and `DailyDayValueResolver.isHighSettled` (17:00) choose which number the today
  label prints: observed, or `min`/`max` of observed and forecast.
- `StoredBlendGuard` / `DailyBlendWrite` keep a stored observed high/low when a recompute has fewer
  stations (data quality of actuals).
- `SameDayExtremeCutoff.keptTodayLow` and `DailyHourlySummaries.carryForward` only fill in when a
  fetch sends nothing.
- `HistorySnapshotRetention` rules 3 and 4 keep rows for the prior-day band and rain accuracy. They
  decide which rows are pruned, not what is displayed.
- `SettledForecastPick`: newest fetch since 2026-11-04 (no freeze left).

## Recommendation

1. Remove #1 (16:00 high freeze). It is the same kind of freeze as the low freeze, and it is the
   only remaining one that holds today's live forecast.
2. #2–#7 exist to compare forecast against actual. Without them, past days would show forecasts
   fetched after the fact, which sit close to the actuals, so the comparison would mean nothing.
   #2 and #7 are explicit user rules (2026-10-04, 2026-10-09). Keep them unless the user overrides.

Decision pending.
