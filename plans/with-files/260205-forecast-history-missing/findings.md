# Findings - Missing Forecast History

## Project Overview
- **Issue:** Forecast history is missing/empty on devices.
- **Goal:** Restore visualization of forecast evolution (how predictions changed over time).

## Root Cause Analysis
### 1. The Coordinate Constraint (Query Logic)
The DAO uses a very tight spatial filter: `BETWEEN :lat - 0.02 AND :lat + 0.02`.
- **Reasoning:** This was intended to separate weather data for users who travel between cities.
- **Problem:** Mobile location providers often fluctuate by more than 1.4 miles. When this happens, history saved at coordinate A is invisible to a query at coordinate B.

### 2. Navigation Date Offset (Widget UI)
The widget click handlers are hardcoded to calculate dates relative to `LocalDate.now()`.
- **Bug:** They do not incorporate the `dateOffset` from `WidgetStateManager`.
- **Result:** Tapping a day on a scrolled widget results in the wrong date being passed to `ForecastHistoryActivity`.

### 3. Snapshot Execution (Persistence)
- **Observation:** `backups/.../weather_database` sometimes contains 0 snapshots.
- **Theory:** `saveForecastSnapshot` is only called after a successful network fetch. If the app is relying on cached data or if the network fetch is rate-limited, no new snapshots are created.

## Recommended Fixes
- **Spatial:** Increase tolerance to `0.1` degrees.
- **Temporal:** Fix `dateStr` calculation in `WeatherWidgetProvider` to respect navigation offset.
- **Forensic:** Add `AppLog` entries to `saveForecastSnapshot` for better debugging in the field.
