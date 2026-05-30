## Project Overview

**Weather Widget** is an Android widget-only application that displays weather forecasts with multiple API support.
The app has no launcher activity - users interact entirely through the resizable home screen widget.

### Key Features
- **Multiple API Sources**: Fetches from NWS (US-only, official government data), Open-Meteo (global, no API key), openweather api, and silurian.
- **Resizable Widget**: Adapts layout from 1x1 (single day) to 8+ columns (7+ days)
- **Multiple View Modes**: Daily view (forecast bars), Hourly view (temperature curve), and more
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

Use plain `./gradlew ...` by default.

```bash
# Build debug APK
./gradlew assembleDebug

# Install to device/emulator
./gradlew installDebug

# Build release
./gradlew assembleRelease

# Clean build
./gradlew clean

# Run all tests
./gradlew test
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
│   ├── ForecastHistoryActivity.kt
│   └── DailyAccuracyAdapter.kt
├── util/
│   ├── TemperatureInterpolator.kt
│   ├── NavigationUtils.kt
│   ├── WeatherIconMapper.kt
│   ├── RainAnalyzer.kt
│   └── SunPositionUtils.kt
└── widget/                 # Widget core components
    ├── WeatherWidgetProvider.kt    # Main widget lifecycle
    ├── WeatherWidgetWorker.kt      # Background data fetch
    ├── WidgetStateManager.kt       # Per-widget state persistence
    ├── DailyForecastGraphRenderer.kt # Daily view graph rendering
    ├── HourlyTemperatureGraphRenderer.kt # Hourly view temp curve with min/max labels
    ├── PrecipitationGraphRenderer.kt # Hourly precipitation graph
    ├── GraphRenderUtils.kt          # Shared graph utilities (smoothing, bezier, labels)
    ├── ForecastEvolutionRenderer.kt # Forecast history graphs
    ├── UIUpdateScheduler.kt        # AlarmManager-based UI updates
    ├── UIUpdateReceiver.kt
    ├── OpportunisticUpdateJobService.kt  # JobScheduler for Android 8+
    ├── ScreenOnReceiver.kt         # Screen unlock handler
    └── DataFreshness.kt            # Staleness checking
```

## Code Style Guidelines

### Formatting
- Use Kotlin idioms over Java-style code
- Use data classes for value objects

### Logging
- Define `private const val TAG = "ClassName"` at top of file
- Use appropriate log levels:
  - `Log.d(TAG, "message")` for debugging
  - `Log.i(TAG, "message")` for informational
  - `Log.e(TAG, "message", exception)` for errors (always include exception)
- Log important state transitions and data fetches
- Do not be eager to delete debug logging
- Use database logging for important logging

### Error Handling
- Don't silently swallow exceptions - log them

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
- Use `goAsync()` in BroadcastReceivers to avoid ANRs

### Database (Room)
- Add migrations in `WeatherDatabase.kt` when changing schema
- Use `OnConflictStrategy.REPLACE` for upserts

### API Calls
- Use Ktor client for HTTP requests
- Define data classes for request/response bodies
- Parse JSON using kotlinx.serialization
- Handle network errors gracefully with try-catch
- Log API calls via `ApiLogger`

## Evidence-First Debug Protocol
For bug reports, regressions, "why is this happening?" analysis, and data mismatch investigations:

### Hard Gate Rules
- Do not guess at root cause.
- Do not propose or implement a fix until evidence is collected.
- If database and logs are not accessible, stop and ask for the exact missing command/data needed.
- For live widget questions about what icon/value/layout is currently showing on emulator/device, verify with runtime evidence first: screenshot plus renderer-specific `adb logcat`, and quote the actual emitted icon/resource log line when available before answering.

## Testing Guidelines

### Test Location and Framework Preference

**Prefer Robolectric (JVM) tests over instrumented (androidTest/emulator) tests whenever possible.** Robolectric tests run in seconds on the JVM; instrumented tests require an emulator or device and take minutes.

