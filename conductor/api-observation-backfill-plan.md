# Plan: API Observation Backfill

## Objective
Implement a production backfill mechanism for all non-NWS APIs (Tomorrow.io, Open-Meteo, WeatherAPI, Silurian, Visual Crossing) so the widget can immediately render the "red temperature actuals line" on a fresh install or an emulator. This removes the reliance on organically accumulating data over 24 hours.

## Background
Currently, the widget's "actuals" line is drawn by querying the `observations` table. For non-NWS APIs, observations are only recorded organically when the widget fetches the current temperature. On a fresh install or an emulator, this table is empty, resulting in a missing actuals line. 

## Proposed Solution (Zero Extra Network Requests)
Instead of building a complex background worker to explicitly fetch historical observations for each API, we can leverage the existing `ForecastRepository` fetch cycle:
1. Ensure every API client requests at least 24 hours of **historical hourly data** during its standard `getForecast()` call.
2. Intercept the historical portion of the `hourly` list (where `dateTime < now`) in `ForecastRepository` and synthesize `ObservationEntity` rows (e.g., `stationId = "TOMORROW_IO_MAIN"`).

## Implementation Steps

### 1. Update API Clients to Request History
Modify the API clients to include the past 24 hours in their hourly forecast responses:
- **`TomorrowIoApi.kt`**: Add `parameter("startTime", OffsetDateTime.now().minusHours(24).truncatedTo(ChronoUnit.HOURS).toString())` to the `/timelines` request.
- **`OpenMeteoApi.kt`**: Already requests `historyDays = ACTUALS_HISTORY_DAYS`. No change needed.
- **`VisualCrossingApi.kt`**: Update the timeline request range to start from `now - 24 hours`.
- **`WeatherApi.kt`**: If the `forecast.json` endpoint doesn't support history natively, add a parallel async fetch to `history.json` for the previous day and merge it into the `ForecastResult.hourly` list.
- **`SilurianApi.kt`**: Update the request to fetch the previous day's data and prepend it to the hourly list.

### 2. Synthesize Observations in `ForecastRepository`
Modify `ForecastRepository.kt` to extract past hourly data and persist it as observations.
- Create a new helper function: `saveHistoricalActuals(hourlyData: List<HourlyForecastEntity>, sourceId: String, lat: Double, lon: Double)`
- Filter the list for items where `dateTime <= System.currentTimeMillis()`.
- Map these items to `ObservationEntity`:
  - `stationId = "${sourceId}_MAIN"`
  - `stationName = "$sourceId: History Backfill"`
  - `timestamp = dateTime`
  - `temperature = temperature`
  - `condition = condition`
- Call `observationDao.insertAll()` to save the backfilled actuals.
- Call this helper inside `saveHourlyEntitiesFromShared()` so that every API automatically gets its history backfilled into the observations table during the forecast sync.

### 3. Clean Up Legacy Backfill Logic
- **`HourlyObservationBackfill.kt`**: Since all APIs will now organically backfill their actuals via the `ForecastRepository`, the explicit NWS backfill worker logic might become redundant. If NWS is also updated to provide historical hourly data via `NwsForecastMapper.kt`, the `OBS_HOURLY_BACKFILL_SKIP` logic and the worker can be safely deprecated or removed.

## Verification
- **Unit Tests**: Update `ForecastRepositoryTest` to verify that `observationDao.insertAll` is called with historical `ObservationEntity` rows when `saveHourlyEntitiesFromShared` processes past timestamps.
- **Emulator Verification**: Install the widget on the emulator, select "Tomorrow.io", and verify the red actuals line instantly appears without needing to wait 24 hours.