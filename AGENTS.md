## Project Overview

**Weather Widget** is an Android widget application that displays weather forecasts with multiple API support.
There is also a Linux app that is intended to function similarly.

### Key Features
- **Multiple API Sources**: NWS (US-only, official government data), Open-Meteo (global, no API key), and Silurian are the default-visible sources; Tomorrow.io is debug-only (tight free quota). Visual Crossing and OpenWeatherMap remain in the `WeatherSource` enum but are hidden/deprecated. API clients live in `:shared` (`shared/.../data/remote/`), not `:app`.
- **Resizable Widget**: Adapts layout from 1x1 (single day) to 8+ columns (7+ days)
- **Multiple View Modes**: Daily view (forecast bars), Hourly view (temperature curve), and more
- **Temperature Interpolation**: Smooth current temperature display using hourly forecast data
- **Forecast Accuracy Tracking**: Compares predictions vs actual observations
- **Forecast History Viewer**: Activity to inspect forecast evolution and compare with actuals
- **App Log Auditing**: Persists fetch and cleanup events for diagnostics
- **Battery-Aware Updates**: Adjusts fetch intervals based on battery level (60-480 min)

## Dual-Platform Changes (Memory)

**Apply every change to BOTH Android (`:app`) and Linux Desktop (`:desktop`) unless the user
explicitly restricts the request to a single platform.** This includes UI/layout/settings
reordering, preferences, default values, and behavior — not just feature logic.

When a settings section is added, removed, or reordered in the Android layout
(`app/src/main/res/layout/activity_settings.xml`), mirror the same change in the desktop settings
window (`desktop/src/main/kotlin/com/weatherwidget/desktop/SettingsWindow.kt`), and vice versa.

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin 2.3.10 |
| Build System | Gradle 8.13 + AGP 9.1.0, Kotlin DSL |
| Compile/Min/Target SDK | 35 / 26 / 35 |
| Java Version | 21 |
| DI Framework | Hilt 2.59.2 |
| Database | Room 2.7.0 |
| HTTP Client | Ktor 2.3.7 (OkHttp engine) |
| Background Work | WorkManager 2.9.0 |
| Serialization | kotlinx.serialization 1.7.3 |
| Testing | JUnit 4 + Robolectric + mockk 1.13.9 |
| Modules | `:app` (Android) · `:shared` (JVM weather/API code) · `:desktop` (Compose for Desktop) |

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

# Play Store Fastlane Commands (fastlane binary path: ~/.local/share/gem/ruby/3.3.0/bin/fastlane)
FASTLANE=~/.local/share/gem/ruby/3.3.0/bin/fastlane
$FASTLANE beta       # Upload release to Open Beta track
$FASTLANE run upload_to_play_store track:beta track_promote_to:production version_code:<VERSION> changes_not_sent_for_review:false # Promote Beta to Production
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
│   │   (API clients moved to :shared — shared/.../data/remote/:
│   │    NwsApi, OpenMeteoApi, SilurianApi, TomorrowIoApi, OpenWeatherMapApi;
│   │    WeatherSource enum is in shared/.../data/model/)
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
  - `Log.v(TAG, "message")` for **high-frequency / per-frame / per-tick / per-poll traces** (render
    breadcrumbs, the every-~2-min current-temp resolver, label-placement decisions, etc.)
  - `Log.d(TAG, "message")` for low-frequency debug diagnostics worth keeping
  - `Log.i(TAG, "message")` for informational events
  - `Log.e(TAG, "message", exception)` for errors (always include exception)
- Log important state transitions and data fetches
- Do not be eager to delete debug logging
- **VERBOSE vs the DB log — the key rule.** The shared `Log` (`shared/.../util/Log.kt`) has two
  routing decisions: the *ephemeral* sink (logcat / desktop console) shows everything including
  VERBOSE; the *persistent* DB log (`app_logs`, queried with `sqlite3`) is reserved for **sparse,
  queryable events**. `VERBOSE` is the explicit "do not persist" tier: it stays visible ephemerally
  but is dropped at the persistence boundary (the `CurrentTemperatureResolver.dbLogger` wiring skips
  `level == "VERBOSE"`). So:
  - **Anything that fires every frame/tick/poll → `Log.v`.** Otherwise it swamps `app_logs` (the
    `CurrentTempResolver` tag once grew to ~62k rows / ~96% of the table) and forces read-side
    filters to dig real events back out.
  - **`DEBUG` and above persist** to `app_logs` — fine for genuinely sparse breadcrumbs. Use database
    logging for important, low-frequency state you want to query later (e.g. one summary row per
    resolution like `CURR_TEMP_RESULT`, not the per-step trace).
  - On **Android** `app_logs` is the only persistent log (no file sink), so prefer a periodic
    *summary* at DEBUG over persisting a full trace.  Use VERBOSE for frequent render logs.

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

