# Use Silurian's real current-temperature for the actual-temp line

## Context

The hourly graph's **actual (observed) temperature line** is smooth for NWS but jagged/sparse for
Silurian (and looks the same for any non-NWS source that can't supply frequent real "now" points).

The line's smoothness is driven entirely by how many distinct observation timestamps land in the
`observations` table within the graph window (`ObservationBlender.blendObservationSeries` →
`candidateTimes`, `ObservationBlender.kt:141`). NWS accumulates dense, sub-hourly points from real
stations. Non-NWS sources get their "now" points from `CurrentTempRepository.refreshCurrentTemperature()`,
which polls each source's lightweight `getCurrent()` every ~5–16 min (charging) and writes an
`ObservationEntity` per source/point-of-interest.

The recent "limit history" commits (`c7e8cb0`, `43a41d3`) are **not** the cause — they only add an
additive `hourly_forecast_history` table plus a bucket-collapse on the *daily* `forecasts` table.
Neither touches `observations` or the live hourly table that feed the actual line.

The real problem is **Silurian alone**:

- `CurrentTempRepository.fetchSilurianCurrent()` (`CurrentTempRepository.kt:262`) calls the heavy
  `silurianApi.getForecast(lat, lon, 1)`, which issues 3× `/history/hourly` + 1× `/forecast/hourly`
  per point — ~20 HTTP calls per current-temp tick across the 5 spatial points. These are slow and
  get throttled, so most Silurian "current" fetches never complete → few observations stored.
- Even when it does complete, `SilurianApi.getForecast` sets `currentTemp = firstHour?.temperature`
  (`SilurianApi.kt:184`) over a history+forecast list sorted ascending, so `firstHour` is the
  **oldest** point (~3 days ago), and `currentObservedAt = null`. The stored "current" observation is
  therefore a 3-day-old temperature stamped at "now."

Silurian's API (confirmed via its OpenAPI spec) has **no dedicated nowcast endpoint**; current
conditions come from `GET /forecast/hourly`, whose series begins at the current hour. The intended
outcome: give Silurian a real, lightweight `getCurrent()` so the current-temp loop reliably stores
correct, frequent "now" observations — making its actual line as dense/smooth as NWS over time.

## Approach

### 1. Add `SilurianApi.getCurrent(lat, lon): CurrentReading?`
File: `app/src/main/java/com/weatherwidget/data/remote/SilurianApi.kt`

Mirror the existing per-API pattern (`WeatherApi.getCurrent` at `WeatherApi.kt:189`,
`OpenMeteoApi.getCurrent` at `OpenMeteoApi.kt:161`), including a `CurrentReading` data class
(`temperature`, `condition`, `observedAt`).

- Issue a **single** `GET /forecast/hourly` request (no `/history/hourly` loop).
- Parse the hourly timeseries with the existing `parseTimeseries(..., "hourly")`.
- Select the entry **nearest to `now`** (not `firstOrNull()`); set `observedAt` to that entry's real
  timestamp (reuse the existing `LocalDateTime.parse(time.take(19)).atZone(...)` conversion at
  `SilurianApi.kt:164`).
- Reuse the existing `X-API-Key` header, `units=imperial`, and `ApiAccessException` error handling
  already used by `getForecast`.

### 2. Rewire `CurrentTempRepository.fetchSilurianCurrent()`
File: `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt:262`

Replace the `silurianApi.getForecast(point.first, point.second, 1)` call with the new
`silurianApi.getCurrent(...)`, matching how `fetchWeatherApiCurrent`/`fetchOpenMeteoCurrent` consume
`getCurrent()`. Keep the existing `ObservationEntity` insert under `SILURIAN_MAIN` /
`SILURIAN_<index>` and the multi-point-of-interest loop unchanged — only the per-point network call
and the temp/observedAt source change. Use `reading.observedAt` for the observation `timestamp`
(now a real value instead of `null → System.currentTimeMillis()`).

### 3. Fix the `getForecast` current value (consistency / fallback path)
File: `app/src/main/java/com/weatherwidget/data/remote/SilurianApi.kt:181-186`

`firstHour` is the oldest hourly point. Change `currentTemp`/`currentCondition`/`currentObservedAt`
to come from the hourly entry nearest `now` (extract a small helper shared with `getCurrent`). This
also corrects the value `saveHistoricalActuals`/header logic sees from the full-forecast path.

### "Applies to other APIs also" — audit
The other non-NWS sources already fetch real current values:
`OPEN_METEO`, `WEATHER_API`, `OPEN_WEATHER_MAP`, `VISUAL_CROSSING` use dedicated `getCurrent()`;
`TOMORROW_IO` extracts a real `current` block from its forecast (`TomorrowIoApi.kt:139`). **Silurian
is the only one synthesizing "current" from the wrong hourly point.** No change needed for the others
beyond confirming this during review. (Optional follow-up, out of scope: give `TOMORROW_IO` a
lightweight current call too, since its current path also pulls a full forecast.)

## Reused existing code
- Per-API `getCurrent()` + `CurrentReading` pattern: `WeatherApi.kt:189`, `OpenMeteoApi.kt:161`.
- `CurrentTempRepository.insertCurrentObservation()` + multi-POI loop (`getPointsOfInterest`) — unchanged.
- `SilurianApi.parseTimeseries(...)` and the existing timestamp parsing.
- Actual line consumes the stored observations via `ObservationBlender.blendObservationSeries`
  (`TemperatureHourDataBuilder.buildHourDataResult`, `ObservationBlender.kt:101`) — no renderer change.

## Verification
1. `./gradlew testDebugUnitTest --tests "*SilurianApi*"` (add a unit test: nearest-to-now selection;
   `getCurrent` issues a single hourly request and returns a non-null `observedAt`).
2. Build & install: `./gradlew installDebug`.
3. On device, ensure Silurian is a visible source; trigger current-temp fetches (plug in / unlock).
   Pull logs and confirm successful Silurian current fetches with real timestamps:
   - `adb logcat` / app_logs: look for `CURR_FETCH_SOURCE_RESULT source=silurian success=true`
     with `observedAgeMin` small (minutes, not ~4320 = 3 days), and `OBS_CURRENT_INSERT source=silurian`
     with `timestampAgeMin` small.
4. Pull the DB (`python3 scripts/backup_databases.py`) and query `observations` for
   `api='silurian'` over the last few hours — confirm rows accumulate at the loop cadence
   (sub-hourly), versus the previous sparse/old-timestamp rows.
5. Screenshot the hourly graph with Silurian selected after a few fetch cycles; the actual line
   should be visibly denser/smoother. Convert PNG→JPG before reading per CLAUDE.md.
