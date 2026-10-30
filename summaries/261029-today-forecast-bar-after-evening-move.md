# Today's forecast bar drawn short after an evening move — summary (2026-10-29)

No plan file (small, single-rule fix).

## User prompts

1. "fold-4 : today column, right column seems drawn to short"
2. "yes" (approved the proposed fix)
3. "write session summary to summaries/"

## Evidence

- Fold screenshot (inner display, 22:00): Thu column's right bar stopped at ~62.5° under a 67.8°
  label, while the left (prior-forecast) bar reached the label.
- Logcat, widget 345 (Open-Meteo and NWS):
  `TODAY_BAR_DEBUG … fHigh=null fLow=null trueHigh=67.78 sHigh=68.0`. The `today_forecast` bar's
  `topY=155.7` maps to ~62.5° (the observed range), not a forecast.
- `app_logs`: `GPS_RESAMPLE outcome=location_moved trigger=screen_on lat=37.4149 lon=-122.0869`
  at 18:51:59 (~1 km from the previous site at `37.424,-122.088`).
- `forecasts`, today, Fold backup `20261029_220100`:

  | Source | Site | Fetched | High / Low |
  |---|---|---|---|
  | OPEN_METEO | 37.424,-122.088 | 15:21 | 67.0 / 48.2 |
  | OPEN_METEO | 37.415,-122.087 | 18:52, 21:56 | null / null |
  | NWS | 37.424,-122.088 | 16:42 | 70.0 / 51.0 |
  | NWS | 37.415,-122.087 | 18:52, 21:56 | null / null |

## Root cause

Three rules that each work as designed combined to lose today's forecast:

1. `SameDayExtremeCutoff.filter` nulls today's high after 16:00 and today's low after 06:00, so any
   post-16:00 fetch stores an empty today row.
2. `keptTodayLow` carries a prior low forward only at the same site; the new site had no prior.
3. `DailyTodayResolver` (and desktop's equivalent) looked for a complete replacement only at the batch
   row's `LocationMatch.sameSite` (0.002°). The old site is 0.009° away, so its 15:21 row was never
   considered. Hourly fallback had nothing either (`hourlyRows=0`).

`DailyBarRenderer.drawTodayTripleBar` then drew `dashedLineHigh ?: solidLineHigh`, i.e. the observed
values.

## What changed

- **`:shared` `PartialForecastDays.todayRowNearestSite`**: runs `todayRow` at the anchor site first,
  including one-sided rows (hourly fills the other side). Only when the anchor site has no temperature
  at all for today does it take the newest complete row from the nearest other site in the box,
  within `COMPLETE_REPLACEMENT_MAX_AGE_MS` (24 h) of the newest stored row. Same-site-first keeps the
  2026-07-30 cross-town fix (another town's 84°) closed.
- **Android** `DailyTodayResolver.resolveTodayRow`: uses the new rule when a batch row exists; the
  no-batch path (Silurian evenings) is unchanged.
- **Desktop** `DesktopWeatherDao.getDailyForecasts`: the today candidate query now reads
  `locationLat`/`locationLon` and anchors on the newest fetch's site. Previously it took the newest
  complete row anywhere in the ±0.1° box; it is now same-site first, matching Android.

## Tests

- `PartialForecastDaysTest`: post-cutoff move borrows the old site's row (incident numbers; a newer
  but farther site loses); the anchor site's own values win, including one-sided; another site's row
  older than 24 h is not borrowed.
- `DailyTodayResolverTodayRowTest`: empty batch row after a move → nearest site's complete row of the
  display source. Verified to fail without the fix (5 tests, 1 failed), pass with it.
- `./scripts/unit-tests.sh`: 6392 passed.

## Runtime verification

- `installDebug` on Fold and Pixel; desktop rebuilt and restarted (`buildStart-desktop.sh`, process up).
- Fold after install:
  `today weather incomplete (high=null low=null), using stored row … (high=67.0 low=48.2)` and
  `TODAY_BAR_DEBUG widget=345 … fHigh=67.0 fLow=48.2`.
- Screenshot: the right bar now reaches 67° with a "67°" forecast label; its bottom sits at ~48°.

## Not done

- No `arch/` doc describes the today-row rule; the KDoc on `todayRowNearestSite` is the documentation.
- Not committed.
