# QWEN.md - Weather Widget Project Context

## Project Overview
**Weather Widget** is a multi-platform weather application (Android Widget + Linux Desktop App) that aggregates data from multiple APIs (NWS, Open-Meteo, etc.) and tracks forecast accuracy.

## Tech Stack
- **Language**: Kotlin 2.0.21
- **Build System**: Gradle 8.13 (Kotlin DSL)
- **Android**: SDK 26/34, Hilt, Room, Ktor, WorkManager
- **Desktop**: Compose for Desktop, SQLite (via JDBC)
- **Java**: 21

## Build & Run
### Android
- **Build/Install**: `./gradlew installDebug`
- **Tests**: `./gradlew test` (Unit), `./scripts/emulator-tests.sh` (Instrumented)

### Desktop
- **Dev Run**: `./gradlew :desktop:run`
- **Distributable**: `./gradlew :desktop:createDistributable`
- **Fast Restart**: `scripts/fast-desktop-restart.sh`

## Core Architecture
- **Two-Tier Update System**:
    - **UI Updates**: Frequent, opportunistic (AlarmManager), updates interpolated temp from cache.
    - **Data Fetches**: Infrequent, battery-aware (WorkManager), fetches from APIs.
- **Repository Pattern**: `WeatherRepository` coordinates network and local DB.
- **Shared Logic**: `:shared` module contains API clients and models used by both Android and Desktop.

## Development Guidelines
- **Logging**:
    - `Log.v`: High-frequency traces (do NOT persist to DB).
    - `Log.d` and above: Persist to `app_logs` table.
- **Error Handling**: Log exceptions; do not swallow silently.
- **DI**: Use Hilt (`@Singleton`, `@Inject constructor`).
- **Coroutines**: Use `suspend` functions; `goAsync()` in `BroadcastReceiver`.
- **Database**: Room for Android; SQLite for Desktop. Use `OnConflictStrategy.REPLACE`.

## Evidence-First Debug Protocol
**Strict Requirement**: Do not guess root causes.
1. **Collect Evidence**: Pull logs (`adb logcat`), query DB, take screenshots.
2. **Analyze**: Identify the exact mismatch or failure point.
3. **Fix**: Implement the fix based on evidence.

## Testing Strategy
- **Pure Function Extraction**: Prefer extracting logic into pure functions over mocking.
- **Robolectric**: Use for JVM tests needing Android context.
- **Instrumented Tests**: Use only for real Canvas/Bitmap rendering or SQLite migrations.

## Desktop App Specifics
- **Single Instance**: Last-launch-wins via `.quit` trigger file.
- **Autostart**: Managed via `scripts/desktop-app-launcher-and-autostart.sh`.
- **IPC**: `PanelIpcServer` provides data to XFCE panel via Unix Domain Socket.

## Git Conventions
- **Commit Messages**: Imperative mood, concise summary, detailed "why" and "how" in the body.