1. **Pure logic** (no Android dependencies): Write as plain unit tests in `test/` with no framework.
2. **Needs Android Context, SharedPreferences, Room, or Resources**: Extend `com.weatherwidget.test.RobolectricTest` (which provides `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`, and `@Category(LongDuration::class)`). Use `ApplicationProvider.getApplicationContext()` for Context.
3. **Needs real Canvas/Bitmap rendering, RemoteViews + performClick, real View.measure/layout, or real SQLite migrations**: Only then write instrumented tests in `androidTest/`.

When migrating from instrumented to Robolectric:
- Replace `InstrumentationRegistry.getInstrumentation().targetContext` with `ApplicationProvider.getApplicationContext()`
- Replace `@RunWith(AndroidJUnit4::class)` with `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`
- Wrap `WidgetIntentRouter.handleX()` calls in `try { } catch (_: Exception) {}` since AppWidgetManager.updateAppWidget fails without a registered widget in Robolectric
- Use `TestDatabase.create()` for Room in-memory databases
- Add test duration category annotations:
  - `@Category(ShortDuration::class)` — Pure JVM tests with no Android framework (no Robolectric, no Context). Just Kotlin logic. **Never use on Robolectric tests.**
  - `@Category(MediumDuration::class)` — Integration tests or slow unit tests that don't need the Robolectric framework.
  - `@Category(LongDuration::class)` — All Robolectric tests (JVM tests that need Android Context, SharedPreferences, or Room). This is the default for `RobolectricTest` subclasses.

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
- Write descriptive test names using backticks explaining behavior
- Test happy paths and edge cases
- When running a focused Android instrumented test and multiple devices are attached, prefer emulator-only execution first to avoid slow multi-device `connectedDebugAndroidTest` runs.
- Use `./scripts/emulator-tests.sh -c <fully.qualified.TestClass>` by default for emulator-only instrumented test iterations unless the user explicitly wants all connected devices.

## Documentation Preferences

### Session Logs
- Include user prompts
- Prefer numbered lists over bulleted lists in `session-logs/` for long lists.

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

## Device Identification

```bash
# List connected devices
adb devices

# Verify device identity
adb -s <device_id> shell "getprop ro.product.manufacturer && getprop ro.product.model"
```

### Safe Multi-Device SDK Query
Use this when you need `ro.build.version.sdk` for every connected device. It safely handles device serials with spaces and avoids stdin issues that can skip later devices in a loop.

```bash
ADB=/home/dcar/.Android/Sdk/platform-tools/adb
$ADB devices | sed '1d;/^$/d' | while IFS=$'\t' read -r serial state; do
  if [ "$state" = "device" ]; then
    sdk=$($ADB -s "$serial" shell getprop ro.build.version.sdk </dev/null 2>/dev/null | tr -d '\r')
    echo "$serial => sdk=${sdk:-<empty>}"
  else
    echo "$serial => state=$state (not queried)"
  fi
done
```

### adb Paths
- SDK path note: on this machine use `~/.Android/Sdk` (not `~/Android/Sdk`) for both `adb` and `emulator` commands.

**Example from this project:**
- `2A191FDH300PPW` - Appears like Samsung ID, but is actually **Google Pixel 7 Pro**
- `RFCT71FR9NT` - Appears like Pixel ID, but is actually **Samsung SM-F936U1**

**Lesson:** Device ID formats are unreliable for identification. Always verify with `getprop` before assuming which physical device corresponds to which ID.

## Testing the Widget

- No mocking framework — prefer pure function extraction for testability
- See [arch/testing-strategy.md](arch/testing-strategy.md) for full analysis and rationale

### Emulator Inspection Preference
- User phrase mapping: when the user says "look at emulator", assume the emulator is already running.
- Default inspection actions: take a screenshot and/or inspect runtime logs with `adb logcat`.
- If the user says the widget still looks wrong after a change, treat the emulator screenshot and renderer-specific logcat as the source of truth over code inspection. Add targeted placement/rejection logging first, then decide the fix from the observed runtime evidence.

