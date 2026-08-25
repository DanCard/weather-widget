# Unify Observation Series on Multi-Station IDW Blending

**Date:** 2026-08-25  
**Status:** Completed

## Context & Problem

When viewing hourly temperature graphs for non-NWS sources (e.g. Silurian, Open-Meteo) configured to use borrowed actuals (e.g. Synoptic or METAR), the hourly graph text for the dominant station was displaying an unexpected personal weather station (e.g. `f4751`) instead of the dominant airport station with the highest blend weight (`knuq`), and the actuals temperature curve differed from the multi-station IDW blend shown on the Stations/Blend diagnostic tab.

### Root Cause
In `ActualTemperatureSeriesBuilder.build(...)`, an obsolete single-station selection gate (`selectObservationSeries`) was filtering observations for any source where `displaySourceId != NWS && displaySourceId != TOMORROW_IO`. This gate selected a single station (`F4751`) based on raw observation count and discarded all other stations (including `KNUQ`, `E7138`, `AW020`, etc.) prior to calling `blendObservationSeries`.

Consequently:
1. `blendObservationSeries` only received data from a single personal station rather than the full multi-station cluster.
2. The dominant station text was forced to `F4751` instead of `KNUQ` (which holds 50.9% weight under Inverse Distance Weighting and the 95% personal station discount).

---

## Changes

1. **Removed Legacy Single-Station Gate**:
   - In [`ActualTemperatureSeriesBuilder.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilder.kt), removed the `selectObservationSeries(...)` branch from `build(...)`. All weather sources now feed their complete set of matched observations directly into `blendObservationSeries(...)`.
   - Deleted the obsolete `selectObservationSeries(...)` function and `SelectedObservationSeries` data class.

2. **Cleaned Up Android Widget Handler**:
   - In [`TemperatureHourDataBuilder.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/TemperatureHourDataBuilder.kt), removed `selectedStationId` filtering and deleted the obsolete `selectObservationSeries` test-visible wrapper.

3. **Updated Unit Tests**:
   - In [`ActualTemperatureSeriesBuilderTest.kt`](file:///home/dcar/projects/weather-widget/shared/src/test/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilderTest.kt), updated the test to verify that multi-station observations for borrowed actuals sources correctly apply IDW blending with the personal station discount and identify the dominant station (`KNUQ`).

---

## Verification

1. **Unit Test Suite**:
   - Executed `./scripts/unit-tests.sh` with **3,613 tests passed** across `:shared`, `:app`, and `:desktop` in 54 seconds.

2. **Linux Desktop App**:
   - Rebuilt distributable via `scripts/buildStart-desktop.sh` and verified that all 11 Synoptic stations are blended ($n=432$ points) and the dominant station label renders:
     ```
     DominantStationDiag: text=knuq 73.4° @ 2:15 pm (contribution=KNUQ)
     ```

3. **Android Emulator**:
   - Built and installed debug build (`./gradlew installDebug`).
   - Verified on emulator screen that the hourly temperature graph renders:
     ```
     knuq 73.4° @ 2:15 pm
     ```
