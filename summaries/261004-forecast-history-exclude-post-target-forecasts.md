# Forecast History: Exclude Post-Target Forecasts — Summary

Date: 2026-10-04

## Problem Statement & Root Cause

In the Forecast History window (specifically observed with Open-Meteo), forecasts were being displayed and recorded for dates *after* the target date had already elapsed. For instance, when inspecting the forecast history for Saturday, October 3, the graph extended into Sunday morning (October 4 at 3:00 AM) with "newest just now ago".

### Root Causes
1. **API Ingestion (`DesktopWeatherRepository.kt`)**: Open-Meteo queries use `past_days=7` to fetch past hourly observations for backfill (since Open-Meteo lacks a separate station observation API). However, the endpoint returns both hourly and daily data for those past 7 days. On Desktop, `persistForecastResult()` passed all daily entries to `weatherDao.upsertForecasts()`, which persisted past dates into `forecasts` with `dateOfPrediction = todayEpoch` (resulting in `dateOfPrediction > targetDate`). Android's `ForecastSnapshotStore` already filtered out `date.isBefore(todayDate)`.
2. **DAO Query (`DesktopWeatherDao.kt` / `ForecastDao.kt`)**: Neither the desktop nor Android `getForecastEvolution` SQL queries enforced `dateOfPrediction <= targetDate`.
3. **Graph Geometry & Calculation (`ForecastEvolutionGeometry.kt`, `ForecastHistoryWindow.kt`, `ForecastHistoryActivity.kt`)**: Evolution buckets, error sample calculations, and UI displays did not guard against negative `daysAhead` (`daysAhead < 0`), causing post-target predictions to be bucketed and plotted past day 0 on the timeline.

---

## Implementation Details

The fix enforces filtering at ingestion, persistence, DAO query, geometry calculation, and UI layers across all modules:

### 1. Shared Logic
- [`ForecastEvolutionGeometry.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/graph/ForecastEvolutionGeometry.kt):
  - In `bucketize()` and `errorSamples()`, filtered out data points where `daysAhead < 0`.
- [`DesktopWeatherDao.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt):
  - In `upsertForecasts()`, clamped `dateOfPrediction = minOf(todayEpoch, targetDate)` to prevent future prediction timestamps on past dates (e.g. in historical test data).
  - In `getForecastEvolution()`, added SQL condition `AND dateOfPrediction <= ?` (binding `targetDate`).

### 2. Desktop App
- [`DesktopWeatherRepository.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt):
  - In `persistForecastResult()`, filtered daily forecast records to `LocalDate.parse(it.date) >= today` before saving into the database.
- [`ForecastHistoryWindow.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/ForecastHistoryWindow.kt):
  - In `loadHistory()`, mapped points with `mapNotNull`, dropping any row with `daysAhead < 0`.
  - Recomputed `snapshotCount` and `newestFetchAgeMs` against valid pre-target points so the header caption accurately reflects the last pre-target snapshot age.

### 3. Android App
- [`ForecastDao.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt):
  - Added `AND dateOfPrediction <= :targetDate` to Room's `getForecastEvolutionRaw()` query.
- [`ForecastHistoryActivity.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt):
  - In `updateViews()`, filtered out points with `daysAhead < 0` when mapping `evolutionPoints`.

---

## Verification & Visual Results

### Database Cleanup
- Existing corrupted records in `~/.local/share/weather-widget/weather.db` were removed via:
  ```sql
  DELETE FROM forecasts WHERE dateOfPrediction > targetDate;
  ```
  Verified remaining invalid count is `0`.

### Automated Tests
- Added unit tests verifying post-target exclusion:
  - [`DesktopWeatherDaoTest.kt`](file:///home/dcar/projects/weather-widget/shared/src/test/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDaoTest.kt): `getForecastEvolution excludes forecasts whose prediction date is after target date`.
  - [`ForecastSnapshotDaoTest.kt`](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/data/local/ForecastSnapshotDaoTest.kt): `getForecastEvolution excludes forecasts whose prediction date is after target date`.
- Executed `./scripts/unit-tests.sh`: all 4,546 tests passed (1,774 shared, 454 desktop, 1,077 short, 26 localization, 79 medium, 1,136 long).

### Visual Verification
Rebuilt and launched the desktop app with `./scripts/buildStart-desktop.sh`, and captured empirical screenshots of the "History of Forecasts" window for Saturday, October 3:

| Metric / Element | Before Fix | After Fix |
| :--- | :--- | :--- |
| **X-Axis Timeline Range** | Extended past Sat Oct 3 to `10/4 3 AM` (Sunday morning) | Stops cleanly at `10/3 4 PM` (Saturday afternoon) |
| **Curve Points** | Stretched into negative days ahead (`daysAhead < 0`) | Only valid pre-target forecasts (`daysAhead >= 0`) |
| **Newest Snapshot Caption**| `63 snapshots · newest just now ago` (fetched on Oct 4) | `35 snapshots · newest 10h 46m ago` (last snapshot on Oct 3) |
| **Ingested Records** | Open-Meteo past 7 daily items saved as forecasts for passed dates | Filtered out at ingestion; prediction date clamped to target date |
