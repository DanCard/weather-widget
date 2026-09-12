# Backfill elapsed-hour forecast history when a site has none

## Reported behavior

Samsung SM-F936U1, 2026-09-11 18:21, hourly temperature graph, NWS: the pink actual line runs
9a → now (5:55 pm), but the grey dashed forecast line only begins at ~4 pm (78°). The forecast
for the elapsed hours 9a–4p is absent. Screenshot in the session; the user asked for a backfill.

## Runtime evidence

1. `dumpsys package`: `firstInstallTime=2026-09-11 17:25:42` = `lastUpdateTime`. Fresh install.
   `app_logs` first row 17:28:16 is `DB_CREATE Database created from scratch`.
2. `hourly_forecasts` and `hourly_forecast_history` for every real source (NWS, OPEN_METEO,
   SILURIAN, TOMORROW_IO) at both site fragments (37.417/-122.089 and 37.418/-122.094) hold rows
   from **17:00 today onward only**. History has exactly **one** `timestampToGroupPredictions`
   bucket per source. The only rows before 17:00 are `OPEN_METEO_PRIOR24` (the frozen cloud
   series), which `HourlyForecastStitcher` deliberately excludes from the temperature line.
3. Every provider's payload already *contains* today's elapsed hours: Open-Meteo is requested with
   `past_days=7` (`ForecastFetchCoordinator.kt:161`), Silurian with `include_past=true`
   (`SilurianApi.kt:29`, whose own comment says "so the forecast curve can retain the latest model
   run's elapsed hours"), Tomorrow.io with `startTime=nowMinus23h`. NWS `/forecast/hourly` starts
   at the current hour, but the raw gridpoint endpoint the app already fetches every NWS cycle
   (`getGridpointsBundle`) carries `temperature.values` back to the current issuance's start —
   probed live at 18:35: `validTimes 2026-09-11T15:00:00+00:00/P7DT10H`, i.e. 08:00 PDT today,
   `updateTime 21:41Z`.
4. `HourlyForecastStore.saveHourlyEntitiesFromShared` line 107 drops everything older than
   `now - 1h` before *either* table sees it (`futureData`), and the history rows are derived from
   that filtered list. `DesktopWeatherRepository.persistForecastResult` line 577 does the same on
   desktop. NWS elapsed hours are never assembled at all (`GridpointsBundle` parses skyCover, QPF
   and daily extremes from the raw grid, not hourly temperature).

## Root cause

The past-hours forecast line depends entirely on `hourly_forecast_history` rows that an *earlier*
fetch wrote while those hours were still in the future. On a fresh install, a new location, or a
recreated database there is no earlier fetch, so the line has no data until the device has run at
that site for a full day. The data to fill it arrives in every fetch and is discarded.

The drop is right for the live table (`hourly_forecasts` is a forecast archive; REPLACE-ing past
rows would erase what was predicted) and right for history when a snapshot for the hour already
exists (freshest-wins would promote the hindcast). It is wrong for an hour that has **no** row at
all: nothing is being overwritten, and the app's stated rule for past hours is already "the latest
forecast wins" (`HourlyForecastStitcher`, `hourly_past_hours_latest_forecast`).

## What will change

One rule, in `:shared`, applied on both platforms, same-source only (no provider ever substitutes
for another — `no_cross_source_fallback`):

**An elapsed hour (`dateTime < now - 1h`, within the hourly loader's 72 h look-back) from the
fetched payload is filed into `hourly_forecast_history` iff that source has no history row for
that hour at that site. Existing rows are never touched. The live table is unchanged.**

Files:

- `shared/.../data/model/ElapsedForecastBackfill.kt` (new, pure):
  `select(fetched: List<HourlyForecast>, nowMs, coveredHours: Set<Long>): List<HourlyForecast>`.
  Constants `LOOKBACK_MS = 72h`, `ELAPSED_BOUNDARY_MS = 1h` (the same boundary the live filter
  uses, so the two never disagree about which side an hour is on).
- `shared/.../data/remote/NwsApi.kt`: `GridpointsBundle` gains `temperatureByHour: Map<Long, Float>`
  (epoch-ms hour → °F) and `precipProbabilityByHour: Map<Long, Int>`, parsed from
  `properties.temperature` / `properties.probabilityOfPrecipitation` with the same `PTnH`
  expansion `parseSkyCoverFromProperties` uses. `wmoUnit:degC` → °F.
- `shared/.../data/remote/NwsForecastFetch.kt`: `NwsForecastBundle.elapsedHourlyPeriods` —
  grid hours strictly before the first live hourly period, with sky cover merged and
  `shortForecast` from a new `NwsSkyCoverCondition.shortForecastFor(cover)` (NWS's own sky-cover
  bands: ≤12 Clear, ≤37 Mostly Clear, ≤62 Partly Cloudy, ≤87 Mostly Cloudy, else Cloudy —
  vocabulary already used by `WeatherCodeMapper`).
- Android `HourlyForecastStore.backfillElapsedHistory(hourly, lat, lon, source)`: reads the
  source-scoped history rows for the elapsed window, same-site filters against the quantized
  site (as `saveHourlyEntities` does), runs `select`, inserts under the regular
  `ForecastHistoryPolicy` bucket with `fetchedAt = now`. Logs `HOURLY_HISTORY_BACKFILL
  source= site= elapsed= covered= stored=` — INFO when `stored > 0`, VERBOSE otherwise (it runs
  every fetch and is a no-op in steady state).
  Called from `saveHourlyEntitiesFromShared` (Open-Meteo, Silurian, Tomorrow.io) and from
  `ForecastFetchCoordinator.fetchFromNws` with the bundle's elapsed periods.
- Desktop `DesktopWeatherDao.getHourlyHistoryCoveredHours(lat, lon, source, from, to)` and a call
  in `DesktopWeatherRepository.refresh` next to the snapshot write, reusing
  `upsertHourlyForecastHistory` for the selected rows. NWS elapsed periods come from the same
  shared bundle via `DesktopWeatherService`.

Not changing: `hourly_forecasts` write filter; the stitcher; `OPEN_METEO_PRIOR24`; the Forecast
History view (a backfilled row's bucket is the fetch bucket, later than its hour, so
`DailySnapshotSelector`'s 24h-prior selection never picks it as an as-predicted snapshot).

## Tests

| # | Kind | Test | Asserts |
|---|------|------|---------|
| 1 | unit (shared) | `ElapsedForecastBackfillTest.selects only uncovered elapsed hours` | future hours, hours within the 1 h boundary, covered hours and hours older than 72 h are excluded; the rest returned in time order |
| 2 | unit (shared) | `ElapsedForecastBackfillTest.all covered yields empty` | steady state writes nothing |
| 3 | unit (shared) | `NwsApiGridTemperatureParseTest` | `PT3H` expands to 3 hourly keys; degC→°F; malformed validTime skipped; PoP parsed |
| 4 | unit (shared) | `NwsForecastFetchTest.elapsed periods come from the grid before the first live hour` | MockEngine grid with hours before/after the live start: only earlier hours become elapsed periods, sky cover merged, condition mapped; none when grid fetch fails |
| 5 | unit (shared) | `NwsSkyCoverConditionTest` | band boundaries 12/37/62/87 |
| 6 | integration (Room, Robolectric) | `HourlyForecastStoreElapsedBackfillTest` | fresh DB: backfill inserts the elapsed hours and NOT the future ones; live table untouched; second call inserts 0; an hour with a pre-existing snapshot in another bucket is not overwritten; a jitter fragment 0.001° away counts as covered |
| 7 | integration (sqlite, desktop) | `DesktopWeatherDaoTest.elapsed backfill` | same as 6 on the JDBC path |

## Verification

Tests: shared 10 new (ElapsedForecastBackfillTest 4, NwsSkyCoverConditionTest 1,
NwsForecastFetchTest +3), Android HourlyForecastStoreElapsedBackfillTest 5 (Room/Robolectric),
desktop DesktopWeatherDaoTest +1. Full `:shared` + `:desktop` suites 1,949 green; app
`data.repository.*` 188 green. Mutation check: replacing the same-site filter with box-only fails
exactly the jitter/site test. One existing desktop test re-pinned:
`DesktopBackfillIntegrationTest."Tomorrow refresh does not rewrite elapsed forecast storage"` now
seeds a genuine earlier snapshot for the elapsed hour and asserts it survives (55° "Predicted"),
instead of asserting the hour is absent — "does not rewrite" is the invariant, not absence.

Samsung SM-F936U1, debug install 18:48. The existing `TEMP_GAPS_REFRESH` trigger (missing=7,
spans 10:00..17:00, 15-min cooldown) had been forcing a full sync since 17:57 that could never
fill the gap; at 19:03:44 it fired again against the new build:

```
19:03:47 HOURLY_HISTORY_BACKFILL source=TOMORROW_IO site=37.417,-122.089 offered=23 covered=2 stored=21
19:03:47 HOURLY_HISTORY_BACKFILL source=NWS         site=37.417,-122.089 offered=10 covered=1 stored=9
19:03:49 HOURLY_HISTORY_BACKFILL source=OPEN_METEO  site=37.417,-122.089 offered=71 covered=2 stored=69
```

`hourly_forecast_history` NWS rows for today, one bucket, from the raw grid: 08:00 62° Mostly
Cloudy (65%) … 12:00 77° Clear … 14:00–16:00 80° Clear. Widget repaint at 19:04 draws the dashed
NWS line across the whole day (68° left edge → 80° peak 2p → 72° at 6p). Silurian was not fetched
in that sync (120-min interval not due) and will backfill on its next fetch. Desktop distributable
rebuilt and restarted 19:04.

Side finding while verifying: the Samsung's default route is a gnirehtet VPN; after the host
slept, the `adb reverse localabstract:gnirehtet tcp:31416` tunnel was gone while the relay still
ran, so every request timed out at 30 s from 18:14. Re-adding the reverse restored it.
