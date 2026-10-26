# Hourly forecast settle at hour and daily-hourly parity for temperature, cloud cover, and rain

## User Prompts

1. "desktop: today column, cloud percentage doesn't match what hourly graph says"
2. "Shouldn't use a day ago, for noon today. Maybe last update should be 11 am today. In other words should continue to update until hour is in the past."
3. "Do both sources use the same data?"
4. "Does both daily forecast view and hourly graph view use the same data?"
5. "Does both daily forecast view and hourly graph view use the same data in the proposed solution?"
6. "Agreed on proposed solution. Make sure the code is shared between desktop and android. Not duplicated."
7. "Write the plan to plans/ dir. This should be the same for temperature, cloud cover and rain %, hourly data."
8. "write summary of changes to summaries/ dir"

## Problem & Root Cause

1. **Hourly graph substituted day-ago forecast for today's elapsed hours**:
   `CloudSeriesBuilder` previously marked every hour with `dateTime < currentHourStart` as frozen and replaced it with `priorForecast[hour.dateTime]` (`OPEN_METEO_PRIOR24` / `cloud_cover_previous_day1`, the prediction from 24 hours prior). For noon today, the graph showed 15% (from 24h ago) rather than the forecast active when noon arrived.
2. **Post-hour fetch overwrote live forecast**:
   When Open-Meteo fetched at 12:28 PM (after 12:00 PM had passed), it returned 94% cloud cover for 12:00 PM as an assimilated post-hoc observation. Because `HourlyForecastStitcher` picked the newest fetch indiscriminately, the post-hour fetch overwrote the pre-noon forecast in the live table.
3. **Daily view and hourly graph consumed different representations**:
   The Today column showed 94% (from the daily table / latest fetch), while the hourly graph displayed 15% (from the 24h-prior prediction), resulting in visual contradiction.

## Changes

All shared weather behavior was placed in `:shared` without platform duplication:

1. **`shared/.../data/model/HourlyForecastStitcher.kt`**:
   - Updated `stitch` to enforce the settle-at-the-hour rule across all metrics (`temperature`, `cloudCover` and band layers, `precipProbability`, `precipAmountMm`):
     - **Future & current hours (`dateTime >= nowMs`)**: update continuously with each new fetch.
     - **Elapsed hours of today (`todayStartMs <= dateTime < nowMs`)**: freeze at the last forecast fetched at or before that hour (`fetchedAt <= dateTime`). Later fetches arriving after the hour passed (`fetchedAt > dateTime`) are rejected as post-hoc hindcasts/assimilations. If no pre-hour forecast exists (e.g. app installed after the hour), falls back to the earliest available candidate.
     - **Past days (`dateTime < todayStartMs`)**: live rows (e.g. from `GoogleHistoryRefill`) win over older snapshots, allowing user-triggered repairs to update historical rows.
     - Nullable fields are backfilled from other same-site candidates while preserving the DAO bucket-DESC order expected by snapshot retention pruning.
2. **`shared/.../shared/graph/CloudSeriesBuilder.kt`**:
   - Gated `priorForecast` (24h-prior prediction) and `priorBands` so they only apply to hours on past calendar days (`hour.dateTime < todayStartMs`).
   - For today's elapsed hours, `CloudPoint.forecastCover` preserves the settled forecast from `HourlyForecastStitcher` (`isFrozen = false`), eliminating the day-ago prediction swap on today.
3. **`shared/.../shared/util/DailyNoonCloudCover.kt` & `DailyColumnBuilder.kt`**:
   - Added `isToday: Boolean` to `DailyNoonCloudCover.forColumn(...)`. When `isToday == true`, prioritizes `hourlyNoon()` from the shared hourly forecast series over `rowPercent` from daily rows.
   - Passed `isToday = isToday` from `DailyColumnBuilder`, guaranteeing that the Today column and hourly graph consume the identical noon forecast.
4. **Tests Updated & Added**:
   - `HourlyForecastStitcherTest.kt`: updated past-hour tests to assert that pre-hour forecasts are frozen against post-hour live hindcasts across temperature, cloud cover, and precipitation chance.
   - `CloudSeriesBuilderTest.kt`: added test verifying today's elapsed hours keep the settled forecast without swapping in day-ago predictions, and updated past-hour tests to past-day offsets.
   - `CloudSeriesBuilderBandsTest.kt`: added test verifying today's elapsed hours keep live bands, and updated past-day band assertions.
   - `ObservedCloudBandPipelineIntegrationTest.kt`: updated past-day pipeline tests to span the full multi-day window.
   - `daily-parity/` fixtures: updated `early-morning.txt` and `nws-evening-partial.txt` expected files to reflect today's column consuming the shared hourly noon forecast.

## Verification

- **Full Unit Test Suite (`./scripts/unit-tests.sh`)**:
  - **6,162 tests passed** across all modules:
    - 2,781 `:shared` tests passed
    - 764 `:desktop` tests passed
    - 2,617 `:app` tests passed (short, medium, long, localization)
- **Daily Parity Contract (`scripts/daily_parity_diff.py`)**:
  - Reports **0 differences** across all fields and fixtures between Android and desktop.
- **Desktop Runtime Verification**:
  - Executable distributable built via `scripts/buildStart-desktop.sh` (`:desktop:createDistributable`).
  - Desktop daemon and UI window launched and verified running.
  - Window log confirmed both Cloud Cover view peak and Today column reflect the same 94% noon forecast.

## Related Plan

- [`plans/261026-hourly-forecast-settle-at-hour-and-noon-cloud-parity.md`](../plans/261026-hourly-forecast-settle-at-hour-and-noon-cloud-parity.md)
