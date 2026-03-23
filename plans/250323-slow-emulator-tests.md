# Fix Slow Emulator Tests (~5 min → ~40s)

## Context

Emulator tests used to run in under a minute but now take ~5 minutes. Analysis of the JUnit XML results (`TEST-emulator-5554 - 16-_app-.xml`) shows **one test class consumes 79% of total execution time**: `WeatherObservationsSourceIntegrationTest` takes 242s out of ~305s total. It launches a Hilt `@AndroidEntryPoint` activity 6 times via `ActivityScenario.launch()`, each paying full DI initialization on the emulator (22-88s per launch). A Robolectric version of this test already exists and covers most scenarios.

## Plan

### Step 1: Delete WeatherObservationsSourceIntegrationTest (saves ~242s)

**Delete:** `app/src/androidTest/java/com/weatherwidget/ui/WeatherObservationsSourceIntegrationTest.kt`

**Before deleting**, port 2 missing test scenarios to the existing Robolectric test:

**Add to:** `app/src/test/java/com/weatherwidget/ui/WeatherObservationsActivityRobolectricTest.kt`
- `activityStarts_withCorrectSourceFromWidget` — set source to OPEN_METEO, launch with widget ID, assert button text
- `activityStarts_withDefaultSource_whenNoWidgetIdProvided` — launch without widget ID, assert falls back to first visible source

The Robolectric test already covers source filtering and source cycling via button click.

### Step 2: Remove unnecessary IsolatedIntegrationTest inheritance (saves ~20-30s)

These 7 test classes extend `IsolatedIntegrationTest` (paying DB creation + `clearAllTables()` overhead) but never use the database:

| File | Actually needs |
|------|---------------|
| `widget/TemperatureGraphClutterTest.kt` | `context` only |
| `widget/TemperatureGraphLabelTest.kt` | `context` only |
| `widget/SamsungDataFailTest.kt` | `context` only |
| `widget/DailyForecastGraphRendererTest.kt` | `context` only |
| `widget/history/NwsHistoryIntegrationTest.kt` | nothing from base class |
| `widget/WidgetStateManagerApiRotationTest.kt` | `context` + SharedPreferences |
| `widget/WidgetStateManagerSyncTest.kt` | `context` + SharedPreferences |

For each: remove inheritance, add `context = ApplicationProvider.getApplicationContext()` in `@Before`, remove `override` from setup/cleanup.

### Step 3: Delete placeholder tests

Delete these files that only assert `assertTrue(true)`:
- `app/src/androidTest/java/com/weatherwidget/widget/handlers/TemperatureViewHandlerTest.kt`
- `app/src/androidTest/java/com/weatherwidget/widget/handlers/PrecipViewHandlerTest.kt`

### Step 4: Remove dead logcat code

In `TemperatureGraphClutterTest` and `TemperatureGraphLabelTest`: remove `logcat -c` call from `@Before`, remove unused `getHourlyGraphLogs()`, `runShellCommand()`, `instrumentation` field, and related imports.

### Step 5: Move pure-logic tests to unit test suite (saves ~2-3s)

- `NavigationUtilsTest` → `app/src/test/.../handlers/NavigationUtilsTest.kt` (plain JUnit, no Android deps)
- `DailyViewHandlerIntentContractTest` → `app/src/test/.../handlers/DailyViewHandlerIntentContractTest.kt` (Robolectric for Context)

## Verification

1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest` — verify Robolectric + moved tests pass
2. `./scripts/run-emulator-tests.sh` — verify instrumented tests pass and total time is under 60s
