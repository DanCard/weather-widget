# Google daily low: take the morning from Google's own hourly, not the shifted day minimum

Status: **implemented** (2026-10-21, approved as proposed; no repair of stored rows).

## Report

Emulator (Foldable), Google, Wed 2026-10-21: daily view low **51°**, hourly temperature graph low
**57.5°** (dashed forecast, ~04:00). Official stations 00:00–09:00: **57.4°**. The hourly is right.

## Root cause

`GoogleWeatherApi.lowsFiledUnderTheirMorning` (plans/261010-google-daily-low-filed-under-the-morning-it-ends.md)
gives day D the `minTemperature` of Google day D−1, because Google's day runs **07:00 → 07:00** and
its minimum is *usually* the next morning. It is the minimum of the **whole** window, so it also
includes D−1's own 07:00 hour. After a cold morning followed by a mild night, that 07:00 is the
minimum, and D−1's morning low is filed under D.

Same fetch (Tue 2026-10-20 17:31), Google hourly: Tue 07:00 = 50.9°, overnight to Wed 04:00 min 57.5°;
Google day Tue `minTemperature` = 51 → filed under Wed. Every later fetch kept it: the 05:57 Wed fetch
has no low for today (by design), so the partial-today rule kept the 17:31 row's 51.

Stored newest rows, 2026-10-11 … 24 (this device):

| | days |
|---|---|
| shown low ≈ hourly 00:00–09:00 min (±0.8) | 12 |
| wrong — previous day's 07:00 colder than the night | **10/16** (51 vs 54.0; prev 07:00 49.7), **10/21** (51 vs 57.5; prev 07:00 50.9) |

The 261010 table only had cooling nights, so it never showed this case. It happens on warming
mornings, and in autumn sunrise is near 07:00, so Google's window starts at about the coldest hour.

Google's `nighttimeForecast` has no temperature, so the day reply alone can't separate the two
mornings. The hourly download can: it arrives in the same fetch (to 72 h, or 192 h on LONG).

## Fix (`:shared`, both platforms)

1. `GoogleWeatherApi` parse: day D's low = **min of the fetch's hourly temperatures in D 00:00–09:00
   local**, when the download covers that window completely (`DailyHourlySummaries.windowCovered`,
   same zone rule as the other daily-from-hourly summaries).
2. Otherwise (days past the hourly reach, or today once the morning has passed) keep the current
   rule: Google day D−1's `minTemperature`, and none for today, which the partial-today rule completes.
3. Log once per fetch: `GOOGLE_DAILY_LOW from_hourly=N shifted=N`.

Window: 00:00–09:00 versus the night 19:00 D−1 → 09:00 D gave identical values on all 16 days here.
00:00–09:00 is simpler and is "the day's own morning", the 261010 intent. Midnight-to-midnight (like
Open-Meteo and the observed actuals) would differ only when the evening is colder than the morning
(10/23: 54.1 vs 55.5); I'm not proposing it, because the high/low pairing then changes meaning for
Google alone.

## Not proposed: repairing stored rows

The stored live hourly is the *newest* fetch per hour, not the hourly of the batch that wrote a row,
so a repair would rewrite past forecasts with later knowledge. Only 2 days in 2 weeks are affected,
and both age out (30 days). **Consequence:** today (10/21) keeps showing 51° until tomorrow, because no
fetch after 09:00 covers this morning. Tomorrow's low (Thu 22, 57°) is already right.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | Recorded shape of this case: day Tue min 51, hourly Tue 07:00 50.9, Wed 00–09 min 57.5 → Wed low 57.5 | unit (`GoogleWeatherApiTest`) |
| 2 | Cooling night (261010 fixture): hourly-derived low = shifted value ± rounding (no regression) | unit |
| 3 | Day past the hourly reach → shifted `minTemperature` | unit |
| 4 | Window partly covered (fetch at 05:57: hours 05–09 only) → no hourly low; today → none (partial-today completes) | unit |
| 5 | Full fetch → stored rows (Android repository → Room, Robolectric; day minima forced to 40°): covered mornings store their rounded hourly minimum, later days 40 | integration |

## On-device verification

Emulator and desktop after a Google fetch: each covered day's daily low equals the hourly graph's
00:00–09:00 minimum; `GOOGLE_DAILY_LOW` logged.

## Changed during implementation

- **Forecast hours only, not elapsed `history/hours`.** The existing fetch test's history covers today
  00:00–09:00 and gave today a low of 61.7 — what happened, not a forecast (the app keeps those in
  `hindcast*`). Forecast hours start at the current hour, so today still gets no low from a fetch.
- Stored lows are whole degrees (`ForecastTempRounding.forStorage`, every day but today), so a
  57.5° morning stores 58°. By design; the integration test compares against the rounded value.
- The recorded fixtures' day minima already equal their mornings, so the integration test replaces
  every `minTemperature` with 40° (`GoogleWeatherFixtures.engine(daysTransform = …)`) to tell the
  rules apart.

## Verified

- Unit (`GoogleWeatherApiTest`, 5 new): 4 fail without the fix (the bug case gives 51 back); the
  cooling-night case passes either way by design.
- Integration (`GoogleWeatherFetchIntegrationTest`, real parse → coordinator → Room): fails without
  the fix (`expected 59.0 but was 40.0`).
- Suites: shared 2,390, desktop 641, app 2,629 — 0 failures.
- Emulator (Foldable) after a LONG Refresh: `GOOGLE_DAILY_LOW from_hourly=7 shifted=2`; Oct 22–28
  stored lows = rounded 00:00–09:00 hourly minimum; Oct 29 (6 of 9 hours) shifted; today 51 kept.
- Desktop rebuilt and restarted; picks it up on its next scheduled Google fetch.
