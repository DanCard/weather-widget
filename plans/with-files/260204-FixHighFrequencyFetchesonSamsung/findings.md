# Findings - Samsung High Fetch Frequency

## Device Information
- Samsung Device ID: `sm-f936u1_adb-RFCT71FR9NT-j2OIso_adb-tls-connect_tcp`

## Fetch Analysis
- **Observation**: Samsung device performed 370 NWS Forecast fetches on 2026-02-04.
- **Timestamp Bursts**: Multiple fetches occurred within the same millisecond (e.g., 16 sessions at `13:23:46`).
- **Data Types**: The spike is primarily in `NWS Forecast` snapshots.

## Code Analysis
- Fetch logic location: `WeatherRepository.getWeatherData`
- Triggers: `WeatherWidgetProvider`, `ScreenOnReceiver`, `UIUpdateReceiver`, `OpportunisticUpdateJobService`.
- **Root Cause**: Redundant triggers (ScreenOn + Provider) and a race condition where multiple WorkManager instances could bypass the 1-minute rate limit if they started nearly simultaneously.

## Implementation Details
- **Throttling**: Increased `MIN_NETWORK_INTERVAL_MS` from 1 minute to 10 minutes.
- **Strict Guard**: Added `networkAllowed` parameter to `getWeatherData` to prevent network access during UI-only refreshes.
- **Worker Fix**: `WeatherWidgetWorker` now explicitly sets `networkAllowed = false` for UI-only updates.
