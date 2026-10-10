# Daily view keeps far-day cloud and rain on the forecast row; hourly stored to 72 h

Plan: `performance/261010-daily-view-summaries-instead-of-far-hourly.md`.

## What changed

- `forecasts` gains `noonCloudPercent`, `hourlyDayPrecipMax`, `hourlyNightPrecipMax` (Room 78 /
  desktop 31, `FORECAST_HOURLY_SUMMARY_COLUMNS`).
- `:shared` `DailyHourlySummaries`: per-day values from a fetch's whole download (each window only
  when covered), stored hours as second source, `carryForward` so a fetch that does not reach a day
  never blanks it. Android `ForecastSnapshotStore` and desktop `DesktopWeatherDao.upsertForecasts`
  carry from the previous row; the stored day/night chance is provider → row max (`DailyPrecipPeriods`).
- Hourly (live + snapshot) stored to 72 h for every source (`HourlyHorizons.ROUTINE_HOURS`);
  on-demand fetch of a free source keeps its whole horizon.
- Readers (Android `DailyViewLogic` / `DailyForecastIconResolver`, desktop `DesktopDailyForecastModel`,
  shared `DailyRainLabels`): the row first, hourly for rows written before the columns.

## Verification

See the plan's Verification section: all suites green, migration test on the emulator, emulator DB
checked after a forced refresh. Desktop live fetch and Pixel DB pending their next scheduled syncs.
