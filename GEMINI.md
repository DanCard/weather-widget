# GEMINI.md - Weather Widget Project Context

## Project Overview
**Weather Widget** is a multi-platform weather solution. It includes a home screen widget (with a launcher activity for configuration/onboarding) and a Linux desktop companion app. It provides high-accuracy weather forecasts by aggregating data from multiple sources: the **National Weather Service (NWS)**, **Open-Meteo**, **Tomorrow.io**, **WeatherAPI**, **OpenWeatherMap**, **Visual Crossing**, and **Silurian**.

### Key Features
- **Multiple API Support**: Comparison and toggling between NWS (US-only), Open-Meteo (Global), Tomorrow.io, WeatherAPI, OpenWeatherMap, Visual Crossing, and Silurian.
- **Adaptive, State-Aware Update System**: Dynamically reschedules lightweight UI updates and forecast fetches based on battery levels, charging state, and screen interactivity (screen-on vs. screen-off). Includes work-stall recovery to bypass background worker freezes on OEM devices like Samsung.
- **Dynamic Rendering**: Custom-drawn graphs for Daily (forecast bars) and Hourly (Bezier temperature curves) views.
- **Accuracy Tracking**: Compares historical forecasts against actual observations to provide reliability scores.
- **Widget-Centric UI**: Core interactions occur directly on the home screen, while a launcher activity provides onboarding and configuration.
- **Desktop Companion**: A Linux-native system-tray application (Compose Desktop) that provides quick weather lookups via a frameless popup, mirroring the widget's aesthetic. Includes a **PanelIpcServer** (Unix Domain Socket) to serve high-performance weather data to the XFCE panel.
- **Google Play Compliance**: Includes prominent background location disclosure and an in-app privacy policy viewer to meet store requirements.

---

## Technology Stack
- **Language**: Kotlin 2.0.21 (Coroutines, Flow, Serialization), **C** (Lightweight IPC client)
- **Build System**: Gradle 8.13 with Kotlin DSL, **GCC** (for genmon client)
- **UI Frameworks**: Android RemoteViews (Widget), Compose Multiplatform (Desktop)
- **Dependency Injection**: Hilt 2.51.1 (Android)
- **Database**: Room 2.6.1 (SQLite)
- **Networking**: Ktor 2.3.7 (Shared engine)
- **Background Work**: WorkManager 2.9.0
- **Testing**: JUnit 4, MockK, Coroutines Test
- **Minimum/Target SDK**: 26 / 34
- **Java**: Version 21

---

## Building and Running
The project requires Java 21. Ensure your environment is configured correctly before running Gradle commands.

### Android
```bash
# Build and install to connected device/emulator
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests
./scripts/emulator-tests.sh

# Play Store Fastlane (binary: ~/.local/share/gem/ruby/3.3.0/bin/fastlane)
~/.local/share/gem/ruby/3.3.0/bin/fastlane beta
```

### Desktop (Linux)
```bash
# Run the desktop app for development (fast iteration, no distributable)
./gradlew :desktop:run

# Build the repo-local app distribution (used by autostart)
./gradlew :desktop:createDistributable

# Run the autostart launcher script (rebuilds distributable if missing)
scripts/desktop-app-launcher-and-autostart.sh

# Rebuild, stop running app, and restart the new repo distributable immediately
scripts/buildStart.sh

# Fast restart of existing distributable (relaunch without rebuilding)
scripts/fast-desktop-restart.sh

# Package the desktop app as a Debian package
./gradlew :desktop:packageDeb
```

---

## Evidence-First Debug Protocol
When investigating bugs or data mismatches, follow this strict sequence:

1.  **Logs and or Database First (Source of Truth)**:
2.  **Action Second**:
    - Do not propose a fix until the evidence (Logs/DB state) confirms the root cause.

---

