# Google's daily low is filed under the morning it ends, like NWS

Status: **implemented** (2026-10-10). History decision left to me by the user: (b), frozen `daily_history` lows not re-frozen.

## Problem

Desktop, Google, daily view: today's low reads 49°, the hourly view's low for today 56.4°.

## Root cause

Google's `forecast/days` "day" runs **07:00 → 07:00** local (raw response, 2026-10-10: Oct 10's
`interval` is `14:00Z → 14:00Z`; daytime 07:00–19:00, nighttime 19:00–07:00). Its `minTemperature` is
therefore the low of the night *after* the date — almost always the next morning. `GoogleWeatherApi
.parseDay` files it under `displayDate`, the date the day *starts*:

| Row | Google low | Next morning's hourly min | Same day's hourly min |
|---|---|---|---|
| Sat 10 | 49 | Sun 05:00 49.2 | 54.1 (56.4 at 07:00) |
| Sun 11 | 50 | Mon 50.0 | 49.2 |
| Mon 12 | 48 | Tue 47.8 | 50.0 |
| Tue 13 | 47 | Wed 47.2 | 47.8 |

Every other source pairs a calendar day's afternoon high with its **morning** low: NWS files a night
period's low under the date the night ends (`NwsDailyMapper`), Open-Meteo's daily min is midnight to
midnight, and observed actuals are midnight to midnight. So Google's lows sit one night late:
the bar, the "yesterday's forecast" left bar (`PriorDayForecast`), and accuracy stats (Google's low
error and bias) all compare the wrong night. Both platforms (`:shared` parser).

## Fix

1. **`:shared` `GoogleWeatherApi.parseDay` → rows built in a second pass**: day D's row takes
   `maxTemperature` of Google day D and `minTemperature` of Google day D−1 (the night ending on D's
   morning). The first day in the response (today) gets no low from this fetch; the last Google
   day's min (the morning after the horizon) is dropped — no low-only phantom row.
   Precip is unchanged: Google's nighttime (19:00 D → 07:00 D+1) already sits under D, matching the
   app's 20:00–08:00 night window filed under D.
2. **Today's low** then comes from yesterday's fetches through the existing shared partial-today rule
   (`PartialForecastDays.todayRow`, both platforms): a batch row missing its low is completed from
   the newest stored row for today that has one. After 06:00 the low is frozen anyway
   (`SameDayExtremeCutoff`). Hourly-derived fallback not added.
3. **One-time repair of stored Google rows** (both platforms, marker so it runs once): within each
   `batchFetchedAt` batch at each site, set row D's `lowTemp` to row D−1's original `lowTemp`
   (null when the batch has no D−1 row). Without it, the first day after the update would complete
   today from yesterday's shifted rows (49° again), and the left bar / accuracy would keep comparing
   the wrong night for 30 days. `hindcastLowTemp` gets the same shift.
4. Log: `GOOGLE_LOW_SHIFT_REPAIR rows=N batches=N` once.

## Open question

`daily_history` holds **frozen** Google `forecastLowTemp` / `priorForecastLowTemp` for past days
(frozen while each day was live, from the shifted rows). They do not recompute. Options:
- **(a) Re-freeze** past Google days from the repaired `forecasts` rows (still present for 30 days)
  — accuracy stats correct for the last 30 days; older history stays shifted.
- **(b) Leave them** — Google tracking is under a week old on most devices, so little history is
  affected; it corrects itself as days roll over.

Recommendation: (b) unless you want the stats right now — (a) touches the freeze rules, which have
their own window gates.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | Parse a recorded 3-day response (fixture from the raw 2026-10-10 reply): Oct 10 high 68.7 low null; Oct 11 high 69.3 low 49.2; Oct 12 high 69.9 low 50.1; no Oct 13 row | unit (:shared) |
| 2 | Precip day/night stay on their displayDate | unit |
| 3 | Missing day in the middle of the response: next day gets no low (no cross-gap borrow) | unit |
| 4 | Repair: one batch of 3 rows → lows shifted down a day, first row null; two batches independent; second run changes nothing | integration (desktop DAO, real SQLite) |
| 5 | Repair on Android (Room, Robolectric) — same cases as 4 | integration |
| 6 | Today's row after the fix: batch row has no low → partial-today rule completes it from yesterday's stored row (desktop `getDailyForecasts`; Android `DailyViewLogic` today path) | integration |
| 7 | Non-Google rows untouched by the repair | integration |
| 8 | Room migration is not needed (no schema change); repair marker in prefs/config — verify it runs once | integration |

## On-device verification

Desktop and Pixel after a Google fetch: daily low for each day equals the **same** morning's hourly
minimum (± provider rounding); today's low equals yesterday's stored value, not tonight's;
`GOOGLE_LOW_SHIFT_REPAIR` logged once.

## Changed during implementation

- **Repair as a data migration** (Room 78→79 / desktop 31→32, `GOOGLE_LOW_SHIFT_SQL`) instead of a
  prefs/config marker: a version bump runs exactly once on both platforms. No `GOOGLE_LOW_SHIFT_REPAIR`
  log line (migrations do not log).
- **Today's low kept at write time, not left to the reader.** `PartialForecastDays.todayRow` swaps in a
  whole older complete row, high included, so every Google fetch would have shown a high up to 24 h
  old. Both writers now keep today's low when a fetch sends none: the newest stored row for today
  **that has a low**, fetched within 24 h (`SameDayExtremeCutoff.keptTodayLow`). First version took
  "the latest row's low" — on the Pixel the latest row was the repaired batch's own first day (no low),
  so nothing was kept; fixed and covered by a regression test on both platforms.
- This also applies to NWS's evening "no low for today": the evening row now stands complete with its
  newer high instead of being replaced by the morning row (`DesktopWeatherDaoTest` updated).

## Verification

- `:shared` + `:desktop` suites green; `:app` 2463 unit tests green; `WeatherDatabaseMigrationTest`
  25/25 on the emulator (incl. `migrate78To79`). New: parser tests on the raw 2026-10-10 reply
  (`google-weather/days-3-2026-10-10.json`), `keptTodayLow` unit tests, `DesktopGoogleLowShiftTest` (4),
  `ForecastSnapshotHindcastCutoffTest` (+3).
- Desktop dry run of the SQL on a copy, then live v32: newest Google batch lows Sun 49, Mon 50, Tue 48,
  Wed 47 (were 50, 48, 47, 47); popup shows them.
- Pixel v79, 11:41 forced refresh: today 68.7 / 58.2 (this fetch's high, yesterday's forecast for this
  morning — Google's hourly now says 56.4 for 07:00, a revision of the same night); Sun 49, Mon 50.
  Widget screenshot matches.
