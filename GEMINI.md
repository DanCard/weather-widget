# GEMINI.md - Weather Widget Project Context

## Project Overview
**Weather Widget** is a specialized Android application designed exclusively as a home screen widget (no launcher activity). It provides high-accuracy weather forecasts by aggregating data from two primary sources: the **National Weather Service (NWS)** and **Open-Meteo**.

### Key Features
- **Dual-API Support**: Side-by-side comparison and toggling between NWS (US-only) and Open-Meteo (Global).
- **Two-Tier Update System**: Separates lightweight UI updates (temperature interpolation) from battery-heavy network fetches.
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
Always ensure `JAVA_HOME` is set to a Java 21 JDK before running Gradle commands.

```bash
# Build and install to connected device/emulator
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug

# Run unit tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test

# Run instrumented tests
./scripts/emulator-tests.sh
```

---

## Evidence-First Debug Protocol
When investigating bugs or data mismatches, follow this strict sequence:

1.  **Database First (Source of Truth)**:
    - Query the Room database via `sqlite3` or existing scripts:
        - `scripts/query_forecasts_all.sh`
        - `scripts/query_captured_today.sh`
2.  **Logs Second**:
    - Audit `app_logs` table for `SYNC_START`, `SYNC_SUCCESS`, `SYNC_FAILURE`.
    - Use `adb logcat` for runtime events.
3.  **Action Third**:
    - Do not propose a fix until the evidence (DB state/Logs) confirms the root cause.

---

## Development Conventions
- **Widget Lifecycle**: Always use `goAsync()` within `BroadcastReceiver` to handle async operations without blocking.
- **Update Logic**:
    - **UI Update**: Frequent (15-60m), opportunistic (no wakeup), uses interpolated cached data.
    - **Data Fetch**: Infrequent (1-8h), battery-aware, fetches new API data.
- **Naming**: PascalCase for Classes, camelCase for functions/properties, backtick-wrapped sentences for test functions.
- **Logging**: Use `private const val TAG = "ClassName"` and standardized log levels.
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

---

## Historical Context & Key Learnings

### Bug Fixes
- **Rate Limiter Bug (2026-02-05)**: Fixed an issue where `lastNetworkFetchTime` was set before the fetch, blocking retries on failure. Now restores the previous value on `NET_FETCH_FAIL` or `NET_FETCH_ERROR`.
- **Hourly Graph Label Overlap (2026-02-05)**: Implemented priority-ordered collision detection (`RectF.intersects()`) for temperature labels (low > high > start > end).
- **Graph Smoothness & Clutter (2026-02-13)**: 
    - Applied 3 iterations of a weighted moving average to "melt" stair-step data plateaus from NWS.
    - Used monotone-aware tangents in `GraphRenderUtils` to prevent spline overshoots.
    - Added value de-duplication: skips labeling if a similar value was already labeled within the last 5 hours.

### API & Data Characteristics
- **Data Types**: NWS returns integer temperatures; Open-Meteo returns decimals.
- **Fallback Logic**: `buildHourDataList` uses a priority fallback: Preferred Source → SOURCE_GENERIC_GAP → first available.
- **Diagnostics**: `app_logs` table stores timestamps as epoch millis. Use `datetime(timestamp/1000, 'unixepoch', 'localtime')` for queries.
