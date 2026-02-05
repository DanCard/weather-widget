# AGENTS.md

This file provides guidance to AI agents working on this repository.

## Project Overview

**Weather Widget** is an Android widget-only application that displays weather forecasts with dual-API support (NWS and Open-Meteo). The app has no launcher activity - users interact entirely through the resizable home screen widget.

### Key Features
- **Dual API Sources**: Fetches from both NWS (US-only, official government data) and Open-Meteo (global, no API key)
- **Resizable Widget**: Adapts layout from 1x1 (single day) to 8+ columns (7+ days)
- **Two View Modes**: Daily view (forecast bars) and Hourly view (temperature curve)
- **Temperature Interpolation**: Smooth current temperature display using hourly forecast data
- **Forecast Accuracy Tracking**: Compares predictions vs actual observations
- **Forecast History Viewer**: Activity to inspect forecast evolution and compare with actuals
- **App Log Auditing**: Persists fetch and cleanup events for diagnostics
- **Battery-Aware Updates**: Adjusts fetch intervals based on battery level (60-480 min)

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin 2.0.21 |
| Build System | Gradle 8.13 with Kotlin DSL |
| Min/Target SDK | 26 / 34 |
| Java Version | 21 |
| DI Framework | Hilt 2.51.1 |
| Database | Room 2.6.1 |
| HTTP Client | Ktor 2.3.7 |
| Background Work | WorkManager 2.9.0 |
| Serialization | kotlinx.serialization 1.6.2 |
| Testing | JUnit 4 + mockk 1.13.9 |

## Build Commands

```bash
# Build debug APK
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug

# Install to device/emulator
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug

# Build release
./gradlew assembleRelease

# Clean build
./gradlew clean

# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests com.weatherwidget.data.repository.WeatherRepositoryTest

# Run specific test method
./gradlew test --tests "com.weatherwidget.util.TemperatureInterpolatorTest.getInterpolatedTemperature returns null for empty list"
```

## Project Structure

```
app/src/main/java/com/weatherwidget/
├── WeatherWidgetApp.kt           # Application + WorkManager config
├── data/
│   ├── local/              # Room entities, DAOs, and database
│   │   ├── AppLogEntity.kt
│   │   ├── WeatherEntity.kt
│   │   ├── ForecastSnapshotEntity.kt
│   │   ├── HourlyForecastEntity.kt
│   │   ├── WeatherDao.kt
│   │   ├── ForecastSnapshotDao.kt
│   │   ├── HourlyForecastDao.kt
│   │   └── WeatherDatabase.kt
│   ├── remote/             # API clients
│   │   ├── NwsApi.kt       # National Weather Service API
│   │   └── OpenMeteoApi.kt # Open-Meteo API
│   ├── repository/         # Data coordination layer
│   │   └── WeatherRepository.kt
│   └── ApiLogger.kt        # API call logging
├── di/
│   └── AppModule.kt        # Hilt dependency providers
├── stats/
│   ├── AccuracyCalculator.kt
│   └── AccuracyStatistics.kt
├── ui/                     # Activities (settings, config, etc.)
│   ├── ConfigActivity.kt
│   ├── SettingsActivity.kt
│   ├── StatisticsActivity.kt
│   ├── FeatureTourActivity.kt
│   ├── ForecastHistoryActivity.kt
│   └── DailyAccuracyAdapter.kt
├── util/
│   ├── TemperatureInterpolator.kt
│   ├── NavigationUtils.kt
│   ├── WeatherIconMapper.kt
│   └── SunPositionUtils.kt
└── widget/                 # Widget core components
    ├── WeatherWidgetProvider.kt    # Main widget lifecycle
    ├── WeatherWidgetWorker.kt      # Background data fetch
    ├── WidgetStateManager.kt       # Per-widget state persistence
    ├── DailyForecastGraphRenderer.kt # Daily view graph rendering
    ├── HourlyGraphRenderer.kt      # Hourly view graph rendering
    ├── ForecastEvolutionRenderer.kt # Forecast history graphs
    ├── UIUpdateScheduler.kt        # AlarmManager-based UI updates
    ├── UIUpdateReceiver.kt
    ├── OpportunisticUpdateJobService.kt  # JobScheduler for Android 8+
    ├── ScreenOnReceiver.kt         # Screen unlock handler
    └── DataFreshness.kt            # Staleness checking
```

