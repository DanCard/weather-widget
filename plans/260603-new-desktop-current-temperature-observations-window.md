# Desktop Observations Window Implementation Plan

Implement a dedicated window for viewing weather observations and data fetch logs in the desktop application, mirroring the functionality of the Android `WeatherObservationsActivity`.

## Objective
- Create a `ObservationsWindow` Composable that displays a list of recent observations (station name, distance, time, temp, condition) and fetch logs.
- Support cycling through available weather sources.
- Support manual data refresh.
- Persist window size and position.
- Add an entry point (button) in the main weather popup header.

## Key Files & Context
- `desktop/src/main/kotlin/com/weatherwidget/desktop/ObservationsWindow.kt`: New file for the observations UI.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`: Entry point and window management.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopConfig.kt`: Update to store window state (size/pos) if needed, or use a separate store.
- `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`: Data access for observations and logs.

## Implementation Steps

### 1. Data Layer Enhancements
- Ensure `DesktopWeatherDao` has efficient queries for:
    - Recent observations grouped by station (similar to Android's `loadObservations`).
    - Recent fetch logs from `app_logs`.

### 2. Create `ObservationsWindow.kt`
- Implement a Compose-based window.
- **Header**: Title, Source Cycler button, Refresh button, Close button.
- **Content**: A scrollable list (or tabs) showing:
    - **Observations**: List items with station name, ID, distance, reported time, temperature, and condition.
    - **Fetch Logs**: List of recent API fetch attempts and results.
- **State Management**:
    - Manage the current selected `WeatherSource`.
    - Handle loading states and manual refresh triggers via `DesktopWeatherRepository`.

### 3. Window State Persistence
- Update `DesktopConfig` or create a `WindowStateStore` to save:
    - `observationsWindowX`, `observationsWindowY`, `observationsWindowWidth`, `observationsWindowHeight`.
- Save state when the window is moved or resized.

### 4. Integration in `Main.kt`
- Add `observationsVisible` boolean state.
- Add `ObservationsWindow` call in the `application` scope, controlled by `observationsVisible`.
- Add a new "Stations" icon button (using the same icon as Android, likely `weather_stations_icon`) to the main weather popup header.

## Verification & Testing
- **Manual UI Verification**: 
    - Open the window from the main popup.
    - Verify observations list matches the current source.
    - Cycle sources and verify data updates.
    - Trigger a refresh and watch for updates.
    - Move/resize the window, close it, and reopen to verify persistence.
- **Data Verification**:
    - Cross-reference displayed observations with the database state.
