# Task Plan: Unify Temperature Blending Logic & Remove Source Inference

## Objective
Fix the discrepancy between the Daily View's "Today" high/low temperatures (raw aggregate) and the Hourly Graph's actual peak/valley labels (blended aggregate) by unifying their calculation logic. Simultaneously, clean up technical debt by removing flawed string-matching source inference.

## Background & Motivation
- **Conflicting Data**: The Daily View calculates today's actual high using a raw aggregate (`maxOf`) across all observation rows. The Hourly Graph uses `ObservationBlender` to apply Inverse Distance Weighting (IDW) to smooth out station data. This divergence causes the UI to present conflicting numbers for the same day (e.g. 73.5° vs 73.1°).
- **Flawed Code**: Helper classes like `ObservationResolver` and `ObservationBlender` are determining the API source of an observation by examining the prefix of the `stationId` (e.g., `inferSource()`). This is redundant and error-prone because the `ObservationEntity` database model already has an explicit, strict `api` column.

## Proposed Solution
1. Strip out the `inferSource()` string-matching logic and use the explicit `api` column.
2. Modify `ObservationRepository.getDailyActualsWithLiveToday` to apply the exact same `ObservationBlender` logic to the current day's observations before extracting the high and low, ensuring both views use the mathematically identical blended dataset.
3. Optimize performance by passing down an `activeSourceList` so we only run the expensive IDW blending math for APIs actively used by widgets on the user's home screen.

## Implementation Steps

### Phase 1: Use Explicit API Column
1. **Update `ObservationResolver.kt`**:
    * Update `aggregateObservationsToDailyBySource` to group by `observation.api` instead of `inferSource(observation.stationId)`.
2. **Update `ObservationBlender.kt` / `TemperatureHourDataBuilder.kt`**:
    * Update `matchesObservationSource` to check `observation.api == displaySource.id` instead of calling `inferSource()`.

### Phase 2: Unify Daily & Hourly Blending Logic
1. **Update `ObservationRepository.getDailyActualsWithLiveToday`**:
    * Change signature to accept `hourlyForecasts: List<HourlyForecastEntity>` and `activeSourceList: List<String>`.
    * For the "Today" live computation, filter `todayObs` to only include observations whose `api` is in `activeSourceList`.
    * Group `todayObs` by `api`.
    * For each source, pass the observations through `ObservationBlender.blendObservationSeries`.
    * Extract the `maxOf` and `minOf` from the resulting *blended* observations to populate the daily actuals.
2. **Update Callers**:
    * Update `WeatherRepository.getDailyActualsWithLiveToday` to accept and pass the new parameters.
    * Update `WeatherWidgetProvider.kt` (inside `onUpdate` data loading) to pass the fetched `hourlyForecasts` and `activeSourceList`.
    * Update `WeatherWidgetWorker.kt` (inside `doWork`) to pass `hourlyForecasts` and `activeSourceList`.

## Verification & Testing
1. Compile and run unit tests.
2. Observe `logcat` on the emulator/device to verify that `trueActualHigh` logged by `TODAY_BAR_DEBUG` exactly matches the `ACTUAL_EXTREMA` peak logged by `TempExtrema`.
