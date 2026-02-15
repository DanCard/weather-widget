# Tech Stack - Weather Widget

## Core Technologies
- **Language:** Kotlin
- **Platform:** Android
- **Minimum SDK:** (To be verified, likely 26+ for JobScheduler/WorkManager features)
- **Target SDK:** Latest Stable

## Architecture & Frameworks
- **Database:** Room (SQLite) for persistent storage of weather data and forecasts.
- **Background Processing:**
    - **WorkManager:** For battery-aware data fetching.
    - **AlarmManager:** For opportunistic UI updates.
    - **JobScheduler:** For piggybacking UI updates on system wakeups.
- **Concurrency:** Kotlin Coroutines.
- **Dependency Injection:** (To be verified if Hilt/Koin is used, otherwise manual).
- **UI Components:**
    - **RemoteViews:** For widget rendering.
    - **Custom Graph Rendering:** Manual bitmap generation for temperature bars and Bezier curves.

## External APIs
- **National Weather Service (NWS):** Primary US-based data source.
- **Open-Meteo:** Global data source for fallback and comparison.

## Build System
- **Gradle:** 8.13+
- **Java:** JDK 21
- **Build Tooling:** `gradlew`

## Development Environment
- **IDE:** Android Studio
- **Emulator:** Generic Foldable API 36 (preferred for testing)
- **Physical Devices:** Pixel 7 Pro (cheetah), Samsung (RFCT71FR9NT) - *Note: Do not run destructive tests on these.*