## Development Conventions
- **Dual-Platform Parity**: Always apply feature modifications, default tab/UI preferences, and behavior updates to BOTH Android (`:app`) and Linux Desktop (`:desktop`). Never ignore desktop when making application changes.
- **Shared Logic**: Business logic, models, and API clients are centralized in the `:shared` module to ensure consistency between Android and Desktop clients.
- **Widget Lifecycle**: Always use `goAsync()` within `BroadcastReceiver` to handle async operations without blocking.
- **Update Logic**:
    - **UI / Current Temp Update**: Highly responsive. While charging, schedules dynamically based on screen state: every 10 min when screen is interactive (screen-on), and every 16 min when screen is off. Bypasses stalls via state-aware scheduling that inspects and replaces overdue or far-future WorkManager jobs.
    - **Forecast Data Fetch (WorkManager)**: Battery, charging, and screen-state aware.
        - **On Charger**: Scaled per-source based on screen activity. Active source updates every 60 min (screen interactive) or 120 min (screen off). Non-active sources update every 120 min (screen interactive) or 240 min (screen off).
        - **Off Charger**: Leverages `BatteryFetchStrategy` (>70% battery: 240 min, >50% battery: 480 min, <=50% battery: suspends background updates; opportunistic fetches allowed down to 30% battery).
- **Naming**: PascalCase for Classes, camelCase for functions/properties, backtick-wrapped sentences for test functions.
- **Logging**: Use `private const val TAG = "ClassName"` and standardized log levels. Do **NOT** remove debug logs during the cleanup phase or after verifying a fix unless explicitly requested by the user. Maintain consistent logging for critical paths (e.g., both High and Low temperature labels).
- **Imports**: Grouped by (1) Android/Framework, (2) Libraries, (3) Project.
- **Desktop Single Instance**: Uses a fire-and-forget, last-launch-wins model. A new launch touches a `.quit` trigger file (`~/.local/share/weather-widget/.quit`), causing any running instance (incumbent) to gracefully quit, allowing the new launch to take over immediately.
- **Desktop Autostart**: Autostart file at `~/.config/autostart/weather-widget-desktop.desktop` executes `scripts/desktop-app-launcher-and-autostart.sh`, which runs the repo-local distributable app (rebuilding it once if missing).
- **Desktop Packaging**: Requires a full JDK with `jpackage` (bundled Android Studio JBR is insufficient; use `/usr/lib/jvm/java-21-openjdk-amd64` or set `JPACKAGE_HOME`). The packaged runtime must declare `java.sql`, crypto modules, and `jdk.unsupported`.
- **Desktop XFCE Genmon**: Integrates via `scripts/genmon-weather.py` (queries `weather.db` and writes Pango markup for panel display). Click events are captured via genmon and touch a `.show` file to open the popup window.

---

## Architecture Summary
The project follows a **Repository Pattern** coordinated with **WorkManager** and **AlarmManager**.
- **`WeatherRepository`**: The central orchestrator for network fetches and local persistence.
- **`PanelIpcServer`**: Lightweight Unix Domain Socket server (`weather.sock`) that provides Pango-formatted markup to the desktop environment.
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

### API & Data Characteristics
- **Data Types**: NWS returns integer temperatures; Open-Meteo returns decimals.
- **Fallback Logic**: `buildHourDataList` uses a priority fallback: Preferred Source → SOURCE_GENERIC_GAP → first available.
- **Diagnostics**: `app_logs` table stores timestamps as epoch millis. Use `datetime(timestamp/1000, 'unixepoch', 'localtime')` for queries.

### Desktop App & Daily Graph Rendering
- **Degenerate NWS Snapshots**: Once a day passes, NWS updates historical forecasts with degenerate placeholder values (`highTemp == lowTemp`). To ensure the comparison snapshot correctly represents the final forecast, filter out degenerate records when querying snapshots from the database.
- **Solid Yellow Historical Bars**: To avoid muddy/dark-brown color mixing on dark backgrounds and maintain visual parity with Android (which uses solid overlay bars), draw past days' historical forecast bars as solid, opaque vertical bars in perfect yellow (`Color.Yellow`).
- **Condition Flags & Icon Mapping**: On daily graph rendering, derive condition flags (like `isSunny`, `isRainy`, `isMixed`) from the resolved icon resource path rather than raw condition strings. This ensures fallback mapping consistency (e.g. `"Unknown"` or empty conditions that default to clear/sunny icons also correctly color the vertical bars in sunny gold).