### Runtime Source Discovery
- For live widget/API mismatch questions, do not ask the user for the location first if runtime context can provide it.
- Default order of operations: inspect a running emulator, otherwise inspect connected devices, otherwise start an available emulator.
- Verify the actual device/emulator identity with `adb shell getprop` instead of inferring from the serial format.
- Collect evidence from app state, widget state, logs, and the active NWS/Open-Meteo endpoint before asking the user follow-up questions.
- Only ask the user for location or endpoint details after those runtime discovery paths are exhausted.

### Manual Testing
1. Build and install: `./gradlew installDebug`
2. On device/emulator: Long-press home screen → "Widgets"
3. Find "Weather Widget" and drag to home screen
4. Resize to test different layouts (1x1, 1x3, 2x3, 4x3, etc.)

### Available Emulators
- `Generic_Foldable_API36`
- `Medium_Phone_API_36`

### Instrumented Tests
The `leaveApksInstalledAfterRun` flag in `gradle.properties` prevents post-test APK uninstall (which would remove all widget instances from the home screen). Do not remove this property.

```bash
# Run on all connected devices (emulator + physical)
./gradlew connectedDebugAndroidTest

# Filter to a specific instrumented test class (connected tests do NOT support --tests)
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.weatherwidget.widget.HourlyTemperatureGraphLabelTest

# Filter to a specific instrumented test method
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.weatherwidget.widget.HourlyTemperatureGraphLabelTest#highLabel_isDrawnAtMaximumTemperature

# Emulator-only
./scripts/emulator-tests.sh                                        # all tests
./scripts/emulator-tests.sh -c com.weatherwidget.util.RainAnalyzerIntegrationTest  # specific class
```

- Assume the user wants the emulator to remain running after tests unless they explicitly request shutdown.

### Emulator Resize Troubleshooting
- On Pixel/Nexus Launcher emulators, resizing from the left edge can "bounce back" when the widget is on the top row or near constrained cells.
- Symptom: Launcher shows resize handles, but drag snaps back and `onAppWidgetOptionsChanged` may not fire.
- Workaround: Move the widget down one row (or to an area with more free cells), then resize.
- This is launcher placement behavior (emulator-specific), not necessarily a widget rendering bug.

### Testing Checklist
- [ ] Widget displays on different sizes
- [ ] Navigation arrows work (left/right)
- [ ] API toggle switches between NWS/Open-Meteo
- [ ] View toggle switches between Daily/Hourly
- [ ] Current temperature interpolates smoothly
- [ ] Graph renders correctly on 2+ row widgets
- [ ] Text mode works on 1 row widgets

## Git Conventions

### Commit Message Strategy
- **Foundation**: Use the technical "Summary of Changes" provided at the end of a task as the verbatim foundation for the commit message body.
- **Format**:
    - **First Line**: A concise, high-level summary (under 72 characters) in the imperative mood (e.g., "Improve graph label placement...").
    - **Body**: The detailed summary, adjusted for plain-text (e.g., converting Markdown headers to bullet points or capitalized sections).
- **Content**: Always explain the "why" and "how" (technical rationale) in addition to the "what."
- **Scope**: Include all related changes (code, tests, documentation, and `plans/`).

## Architecture Reference

For detailed architecture documentation, see:
- `/arch/ARCHITECTURE.md` - Comprehensive system architecture
- `/arch/BitmapScalingArchitectureAnalysis-260203.md` - Bitmap rendering details

## Configuration Files

- `gradle/libs.versions.toml` - Dependency version catalog
- `app/build.gradle.kts` - App-level build configuration
- `build.gradle.kts` - Project-level build configuration
- `settings.gradle.kts` - Project structure settings
- `gradle.properties` - Gradle build properties
