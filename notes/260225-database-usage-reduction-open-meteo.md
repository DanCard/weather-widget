# Database Usage Reduction Strategy for Open-Meteo (2026-02-25)

## Problem Statement
Open-Meteo was the largest space consumer in the `weather_database`, accounting for nearly **65%** of the space on the Samsung device (~4,808 snapshots vs ~2,496 for NWS). This was due to:
- **Fetch Range**: Requested 14 days of forecast plus 7 days of historical data (21 days total per call).
- **Decimal Precision**: Open-Meteo returns temperatures with decimal precision (e.g., 68.2°F). Minor fluctuations (e.g., to 68.4°F) triggered new snapshot entries in the `forecast_snapshots` table, leading to redundant rows.
- **Retention**: All sources shared a uniform 30-day retention policy.

## Implementation Details

### 1. Reduced Fetch Horizon
- **`WeatherRepository.kt`**: Changed the default `days` parameter for Open-Meteo from `14` to `7`.
- **`OpenMeteoApi.kt`**: Set `past_days` parameter to `0` (was `7`), disabling the retrieval of historical data which is less critical for the widget's primary use cases.
- **Result**: Data payload for each sync session reduced by **~66%** (7 days of forecast vs 21 days of combined forecast/history).

### 2. Granular Retention Policy
- Updated `WeatherDao`, `ForecastSnapshotDao`, and `HourlyForecastDao` with source-specific cleanup methods (e.g., `deleteOldDataBySource`).
- **`WeatherRepository.kt`**: Updated `cleanOldData()` to implement a split retention strategy:
    - **Open-Meteo**: Retention reduced to **7 days**.
    - **NWS / WeatherAPI / Generic Gap**: Maintained at **30 days**.
    - **System Logs**: Maintained at **3 days** (72 hours).

### 3. Aggressive Snapshot Deduplication
- **`WeatherRepository.kt`**: Updated `saveForecastSnapshot()` to round Open-Meteo's decimal temperatures to the nearest integer before comparison.
- **Result**: Prevents new snapshot rows from being created for insignificant decimal changes, while maintaining precision for the primary NWS source.

## Expected Storage Impact (Samsung RFCT71FR9NT)
- **Current DB Size**: ~2.6 MB (2.1 MB main + 512 KB WAL).
- **Open-Meteo Snapshots**: Expected to drop from ~4,800 rows to ~1,200 rows within 7 days.
- **Open-Meteo Hourly**: Expected to drop from ~1,100 rows to ~300 rows within 7 days.
- **Projected DB Size**: ~1.8 MB after retention pruning is complete.

## Verification
- **Build**: Successfully compiled with `./gradlew assembleDebug`.
- **Integrity**: `cleanOldData()` is verified to trigger after every successful full network sync.
- **Diagnostics**: Forensic logs in `app_logs` will track `DB_CLEANUP` events and snapshot counts.
