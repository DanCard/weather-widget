# 2026-03-18 Session Log: `daily_extremes` API-only, missing-yesterday graph, and emulator backfill evidence

## User-reported issue

On the emulator, the Tuesday / yesterday column was missing in the daily graph.

The user clarified the intended behavior:

- history should show two bars when available:
  - actuals, which for this feature means `daily_extremes`
  - forecast history for that day
- if `daily_extremes` is missing, the day should still render the forecast-history bar
- forecast history must not be substituted into the actuals bar
- when extremes are missing, the app should query the API for the missing data

The user later clarified a stronger architectural requirement:

- `daily_extremes` must not be derived from cached spot observations
- `daily_extremes` should come only from API-provided extreme temperature values
- `computeDailyExtremes` should be deleted

## Initial diagnosis before refactor

Earlier emulator evidence showed:

- observations existed for recent days
- `daily_extremes` for Tuesday / yesterday was missing
- the graph day was present but its actual high/low values were null
- the graph logic needed to continue rendering forecast-history bars even when actual extremes were missing

Separate worker evidence showed that the historical backfill path was skipping incorrectly because it used observation volume as a proxy for completed daily actuals.

Previous log behavior:

- `ObservationRepository.backfillNwsObservationsIfNeeded(...)` counted yesterday/today observations
- if counts were "high enough", it logged `Skipping backfill`
- this happened even when the required NWS `daily_extremes` rows were missing

That proved the old gate was wrong for the user’s desired model.

## Implementation work completed in this session

### 1. Preserve the historical day when `daily_extremes` is missing

This work had already been implemented before the user asked for the `daily_extremes` refactor, and it remained part of the final state:

- past days remain in the graph if forecast history exists
- the actuals/extremes bar stays absent when `daily_extremes` is missing
- the forecast-history bar still renders
- a background refresh can be requested for the missing actuals

Relevant files already touched for that behavior:

- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- `app/src/test/java/com/weatherwidget/widget/DailyGapFallbackGraphIntegrationTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewHandlerTest.kt`

### 2. Refactor `daily_extremes` to be API-sourced only

The user explicitly rejected the idea of rebuilding daily highs/lows from cached observations. The code was then changed to align with that requirement.

#### `ObservationResolver`

File:

- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`

Changes:

- removed `computeDailyExtremes(...)`
- added `officialExtremesToDailyEntities(...)`

New behavior:

- only observations with both `maxTempLast24h` and `minTempLast24h` are eligible
- rows are grouped by local date and inferred source
- the latest eligible observation for that date/source is used
- `DailyExtremeEntity` is created from those official API-supplied values only
- no fallback to spot temperatures

#### `ObservationRepository`

File:

- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`

Changes:

- removed all use of recomputation from raw observations
- removed `recomputeDailyExtremesForDay(...)`
- after latest-observation ingestion, only insert `daily_extremes` if the observation carries official extreme values
- changed `backfillNwsObservationsIfNeeded(...)` to gate on missing NWS `daily_extremes`, not observation counts

New `backfillNwsObservationsIfNeeded(...)` flow:

- compute required dates:
  - yesterday always
  - today only when local hour >= 2
- read existing `daily_extremes` in the range
- filter to `source == NWS`
- compute `missingDates`
- if nothing is missing, skip
- otherwise fetch backfill observations from NWS stations
- insert raw observations
- convert only official embedded extremes into `daily_extremes`
- stop once all missing dates are filled
- warn if missing dates remain after all station attempts

#### `ForecastHistoryActivity`

File:

- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`

Changes:

- removed the previous "repair `daily_extremes` from observations" path
- replaced it with `requestDailyExtremesRefreshIfNeeded(...)`

New behavior:

- determine recent NWS forecast target dates
- compare them to existing NWS `daily_extremes`
- if dates are missing, request a background widget refresh
- do not synthesize missing `daily_extremes`

#### `DailyExtremeEntity`

File:

- `app/src/main/java/com/weatherwidget/data/local/DailyExtremeEntity.kt`

Change:

- updated documentation comment to reflect that `daily_extremes` is API-provided and not synthesized from observations

#### Tests updated

File:

- `app/src/test/java/com/weatherwidget/widget/ObservationResolverTest.kt`

Changes:

- removed tests that asserted old computed-extremes behavior
- added tests for API-only conversion behavior:
  - latest official entity is selected
  - observations without official extremes are ignored
  - multiple sources produce separate rows

## Test execution during this session

Ran successfully:

- `./gradlew app:testDebugUnitTest --tests com.weatherwidget.widget.ObservationResolverTest`
- `./gradlew app:testDebugUnitTest --tests com.weatherwidget.widget.DailyGapFallbackGraphIntegrationTest --tests com.weatherwidget.widget.handlers.DailyViewHandlerTest`

One intermediate failure occurred when two `testDebugUnitTest` Gradle invocations were run in parallel:

- Gradle failed on `app/build/test-results/testDebugUnitTest/binary/in-progress-results-...bin`
- this was a shared test-results directory collision, not a code failure
- rerunning the widget suite serially succeeded

Another intermediate issue occurred during compilation:

- Kotlin daemon / incremental compilation hit a cache / backup-file failure
- Gradle fell back to a non-daemon compile
- the build then completed successfully

This did not point to an application code regression.

## Emulator investigation after the refactor

The user asked whether the backfill should have worked on the emulator.

Device verified:

- serial: `emulator-5554`
- manufacturer: `Google`
- model: `sdk_gphone64_x86_64`
- SDK: `36`

### Runtime log evidence

Collected from `adb logcat` filtered to widget and repository tags.

Observed sequence on the emulator:

- `WeatherWidgetWorker` ran normally
- `ObservationRepository.backfillNwsObservationsIfNeeded(...)` executed
- it detected:
  - `requiredDates=[2026-03-17, 2026-03-18]`
  - `existingDates=[2026-03-18]`
  - `missingDates=[2026-03-17]`
- it logged:
  - `Missing NWS daily_extremes for [2026-03-17], backfilling last 48 hours`
- it fetched backfill observations from:
  - `AW020`
  - `KNUQ`
  - `KPAO`
- after all attempts it logged:
  - `Backfill completed but official NWS daily_extremes still missing for [2026-03-17]`

This showed that the worker trigger and missing-date gate were functioning correctly after the refactor.

### Database evidence from emulator

The emulator image did not include `sqlite3`, so the app DB was copied locally using:

- `adb exec-out run-as com.weatherwidget cat databases/weather_database`
- plus `weather_database-wal` and `weather_database-shm`

The copied DB showed:

#### `daily_extremes`

Only `2026-03-18` rows existed:

- `2026-03-18 | NWS`
- `2026-03-18 | OPEN_METEO`
- `2026-03-18 | SILURIAN`
- `2026-03-18 | WEATHER_API`

No `2026-03-17` NWS `daily_extremes` row existed after the backfill attempt.

#### `observations`

There were many NWS observation rows for `2026-03-17`, but none carried official extremes.

Query result summary for non-Open-Meteo / non-WeatherAPI / non-Silurian rows:

- `2026-03-17 | 238 rows | 0 rows_with_official_extremes`
- `2026-03-18 | 245 rows | 0 rows_with_official_extremes`

Sample `2026-03-17` rows:

- `KNUQ | 2026-03-17 23:55:00 | 68.0 | NULL | NULL`
- `AW020 | 2026-03-17 23:55:00 | 64.994... | NULL | NULL`
- `KSJC | 2026-03-17 23:05:00 | 71.6 | NULL | NULL`

Conclusion from emulator data:

- the backfill worker did run
- it did fetch historical NWS observations
- but those NWS observation rows did not contain official 24-hour max/min fields
- therefore the API-only `daily_extremes` insert path had nothing to write for `2026-03-17`

So the backfill machinery worked, but it could not succeed in materializing yesterday’s NWS `daily_extremes` row from the specific NWS API path being used.

## Research result: NWS API extreme temps

The user asked how NWS API extreme temperatures work and how to retrieve yesterday’s data.

A short note was written to:

- `notes/260318-nws-api-extreme-temps.md`

Summary of the research:

- `api.weather.gov` station observation endpoints can expose:
  - `maxTemperatureLast24Hours`
  - `minTemperatureLast24Hours`
- but those are rolling 24-hour fields attached to individual observations
- they are not a dedicated "yesterday official daily max/min" endpoint
- the NWS Web API documentation currently notes an upstream issue where 24-hour max/min fields may be missing for stations outside the Central Time Zone

That aligns with the emulator evidence for this Bay Area location: the backfilled NWS observations had null 24-hour extremes.

Practical conclusion:

- `api.weather.gov` observations are not a reliable source for official historical daily extremes
- if official yesterday high/low values are required, the better NWS/NOAA path is climate / NOWData products rather than the observation feed currently used by the app

## Files changed during this session

Files changed directly in this session:

- `app/src/main/java/com/weatherwidget/data/local/DailyExtremeEntity.kt`
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`
- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`
- `app/src/test/java/com/weatherwidget/widget/ObservationResolverTest.kt`
- `notes/260318-nws-api-extreme-temps.md`

Files already modified earlier in the broader debugging / fix sequence and still relevant to the missing-yesterday behavior:

- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- `app/src/test/java/com/weatherwidget/widget/DailyGapFallbackGraphIntegrationTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewHandlerTest.kt`

## Final state at end of session

What is fixed:

- past graph days no longer disappear just because `daily_extremes` is missing
- the graph still renders forecast-history bars for those days
- `daily_extremes` is no longer synthesized from spot observations
- NWS backfill now checks for missing `daily_extremes` rows rather than using observation counts as a proxy

What is still unresolved:

- the current NWS observation backfill API path does not provide official historical extreme values for the emulator’s location / dates
- therefore `2026-03-17` NWS `daily_extremes` remains missing even though backfill ran correctly

Likely next architectural decision:

- either accept that `api.weather.gov` observations cannot reliably populate historical `daily_extremes`
- or add a new historical-actuals source based on NWS/NOAA climate products such as NOWData for official past-day max/min temperatures
