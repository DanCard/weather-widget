# GEMINI.md - Weather Widget Project Context

## Project Overview
**Weather Widget** is a home screen widget (no launcher activity). It provides high-accuracy weather forecasts by aggregating data from multiple sources: the **National Weather Service (NWS)**, **Open-Meteo**, **Tomorrow.io**, **WeatherAPI**, **OpenWeatherMap**, **Visual Crossing**, and **Silurian**.

### Key Features
- **Multiple API Support**: Comparison and toggling between NWS (US-only), Open-Meteo (Global), Tomorrow.io, WeatherAPI, OpenWeatherMap, Visual Crossing, and Silurian.
- **Adaptive, State-Aware Update System**: Dynamically reschedules lightweight UI updates and forecast fetches based on battery levels, charging state, and screen interactivity (screen-on vs. screen-off). Includes work-stall recovery to bypass background worker freezes on OEM devices like Samsung.
- **Dynamic Rendering**: Custom-drawn graphs for Daily (forecast bars) and Hourly (Bezier temperature curves) views.
- **Accuracy Tracking**: Compares historical forecasts against actual observations to provide reliability scores.
- **Widget-Only UI**: All interactions (navigation, API switching, refresh) occur directly on the home screen.

---

## Technology Stack
- **Language**: Kotlin 2.0.21 (Coroutines, Flow, Serialization)
- **Build System**: Gradle 8.13 with Kotlin DSL
- **Dependency Injection**: Hilt 2.51.1
- **Database**: Room 2.6.1 (SQLite)
- **Networking**: Ktor 2.3.7
- **Background Work**: WorkManager 2.9.0
- **Testing**: JUnit 4, MockK, Coroutines Test
- **Minimum/Target SDK**: 26 / 34
- **Java**: Version 21

---

## Building and Running
The project requires Java 21. Ensure your environment is configured correctly before running Gradle commands.

```bash
# Build and install to connected device/emulator
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests
./scripts/emulator-tests.sh
```

---

## Evidence-First Debug Protocol
When investigating bugs or data mismatches, follow this strict sequence:

1.  **Logs and or Database First (Source of Truth)**:
2.  **Action Second**:
    - Do not propose a fix until the evidence (Logs/DB state) confirms the root cause.

---

## Development Conventions
- **Widget Lifecycle**: Always use `goAsync()` within `BroadcastReceiver` to handle async operations without blocking.
- **Update Logic**:
    - **UI / Current Temp Update**: Highly responsive. While charging, schedules dynamically based on screen state: every 10 min when screen is interactive (screen-on), and every 16 min when screen is off. Bypasses stalls via state-aware scheduling that inspects and replaces overdue or far-future WorkManager jobs.
    - **Forecast Data Fetch (WorkManager)**: Battery, charging, and screen-state aware.
        - **On Charger**: Scaled per-source based on screen activity. Active source updates every 60 min (screen interactive) or 120 min (screen off). Non-active sources update every 120 min (screen interactive) or 240 min (screen off).
        - **Off Charger**: Leverages `BatteryFetchStrategy` (>70% battery: 240 min, >50% battery: 480 min, <=50% battery: suspends background updates; opportunistic fetches allowed down to 30% battery).
- **Naming**: PascalCase for Classes, camelCase for functions/properties, backtick-wrapped sentences for test functions.
- **Logging**: Use `private const val TAG = "ClassName"` and standardized log levels. Do **NOT** remove debug logs during the cleanup phase or after verifying a fix unless explicitly requested by the user. Maintain consistent logging for critical paths (e.g., both High and Low temperature labels).
- **Imports**: Grouped by (1) Android/Framework, (2) Libraries, (3) Project.

---

## Architecture Summary
The project follows a **Repository Pattern** coordinated with **WorkManager** and **AlarmManager**.
- **`WeatherRepository`**: The central orchestrator for network fetches and local persistence.
- **`WeatherWidgetProvider`**: Manages the `RemoteViews` and interaction intents.
- **`WidgetStateManager`**: Persists UI-specific state (offset, view mode, API source) per widget ID.
- **`GraphRenderUtils`**: Contains specialized logic for smooth Bezier curves and label de-cluttering (collision detection).

---

## Key Maintenance Scripts
- `scripts/backup_databases.py`: Pulls DB from device for local analysis.
- `scripts/emulator-tests.sh`: Safely runs tests on emulator.
- `restore_missing_history.sql`: Manual data recovery script.

---

## Testing Strategy
The project follows a **pure function extraction** philosophy to maximize testability with minimal dependencies:
- **Avoid Over-Mocking**: Prefer extracting logic into pure functions with no Android dependencies over using mocking frameworks. This keeps tests simple, fast, and decoupled from Android OS variations.
- **Pure Functions**: Extract logic (e.g., dimension calculation, temperature interpolation) into static or standalone functions that can be trivially tested with basic JUnit 4.
- **On-Device Verification**: Use physical devices/emulators to verify visual rendering (stretched graphs, label overlap) and OEM-specific behaviors (e.g., Pixel vs. Samsung launchers) that unit tests cannot capture.
- **Checking Distinction**:
    - **"Check emulator tests"**: Run the automated instrumented test suite (`./scripts/emulator-tests.sh`). A visual audit (screenshot) is only required if tests fail or if the user explicitly requests visual verification of the test run.
    - **"Check/Look at the emulator"**: Perform a mandatory empirical capture (screenshot via `adb` and `logcat` audit) to analyze the visual or runtime state of the widget. Speculative analysis of visual states is prohibited when an active device is available.