## Code Style Guidelines

### Import Organization
- Group imports in this order:
  1. Android/framework imports (`android.*`, `androidx.*`)
  2. Third-party library imports (`kotlinx.*`, `io.ktor.*`, `dagger.*`)
  3. Project imports (`com.weatherwidget.*`)
- Sort alphabetically within groups
- Use blank line between groups

### Formatting
- **4-space indentation** (no tabs)
- Use Kotlin idioms over Java-style code
- Use data classes for value objects
- Use `val` by default, `var` only when necessary
- Use string templates (`"$value"`) over concatenation
- Prefer explicit return types on public functions

### Naming Conventions
| Element | Convention | Example |
|---------|------------|---------|
| Classes | PascalCase | `WeatherRepository` |
| Functions | camelCase | `getWeatherData` |
| Properties | camelCase | `weatherDao` |
| Constants | UPPER_SNAKE_CASE | `TAG`, `MONTH_IN_MILLIS` |
| Private constants | UPPER_SNAKE_CASE in companion object | `WORK_NAME` |
| Test functions | Backtick-wrapped descriptive sentences | `` `getWeatherData returns cached data when not forcing refresh` `` |

### Logging
- Define `private const val TAG = "ClassName"` at top of file
- Use appropriate log levels:
  - `Log.d(TAG, "message")` for debugging
  - `Log.i(TAG, "message")` for informational
  - `Log.e(TAG, "message", exception)` for errors (always include exception)
- Log important state transitions and data fetches

### Error Handling
- Use `try-catch` blocks for API calls and I/O operations
- Return `Result<T>` for functions that can fail (e.g., `suspend fun getWeatherData(): Result<List<WeatherEntity>>`)
- Don't silently swallow exceptions - log them
- For database operations, Room handles errors; wrap DAO calls when needed

### Dependency Injection
- Use Hilt for DI
- Annotate singletons with `@Singleton`
- Use `@Inject constructor(...)` for constructor injection
- Use `@ApplicationContext` qualifier when needing Context
- Provide dependencies in `AppModule.kt`

### Coroutines
- Use `suspend` functions for async work
- Use `runTest` in unit tests for coroutine testing
- Use `coroutineScope` for structured concurrency
- Never use `GlobalScope`
- Use `goAsync()` in BroadcastReceivers to avoid ANRs

