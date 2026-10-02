# One NWS daily pipeline; partial days handled the same on both platforms

## Problem

NWS daily forecasts are built by two pipelines over the same `:shared` steps:

| | Android `NwsForecastMapper.fetchFromNws` | Desktop `NwsDailyMapper.buildDailyForecasts` |
|---|---|---|
| Order | hourly precip/conditions, **gridpoint first**, then periods fill nulls | periods first, gridpoint fills nulls |
| Hourly divergence check (`detectHourlyDivergence`) | yes | no |
| Day with no low (NWS's evening drop for today) | stores `null` | stores **`low = high`** |
| Day with no high (NWS's terminal low-only day) | kept (`NWS_PARTIAL_DAY_KEEP`) | dropped |
| Condition / precip | hourly midday condition, hourly precip, then periods | first daytime period's text, max period chance |

The reason is the model: desktop's `DailyForecast.highTemp/lowTemp` were non-null `Float`, so the
mapper had to invent a low. Both platforms then patch the evening drop differently:

- Android: `DailyTodayResolver.completeSameSiteReplacement` swaps today's partial row for the newest
  complete one; `DailyActualsEstimator` falls back to the hourly max/min; future partial days are
  filled from climate normals (`DailyFutureDayResolver`), except NWS's terminal low-only day.
- Desktop: `DesktopWeatherDao.getDailyForecasts` swaps any `high == low` day for the newest stored
  row with `high <> low`. No hourly fallback, no future normals fill.

Evidence (2026-10-02): desktop stored a flat NWS today row on 9 of 19 fetch days (17:55–23:57).
On 09-02 Android stored `74/null` at 22:21 while desktop stored `74/74` from 21:30 — the collapsed
rows `PastDayForecastOverlay` now skips. Current (01:00) forecasts are identical on both, as
expected: the difference is in the evening and the terminal day.

## Fix

1. `DailyForecast.highTemp/lowTemp` become `Float?`. Desktop DAO writes/reads real NULLs
   (`getFloat` used to turn NULL into 0°F). `ClimateNormals` skips missing values.
2. `:shared` `NwsDailyMapper.assemble(...)` runs Android's pipeline in Android's order (hourly
   precip/conditions moved from `NwsForecastMapper`), returning the accumulator plus diagnostics.
   Android logs from it as before; `buildDailyForecasts` projects it with nullable temps and
   Android's condition/precip fields.
3. `:shared` `PartialForecastDays`:
   - `completeReplacement` — newest candidate with both values (Android's today swap).
   - `todayForecastRange` — daily value, else hourly max/min.
   - `isTerminalLowOnlyNwsFutureDay`, `futureFromNormals` — Android's future-day fill.
4. Desktop uses them: the DAO's flat-day repair becomes today-only and null-triggered via
   `completeReplacement`; the model's today forecast range uses `todayForecastRange`; the repository
   fills partial future days from normals (`isClimateNormal`, the existing green fallback bar).
5. Android resolvers delegate to the same functions.

`PastDayForecastOverlay`'s collapse filter stays (legacy flat rows, Android's 3 flat OWM overlays).

## Tests

- `:shared`: assemble order (grid beats period), divergence cleared, evening drop → low null,
  terminal low-only day kept; `PartialForecastDaysTest`.
- Desktop: DAO today partial → complete replacement, future partial untouched by the DAO; model
  today hourly fallback; repository future normals fill. Existing `NwsDailyMapperBuildTest`
  expectations of `low = high` change to null.
- Android: existing resolver tests stay green.