## Evidence-First Protocol
Applies to **all** agent work: bug reports, regressions, "why is this happening?" analysis, data
mismatches, feature implementation, layout/rendering changes, behavior explanations, and any
situations where the agent might assume or infer state instead of observing it.

### Hard Gate Rules
- **Don't guess — verify.** Never assume what the runtime state is, what a widget is displaying,
  what an API returned, or what code path executed. Check with logs, screenshots, `adb`, database
  queries, or add targeted logging first.
- Do not guess at root cause.
- Do not propose or implement a fix until evidence is collected.
- If database and logs are not accessible, stop and ask for the exact missing command/data needed.
- For live widget questions about what icon/value/layout is currently showing on emulator/device,
  verify with runtime evidence first via adb (screenshot, logcat, database dump).
- If logcat is missing, consider adding logging and/or asking the user to reproduce. A screenshot
  and analyzing it is often the fastest path to truth.
- When implementing features or making changes that affect runtime behavior, verify the result on
  device/emulator before declaring the work done — don't assume the code works because it looks
  correct.

## Testing Guidelines

### Test Location and Framework Preference

**Like to have Robolectric (JVM) tests for each instrumented (androidTest/emulator) tests.**

Robolectric tests connecting ≥2 real components are integration tests, not unit tests.

1. **Pure logic** (no Android dependencies): Write as plain unit tests in `test/` with no framework.
2. **Needs Android Context, SharedPreferences, Room, or Resources**: Extend `com.weatherwidget.test.RobolectricTest` (which provides `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`, and `@Category(LongDuration::class)`). Use `ApplicationProvider.getApplicationContext()` for Context. Single-component tests here are unit tests; multi-component ones are integration tests.
3. **Needs real Canvas/Bitmap rendering, RemoteViews + performClick, real View.measure/layout, or real SQLite migrations**: Only then write instrumented tests in `androidTest/`.

**`@Category` is required in ALL THREE modules — `:app`, `:desktop`, `:shared`.** Every test class
declares exactly one duration bucket and the build fails otherwise, enforced per module by
`validateUnitTestDurations` / `validateDesktopTestCategories` / `validateSharedTestCategories`. Each
module has its own markers under `<module>/src/test/.../com/weatherwidget/test/category/`, all using
the same package and names so `@Category` lines read identically everywhere.

Bucket by the class's measured wall time: **Short <0.2s, Medium 0.2–2s, Long ≥2s.**

| Module | One bucket | All buckets |
|---|---|---|
| `:app` | `:app:testShortDebugUnitTest` | `:app:testByDurationDebugUnitTest` |
| `:desktop` | `:desktop:testShortDesktop` | `:desktop:testByDurationDesktop` |
| `:shared` | `:shared:testShortShared` | `:shared:testByDurationShared` |

`:shared` is currently all-Short (513 tests in ~0.9s, pure JVM logic); its Medium/Long buckets are
empty on purpose and run as no-ops. `scripts/unit-tests.sh` still drives whole-module
`:shared:test` / `:desktop:test`, which now fail fast on an uncategorized test.

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

### Plans
- Prefer a plan for moderate and large changes.
- User prefers verbose output.  Always keep the user informed about what is happening.
- Always write or copy plan files directly to the `plans/` directory in the repository root.

## Widget Development

### Widget-Only App Considerations
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

### NEVER cancel a running WeatherWidgetWorker (native-crash trap)
Cancelling an in-flight `WeatherWidgetWorker` — most easily via `ExistingWorkPolicy.REPLACE`
enqueuing a new request under a unique name whose worker is already running — resumes the cancelled
coroutine's continuation and **segfaults the ART interpreter** (`SIGSEGV`/`SIGABRT` on the
`DefaultDispatch` thread, inside `doWork`). This is fatal and, crucially, **invisible to the JVM
crash logger** (native crashes never reach the `CRASH` app_logs row), so it long masqueraded as a
"dead / unresponsive widget" or a "battery" problem. It bites hardest on `debuggable` builds (no
AOT → everything runs in the interpreter).

Rules when enqueuing widget work:
- **Immediate/expedited unique work → `KEEP` or `APPEND_OR_REPLACE`, never `REPLACE`.** `KEEP` when a
  duplicate is redundant (an in-flight sync already produces fresh data); `APPEND_OR_REPLACE` when the
  latest state must still render/run (it runs *after* the current worker instead of killing it).