### Database (Room)
- Entities in `data/local` package
- Use composite primary keys when needed (e.g., `(date, source)` allows storing both APIs' data)
- DAOs return `suspend` functions or `Flow<T>`
- Add migrations in `WeatherDatabase.kt` when changing schema
- Use `OnConflictStrategy.REPLACE` for upserts

### API Calls
- Use Ktor client for HTTP requests
- Define data classes for request/response bodies
- Parse JSON using kotlinx.serialization
- Handle network errors gracefully with try-catch
- Log API calls via `ApiLogger`

## Testing Guidelines

### Test Framework
- JUnit 4 with mockk for mocking
- Coroutines test library for async code

### Test Structure
```kotlin
class TemperatureInterpolatorTest {

    private lateinit var interpolator: TemperatureInterpolator

    @Before
    fun setup() {
        interpolator = TemperatureInterpolator()
    }

    @Test
    fun `getInterpolatedTemperature returns null for empty list`() {
        val result = interpolator.getInterpolatedTemperature(emptyList(), LocalDateTime.now())
        assertNull(result)
    }
}
```

### Testing Conventions
- Use `mockk(relaxed = true)` for dependencies where behavior isn't critical
- Setup common test state in `@Before` method
- Write descriptive test names using backticks explaining behavior
- Test happy paths and edge cases
- Use `assertEquals(expected, actual)` ordering (expected first)
- Use `runTest` for coroutine tests

## Widget Development

### Widget-Only App Considerations
- No `MAIN`/`LAUNCHER` activity in manifest
- Primary entry point is `WeatherWidgetProvider` (AppWidgetProvider)
- Use RemoteViews for widget layouts (limited widget support)
- All user interactions via PendingIntents on widget elements

### Update System Architecture
The widget uses a two-tier update system to minimize battery impact:

| Update Type | Frequency | Method | Wakeup | Purpose |
|-------------|-----------|--------|--------|---------|
| **Current Temp UI** | 15-60 min | AlarmManager | No (opportunistic) | Update interpolated temp from cache |
| **Opportunistic UI** | ~30 min | JobScheduler | No (piggyback) | Update when system already awake |
| **Data Fetch** | 60-480 min | WorkManager | Yes (controlled) | Fetch from APIs |
| **User Interaction** | Immediate | Direct DB read | N/A | Instant UI update + conditional fetch |
| **Screen Unlock** | Immediate | Direct DB read | N/A | UI update + fetch if charging & stale |

### Widget Size Adaptation
- **1x1**: Today's high (+ current temp if space)
- **1x3**: Yesterday, today, tomorrow (text only)
- **2x3**: Graphical bars with high/low ranges
- **4+ cols**: Additional forecast days (2-5 days)
- **2+ rows**: Graph view; **1 row**: Text view

### Handle Resize Events
Always use `goAsync()` with coroutines in receivers for non-blocking operations:
```kotlin
override fun onAppWidgetOptionsChanged(...) {
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            handleResizeDirect(context, appWidgetManager, appWidgetId)
        } finally {
            pendingResult.finish()
        }
    }
}
```

### Navigation
- Daily view: Navigate by days (30 days history, 14 days forecast)
- Hourly view: Navigate by 6-hour chunks (±24h window)
- All navigation uses direct database reads for instant UI feedback

## Data Model

### WeatherEntity
- Primary key: `(date, source)` - allows comparison between NWS and Open-Meteo
- Tracks `isActual` flag to distinguish observations from forecasts
- `fetchedAt` timestamp for staleness checking
- Nullable `highTemp`/`lowTemp` for partial data handling

### ForecastSnapshotEntity
- Stores 1-day-ahead predictions before 8pm cutoff
- Enables comparison of predicted vs actual temperatures
- Used for accuracy tracking display

### HourlyForecastEntity
- Enables smooth current temperature transitions via interpolation
- Used for UI-only updates without network requests
- Source-tagged for dual-API support

### AppLogEntity
- Stores diagnostic logs for fetches, merges, and cleanup events
- Used for quick auditing in debug flows

## Testing the Widget

### Manual Testing
1. Build and install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. On device/emulator: Long-press home screen → "Widgets"
3. Find "Weather Widget" and drag to home screen
4. Resize to test different layouts (1x1, 1x3, 2x3, 4x3, etc.)

### Available Emulators
- `Generic_Foldable_API36`
- `Medium_Phone_API_36`

### Testing Checklist
- [ ] Widget displays on different sizes
- [ ] Navigation arrows work (left/right)
- [ ] API toggle switches between NWS/Open-Meteo
- [ ] View toggle switches between Daily/Hourly
- [ ] Current temperature interpolates smoothly
- [ ] Graph renders correctly on 2+ row widgets
- [ ] Text mode works on 1 row widgets

## Git Conventions

### Commit Message Format
```
Summary line

• First detail point
• Second detail point
• Third detail point
```

- Use detailed commit messages with bullet points
- Explain "why" not just "what"
- Reference files when specific changes are notable

## Architecture Reference

For detailed architecture documentation, see:
- `/arch/ARCHITECTURE.md` - Comprehensive system architecture
- `/arch/BitmapScalingArchitectureAnalysis-260203.md` - Bitmap rendering details
- `/HOURLY_VIEW_PLAN.md` - Hourly view implementation plan

## Configuration Files

- `gradle/libs.versions.toml` - Dependency version catalog
- `app/build.gradle.kts` - App-level build configuration
- `build.gradle.kts` - Project-level build configuration
- `settings.gradle.kts` - Project structure settings
- `gradle.properties` - Gradle build properties
