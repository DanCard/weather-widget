# Session Summary: API-Specific Daily Actuals & Single-Series Hourly History
**Date**: Tuesday, March 17, 2026
**Status**: Completed & Verified

## Objective
Address two related data-presentation problems:

1. In the daily forecast view, toggling the API source did not change today's displayed high, even though each API had a different forecast high.
2. On Samsung, the hourly temperature history looked noisy because the past curve was effectively stitched from multiple observation stations within the same source.

## Evidence Collected

### 1. Daily view "today high" was not API-specific
- Runtime verification on the emulator showed the displayed `Today` high stayed fixed while source-specific forecast highs changed.
- On the same widget/date, toggling `NWS`, `WEATHER_API`, `OPEN_METEO`, and `SILURIAN` kept the displayed high at `86.0`, while underlying forecast highs differed.
- Code inspection showed daily view was rendering today's visible high from `observedHigh` rather than the selected API's forecast high:
  - `DailyViewLogic.kt`
  - `DailyActualsEstimator.kt`

### 2. Hourly history noise came from mixed station series
- Code inspection of `TemperatureViewHandler.buildHourDataList()` showed the hourly graph queried all observations in the visible time window and filtered only by source prefix, not by `stationId`.
- For NWS, those observations are populated from multiple nearby stations during current fetches and backfills in `CurrentTempRepository.kt`.
- Result: the hourly "actual" curve could hop between nearby stations or POIs inside a single visible window, producing visual jitter that was not just single-station noise.

## Key Requirements Implemented
1. **Daily actual highs/lows must be source-specific**.
2. **Hourly history should use one consistent observation series per displayed source**.
3. **Raw stored data should remain intact**; selection/rendering should change, not source retention.
4. **Existing graph fetch-dot alignment and forecast rendering should remain unchanged**.

## Detailed Changes

### 1. Daily View: Source-Specific Actual High/Low

#### Data plumbing
- Added source-scoped daily actual aggregation from `hourly_actuals` in `ObservationResolver.kt`.
- Added `HourlyActualDao.getActualsInRangeAllSources(...)` so widget update paths can pull daily actuals across all providers and then group by source.
- Updated widget update flows to carry `DailyActualsBySource` instead of one shared daily actual map:
  - `WeatherWidgetProvider.kt`
  - `WeatherWidgetWorker.kt`
  - `WidgetIntentRouter.kt`
  - `WidgetViewHandler.kt`

#### Rendering behavior
- `DailyViewHandler.kt` now selects actuals using `dailyActualsBySource[displaySource.id]`.
- `DailyViewLogic.kt` now uses only source-specific actuals for today/past actual highs and lows.
- `DailyActualsEstimator.kt` no longer substitutes forecast-derived hourly peaks/lows as "actual" values for today.
- If the selected source has no actuals for today/past dates, the daily UI now leaves actual values blank instead of reusing shared observations or another source's values.

#### Result
- Daily actual highs/lows now follow the selected API instead of appearing "stuck" across API toggles.

### 2. Hourly Temperature View: One Observation Series Per Source

#### Root cause addressed
- `TemperatureViewHandler.kt` previously injected every matching observation in the graph window.
- For NWS, that meant readings from multiple stations could be interleaved in the same curve.
- The same class of issue could also affect non-NWS sources that store multiple points of interest.

#### New selection logic
- Added a source-specific observation-series selector in `TemperatureViewHandler.kt`.
- The selector:
  - filters observations to the active source,
  - groups them by `stationId`,
  - chooses one winning series for the visible window,
  - uses coverage first, point count second, distance third, recency fourth.
- Only the selected series is injected into `HourData` for the graph.
- Existing carry-forward behavior for past gaps still applies, but only within the chosen series.

#### Logging
- Added debug logging for:
  - source,
  - selected station ID,
  - station type,
  - point count,
  - number of rejected alternate groups.

#### Result
- The hourly history line now reflects one consistent station/POI series per source instead of switching between multiple nearby sources mid-graph.
- This fixes the main cause of the Samsung "noisy" history appearance without hiding it via artificial smoothing.

## Design Decisions

### Daily view
- Source-specific actuals were treated as the correct semantic contract.
- No fallback to shared observations was retained for daily actual highs/lows.
- Forecast highs/lows remain distinct from actual highs/lows.

### Hourly history
- The fix was intentionally applied at the series-selection layer, not by smoothing the renderer.
- Raw observation storage remains unchanged so diagnostics and future analysis still have full fidelity.
- The renderer continues to use the raw/jagged actual line in `TemperatureGraphRenderer.kt`; the visible improvement comes from avoiding station switching.

## Tests Added / Updated

### Daily actuals
- Updated:
  - `DailyActualsEstimatorTest.kt`
  - `TripleLinePrecisionTest.kt`
  - `DailyViewHandlerTest.kt`
  - `DailyViewUiRoundingTest.kt`
  - `HistoryIconVisibilityRoboTest.kt`
  - `NwsHistoryIntegrationTest.kt`

### Hourly history
- Added/updated in `TemperatureViewHandlerActualsTest.kt`:
  - mixed NWS stations choose one consistent series by coverage,
  - non-NWS multi-POI inputs also resolve to one consistent series,
  - nearest-station tie-break when coverage matches.

### Existing graph/runtime coverage revalidated
- `TemperatureActualsIntegrationTest`
- `TemperatureGraphRendererActualsTest`
- `TemperatureGraphJunctionTest`
- `TemperatureGraphRendererContinuityTest`
- `TemperatureViewHandlerTest` (connected Android test)

## Verification Results

### Daily actuals work
- Targeted unit tests passed after aligning expectations with the new source-specific actual contract.
- Full unit suite passed after updating the remaining rounding test.

### Hourly single-series selection works
- Targeted unit tests passed for the new selector and existing graph behavior.
- Android test compilation passed.
- Connected instrumentation run for `TemperatureViewHandlerTest` passed on:
  - `SM-F936U1`
  - `emulator-5554`

### Full-suite status
- `./gradlew :app:testDebugUnitTest` passed.
- Earlier in the session, the full connected emulator suite also passed after aligning one integration assertion with current forecast-low fallback semantics.

## Commands Run
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.util.DailyActualsEstimatorTest --tests com.weatherwidget.util.TripleLinePrecisionTest --tests com.weatherwidget.widget.handlers.DailyViewHandlerTest --tests com.weatherwidget.widget.handlers.HistoryIconVisibilityRoboTest`
- `./gradlew :app:compileDebugAndroidTestKotlin`
- `./gradlew connectedDebugAndroidTest`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.handlers.TemperatureViewHandlerActualsTest --tests com.weatherwidget.widget.handlers.TemperatureActualsIntegrationTest --tests com.weatherwidget.widget.TemperatureGraphRendererActualsTest --tests com.weatherwidget.widget.TemperatureGraphJunctionTest --tests com.weatherwidget.widget.TemperatureGraphRendererContinuityTest`
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.weatherwidget.widget.handlers.TemperatureViewHandlerTest`

## Files Most Directly Affected
- `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt`
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`
- `app/src/main/java/com/weatherwidget/data/local/HourlyActualDao.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`

## Outcome
- Daily actual highs/lows now respect the selected API source.
- Hourly history no longer mixes multiple stations within the same visible source window.
- Both problems were fixed with behavior verified by unit and Android tests, while preserving raw stored data and existing renderer alignment semantics.