---

## Historical Context & Key Learnings

### Bug Fixes
- **Rate Limiter Bug (2026-02-05)**: Fixed an issue where `lastNetworkFetchTime` was set before the fetch, blocking retries on failure. Now restores the previous value on `NET_FETCH_FAIL` or `NET_FETCH_ERROR`.
- **Hourly Graph Label Overlap (2026-02-05)**: Implemented priority-ordered collision detection (`RectF.intersects()`) for temperature labels (low > high > start > end).
- **Graph Smoothness & Clutter (2026-02-13)**: 
    - Applied 3 iterations of a weighted moving average to "melt" stair-step data plateaus from NWS.
    - Used monotone-aware tangents in `GraphRenderUtils` to prevent spline overshoots.
    - Added value de-duplication: skips labeling if a similar value was already labeled within the last 5 hours.
- **Local Extrema Labeling (2026-02-17)**: 
    - Added logic to detect and label significant local peaks/valleys in the hourly graph.
    - **Priority Change**: Local Extrema are now drawn *before* Start/End labels to prevent less informative labels (like the graph start point) from overlapping and hiding significant local dips.
- **Daily Forecast UI (2026-02-17)**: 
    - Replaced "Today" label with the abbreviated day name (e.g., "Tue") for consistency.
    - Highlighted the current day's label and temperature values with a bright light orange (`#FFEACC`) to make it distinct.
- **Samsung Performance & DB Concurrency (2026-03-19)**:
    - **Query Optimization**: Replaced multiple redundant indices on the `forecasts` table with a single optimized composite index: `(targetDate, source, locationLat, locationLon, batchFetchedAt)`. Reduced data processing by restricting `getForecastsInRange` to the latest batch per source.
    - **Concurrency**: Explicitly enabled **Write-Ahead Logging (WAL)** in `WeatherDatabase` to prevent background syncs from blocking UI reads.
    - **Job Tracking**: Implemented `WidgetUpdateTracker` to automatically cancel stale update coroutines when a new update or resize event arrives.
    - **Scheduling**: Eliminated the redundant manual `OneTimeWorkRequest` loop in `WeatherWidgetWorker` and implemented a 5-minute sync cooldown.
    - **UI Responsiveness**: Added a 250ms debounce to `handleResize` to stabilize UI updates on foldable devices.
- **Hourly Graph Time-Scale & Lookback (2026-04-18)**: Extended hourly graph lookback to 72 hours to prevent missing historical observations. Resolved time-scale distortion and missing icons in historical forecast views.
- **Advanced Temperature Graph Label Placement & Curve Avoidance (2026-04-20)**:
    - Implemented curve avoidance algorithms to prevent labels from overlapping the Bezier temperature curves.
    - Added value-based placement for START/END labels and injected midpoint labels on wide widgets with sparse extrema to maximize readability.
    - Deduplicated the placement engine and fetch-dot layout pipelines (`resolveFetchDotLayout` / `tryExactFitForDirection`).
- **Touch Routing & Daily History Blending (2026-04-22)**: Fixed a touch routing regression in widget navigation, stabilized history day navigation/clamping, and unified the blending of historical actuals with forecasts.
- **Current Temp Charging Loop Recovery (2026-05-19)**:
    - Prevented scheduling stalls by making WorkManager unique work scheduling state-aware, inspecting the active job before enqueuing the next task.
    - Intelligently replaces overdue or mis-scheduled tasks while keeping active/due-soon work, adding persistent debug logs for scheduler actions.
- **Charging-Aware Screen-Off Current Temp Loop (2026-05-19)**:
    - Enabled background fetches during active charging even when the screen is off (interval of 16 minutes), while screen-on charging remains at 10 minutes.
- **Charging/Screen/Active-Aware Forecast Fetch Policy (2026-05-19)**:
    - Implemented a dynamic `ForecastFetchPolicy` using charging/screen-state context to reschedule periodic updates, scaling intervals based on screen state and source activity (60m to 240m), reducing battery wear.
- **Flow-Based Live Observation Reload & Diagnostic Split (2026-05-19)**:
    - Isolated observation-specific fetch diagnostics from high-volume layout/render diagnostics using a dedicated log query.
    - Wired a debounced Room Flow combination (`observeLatestFetchedAt` + `observeLatestCurrentObservationFetchLogAt`) to automatically refresh the Observations activity when fresh data is fetched.

### API & Data Characteristics
- **Data Types**: NWS returns integer temperatures; Open-Meteo returns decimals.
- **Fallback Logic**: `buildHourDataList` uses a priority fallback: Preferred Source → SOURCE_GENERIC_GAP → first available.
- **Diagnostics**: `app_logs` table stores timestamps as epoch millis. Use `datetime(timestamp/1000, 'unixepoch', 'localtime')` for queries.
