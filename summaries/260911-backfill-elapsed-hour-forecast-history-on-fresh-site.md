# Backfill elapsed-hour forecast history on a fresh site — summary

Plan: `plans/260911-backfill-elapsed-hour-forecast-history-on-fresh-site.md`.

## What happened

Samsung hourly graph (NWS) showed the actual line from 9a but the forecast line only from ~4p.
The device was a fresh install (17:25, Samsung Cloud restore; `DB_CREATE` 17:28). The past-hours
forecast line is drawn from `hourly_forecast_history` rows written by *earlier* fetches while
those hours were future; none existed. Every provider's payload carries the elapsed hours
(Open-Meteo `past_days=7`, Silurian `include_past`, Tomorrow.io `nowMinus23h`, NWS raw grid back to
the issuance start — probed live: 08:00 PDT), but `HourlyForecastStore.saveHourlyEntitiesFromShared`
and desktop `persistForecastResult` dropped them before either table. The graph's own
`TEMP_GAPS_REFRESH` trigger had been forcing a full four-source sync every 15 minutes since 17:57
and could never succeed.

## What changed

- `shared/.../data/model/ElapsedForecastBackfill.kt` (new): one rule — an elapsed hour
  (`< now-1h`, within 72 h) is filed into history iff the source has no row for it at that site.
- `NwsApi.GridpointsBundle` gains `temperatureByHour` / `precipProbabilityByHour` parsed from the raw
  grid (`PTnH` expansion, degC→°F, plausibility gate); `NwsForecastFetch.elapsedHourlyPeriods`
  assembles grid hours before the first live period; `NwsSkyCoverCondition` names the condition.
- Android: `HourlyForecastStore.backfillElapsedHistory` + `ForecastFetchCoordinator` wiring for
  NWS and the shared-source path; `NwsForecastMapper.fetchFromNws` returns `NwsFetchResult`.
  Log `HOURLY_HISTORY_BACKFILL` (INFO when stored>0, else VERBOSE).
- Desktop: `DesktopWeatherDao.getHourlyHistoryCoveredHours`, `RawFetch.elapsedHourly` (NWS only),
  `DesktopWeatherRepository.backfillElapsedHistory`.
- Live-table filter unchanged; now shares `ELAPSED_BOUNDARY_MS` so both writes agree.

## Verification

See the plan's Verification section: 20 new/extended tests green, full shared+desktop suites and
app `data.repository` green, mutation-checked; on the Samsung the 19:03 forced sync stored NWS 9 /
Open-Meteo 69 / Tomorrow.io 21 rows and the widget drew the full-day dashed line.
