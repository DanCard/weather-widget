# Suppress Tomorrow.io Dominant Station Label on Hourly Graphs

## Overview
When viewing hourly forecasts under Tomorrow.io, the hourly temperature graph currently displays `tmrw <temp> @ <time>` as a dominant station annotation. Unlike NWS, METAR, or Synoptic feeds (which blend multiple physical weather stations and identify the dominant thermometer), Tomorrow.io provides a single proprietary model/realtime reading. Naming `tmrw` provides no station attribution value and clutters the graph.

This change marks Tomorrow.io's proprietary actuals as synthetic for dominant station resolution, suppressing the label under Tomorrow.io while retaining dominant station labels for NWS, METAR, and borrowing sources (Silurian, Open-Meteo).

---

## Changes

### 1. `TomorrowIoActuals.kt` (`:shared`)
- Expose `MERGED_SERIES_STATION_ID = "Tmrw"` as public/internal.
- Update KDoc to clarify that `Tmrw` is a single-feed logical series rather than a physical weather station.

### 2. `ObservationSourceMatcher.kt` (`:shared`)
- Update `isSyntheticBackfillStation(stationId, sourceId)` to return `true` for Tomorrow.io station IDs (`TOMORROW_IO_RECENT_HISTORY`, `TOMORROW_IO_REALTIME`, `MERGED_SERIES_STATION_ID`, and `HistoricalActualsBackfill.syntheticStationId`).
- This marks Tomorrow.io actuals with `isSynthetic = true` in `ActualTemperatureSeriesBuilder`, cleanly suppressing `DominantStationLabel.formatLabelText` on both Android and Desktop.

### 3. Unit Tests (`:shared`)
- In `ActualsSyntheticBackfillPriorityTest.kt`: add tests verifying Tomorrow.io dominant contributions are flagged `isSynthetic = true` and suppressed by `DominantStationLabel`.
- In `TomorrowIoActualsTest.kt`: verify `ObservationSourceMatcher.isSyntheticBackfillStation` recognizes all Tomorrow.io station IDs.
- In `DominantStationLabelTest.kt`: test Tomorrow.io suppression.

---

## Verification
- Run `./gradlew test` to ensure all tests across `:shared`, `:app`, and `:desktop` pass.
