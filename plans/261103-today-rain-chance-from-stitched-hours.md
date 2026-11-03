# Today's daily rain % from the same stitched hours as the hourly graph

Date: 2026-11-03. Status: proposed.

## User requests

1. "Gemini flash tried to fix an issue on desktop where tomorrow.io rain chance was not appearing on
   today column. The fix didn't work. Suggest reverting that work and starting over."
   — Reverted (uncommitted refactor in `DailyViewLogic`, `DesktopDailyForecastModel`,
   `DesktopWeatherDao`, `ForecastTypes`, `ForecastEntity`, new `DailyForecastPreparer`/`DailyTodayResolver`).
2. "The hourly data for tomorrow.io shows a peak rain chance of 45%. That works on Fold-4. Doesn't
   work on Desktop. Unify the code. Maximize use of shared code."

Superseded plan (historical): `plans/261103-unify-desktop-android-today-rain-chance.md`.

## Evidence (2026-11-03, ~11:15 PST)

Tomorrow.io rows for today, `forecasts`:

| Platform | Newest fetch | `hourlyDayPrecipMax` | peak | Daily label |
|---|---|---|---|---|
| Fold 4 (`RFCT70G352Y`) | 08:56:54 | 45 | 10am | 45% |
| Desktop | 10:02:45 | **0** | 8am | none |

Desktop `hourly_forecasts`, Tomorrow.io, every stored run for the 10am and 11am hours:

```
10am: 0@15:01 0@17:07 0@19:18 30@22:24 30@22:26 0@03:40 45@08:57 0@10:02
11am: 30@15:01 30@17:07 0@19:18 0@22:24 0@22:26 20@03:40 38@08:57 0@10:02
```

- The hourly graph shows 45% at 10am: `HourlyForecastStitcher` (shared, used by both platforms'
  graphs and passed to both platforms' daily columns) freezes a started hour at the last fetch made
  at or before it — the 08:57 run.
- The daily label uses the row's `hourlyDayPrecipMax`, computed at fetch time from the 10:02
  download alone, where the in-progress 10am hour had already dropped to 0%.
- `DailyHourlySummaries.liveDayNight` (shared) prefers that stored max over the stitched hours, so
  the label disagrees with the graph shown right above it.
- Fold 4 shows 45% only because it has not fetched Tomorrow.io since 08:56. Both platforms already run
  identical shared code (`DailyColumnBuilder` → `DailyRainLabels.resolveDailyLabelPrecipAtSite` →
  `liveDayNight`); the next Fold fetch would blank its label the same way.
- Live API at 11:15: 1d = 20%, 10am = 20%: the provider keeps revising started hours.

## Root cause

For **today**, the daily rain % reads a per-fetch summary, while the hourly graph reads the stitched
per-hour forecast that freezes elapsed and in-progress hours at their pre-hour value. A fetch made
during an hour rewrites the summary with the provider's in-hour value, so the daily % drops below
what the graph still shows. This applies to every source and both platforms.

## Fix (`:shared` only)

`DailyHourlySummaries.liveDayNight`: for **today**, when the stitched display-source rows cover the
8am–8pm window (`windowCovered`), take the day max and peak hour from those rows
(`DailyRainLabels.periodMaxima`) ahead of the stored row values. Treat the night window the same way.
Otherwise, and for every future day, behavior stays as it is: stored row first, then hours.

- Today's hours are always inside the 72 h routine store, so the docstring's reason for preferring
  the row (hours past 72 h that routine fetches never rewrite) doesn't apply to them.
- The caller passes `today` (or `isToday`) through `resolveDailyLabelPrecipAtSite` from
  `DailyColumnBuilder`, which already knows `today`. No Android or desktop code changes; both
  platforms pick it up through the existing shared path, and the daily icon follows because it reads
  the same `ResolvedDailyPrecip`.
- One `Log.v` line from the shared resolver when the stitched value differs from the stored one
  (`TODAY_RAIN_STITCHED stored=0@08 stitched=45@10 src=…`), so later reports can be diagnosed from
  logcat or desktop stdout.
- Update the `liveDayNight` KDoc to state the today rule.

## Tests

1. `DailyHourlySummariesTest` (pure JVM):
   - Today, stored max 0 (post-start run), stitched hours with 45 at 10am → 45 @ 10am.
   - Today, stitched hours not covering 8am–8pm → stored values (unchanged behavior).
   - Tomorrow, stored 30 vs hours 50 → 30 (future days unchanged).
   - Each case fails on the current code where it should.
2. `DailyRainLabelsTest`: `resolveDailyLabelPrecipAtSite` for today with the 10:02-shaped fixture → 45%.
3. Parity: `DesktopDailyParityTest` / `AndroidDailyParityTest` and `scripts/daily_parity_diff.py`, with
   0 differences.
4. `./scripts/unit-tests.sh`.

## Runtime verification

1. `./gradlew :desktop:createDistributable`, restart desktop, and confirm today's Tomorrow.io column
   shows 45% with the 10am peak, matching the hourly graph.
2. `./gradlew installDebug` to the emulator and Fold 4, and confirm today's Tomorrow.io column is
   unchanged (45%) after a forced Tomorrow.io refresh.