- **`REPLACE` is only safe for delayed / not-yet-running work** (e.g. `_ui_delayed`, a `REPLACE_DELAYED`
  heartbeat) — there is no live coroutine to cancel.
- **Periodic work uses `ExistingPeriodicWorkPolicy.UPDATE`** (mutates params without cancelling the
  running instance) — keep it that way.
- Explicit `cancelUniqueWork(...)` (loop teardown in `ScreenOnReceiver`-driven `*.cancel()` and
  `onDisabled`) can still hit a running worker at screen/power transitions — an accepted low-frequency
  residual; do not add new cancel-by-name paths.

Diagnosing: query `app_logs` for `PROC_EXIT` rows (see `ProcessExitLogger` — logs
`ApplicationExitInfo`, the only in-app source that captures native/LMK/ANR deaths) and read
tombstones via `adb shell dumpsys dropbox --print` (`data_app_native_crash` / `SYSTEM_TOMBSTONE`). A
`SYNC_CANCELLED stopReason=1` immediately before a `PROC_EXIT reason=CRASH_NATIVE` is the signature.
A crash-loop poisons WorkManager's queue (persisted work keeps retrying); clear it **without**
`pm clear`: `am force-stop`, then `run-as com.weatherwidget rm -f no_backup/androidx.work.workdb{,-shm,-wal}`
(the WorkManager DB is in `no_backup/`, not `databases/`; `weather_database` is untouched).

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

## Desktop App Development

### Desktop Architecture
- **Dual-Platform Parity**: Whenever feature changes, UI preferences, default tabs, or behavior modifications are requested, ALWAYS evaluate and apply corresponding changes to both Android (`:app`) and Linux Desktop (`:desktop`) unless the user explicitly restricts the request to a single platform.
- `:desktop` is a JetBrains Compose for Desktop tray/window app that reuses `:shared` weather models and API clients.
- `:shared` owns clean JVM weather/API code; do not pull Android `Context`, Room repositories, RemoteViews, or widget preferences into `:desktop`.
- The desktop app uses its own thin orchestration (`DesktopWeatherService`) and desktop-only config/location classes.

### Desktop Location Handling
- Saved desktop location lives at `${XDG_CONFIG_HOME:-$HOME/.config}/weather-widget/config.json`.
- First launch with no config opens the location picker immediately.
- Phone GPS via ADB and IP lookup run in parallel while manual picker controls remain usable.
- IP lookup is only a prefill/fallback suggestion; it should not auto-save or close the picker.
- A fresh phone GPS/fused fix may auto-save and close the picker. If phone lookup fails or is stale, leave the picker open and keep the diagnostic log visible.
- The picker log belongs at the bottom of the picker window and should grow when the window is resized taller.

### Desktop ADB Location Notes
- Plain `adb` may not be on `PATH`; desktop code should fall back to `/home/dcar/.Android/Sdk/platform-tools/adb` and Android SDK env vars.
- When multiple devices are connected, skip emulators and target real phones with `adb -s <serial> ...`.
- In Android `dumpsys location`, `Location[...] et=...` is an elapsed-realtime timestamp, not a fix age. Compute age as `phone /proc/uptime - location et`.
- `dumpsys location` output can be large. When running it through `ProcessBuilder`, drain stdout while the process is running; waiting for process exit before reading can deadlock and cause false timeouts.

### Desktop Window Behavior
- The weather window uses a standard system title bar so it can be resized/minimized/closed by the window manager.
- Closing the weather window hides it to the tray; it does not exit the app.
- Tray `Quit` should close long-lived resources such as the desktop `HttpClient` before calling `exitApplication()`.

### Desktop autostart chain

```
~/.config/autostart/weather-widget-desktop.desktop
  Exec= scripts/desktop-app-launcher-and-autostart.sh
        └─ exec's: desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop
                   └─ this binary IS the createDistributable output (jpackage launcher)
```


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

- **Prefer pure-function extraction for testability.** mockk (1.13.9) is available and used where
  mocking Android/WorkManager interactions is unavoidable (e.g. `UIUpdateReceiverTest`,
  `CurrentTempUpdateSchedulerTest` verify `enqueueUniqueWork` policies), but extract pure logic and
  test it framework-free first.
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

- **No Mid-Task Commits**: Do NOT commit code, tests, or documentation changes mid-task. Only perform commits when explicitly requested by the user.

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

## Commands
- `./gradlew :desktop:createDistributable` — build the project
- `./gradlew :desktop:run` — start the project
