# GPS Verification: Testable Resample Seam, Heal Propagation Test, GPS_RESAMPLE Breadcrumb

## Context

Commit 18f28652 unified widget fetch location (`ActiveLocationResolver`) and added background GPS auto-healing (`WeatherWidgetWorker.sampleGpsAndMaybeUpdateLocation()`). The decision logic got unit tests, but the new GPS path itself has zero coverage and can't be tested as written:

- `sampleGpsAndMaybeUpdateLocation()` is a private ~85-line method that inlines the battery gate, permission checks, a static `LocationServices.getFusedLocationProviderClient()` call, the getCurrentLocation→lastLocation fallback ladder, and the heal decision. No seam to inject a fake fix.
- `doWork()` early-returns `Result.success()` when `WeatherDatabase.isTestingMode()` (WeatherWidgetWorker.kt:52), so the GPS path can never be exercised through the worker in tests — extraction is mandatory, not stylistic.
- `LocationUpdater.applyToAllWidgets()` (the heal propagation: widget prefs + POI + force refresh) has no test.
- The GPS path logs only to logcat. No `app_logs` row means "did GPS resample ever run on my phone this week?" is unanswerable from a pulled DB — the exact debugging style this project relies on (cf. WIDGET_RENDER_OK precedent).

Three deliverables (approved by user):
1. Extract a testable `GpsResampler` seam + unit tests.
2. Instrumented test of `applyToAllWidgets` propagation.
3. `GPS_RESAMPLE` app_logs breadcrumb with outcome.

## Item 1: Extract `GpsResampler` + unit tests

### New file: `app/src/main/java/com/weatherwidget/widget/GpsResampler.kt`

```kotlin
class GpsResampler(
    private val appLogDao: AppLogDao,
    private val sharedLocationResolver: SharedLocationResolver,
    // Seam: production impl wraps FusedLocationProviderClient; tests inject a fake.
    private val locationProvider: suspend (useActiveFix: Boolean) -> Location?,
    // Seam: defaults to real permission check; tests inject a predicate.
    private val permissionChecker: (Context, String) -> Boolean = { ctx, perm ->
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    },
    // Seam: defaults to LocationUpdater::applyToAllWidgets; lets unit tests avoid
    // WorkManager.getInstance() (uninitialized under Robolectric).
    private val applyHeal: (Context, Double, Double, String) -> Unit = LocationUpdater::applyToAllWidgets,
) {
    companion object {
        const val LOG_TAG = "GPS_RESAMPLE"
        // Pure gate — plain-JUnit testable (project convention: pure-function extraction).
        fun shouldSample(isEffectivelyCharging: Boolean, batteryLevel: Int): Boolean =
            isEffectivelyCharging || batteryLevel > BatteryTier.TIER_HIGH_THRESHOLD
    }

    suspend fun resample(context: Context) { ... }
}
```

`resample()` orchestrates (logic moved verbatim from the worker, plus breadcrumbs):
1. Battery gate: read `ACTION_BATTERY_CHANGED` sticky intent, evaluate via `shouldSample(BatteryStatePolicy.isEffectivelyCharging(intent), level)`. Skip → breadcrumb, return.
2. Permission checks via `permissionChecker` (FINE required; BACKGROUND on Q+ decides active-fix vs. lastLocation degrade). No FINE → breadcrumb, return.
3. `locationProvider(hasBackgroundPermission)` → fix or null. Null → breadcrumb `no_fix`, return.
4. `LocationUpdater.shouldHealTo(context, lat, lon)` — false → breadcrumb `same_site`, return.
5. Label via `sharedLocationResolver.fromCoordinates(lat, lon).label`, falling back to `"%.4f, %.4f"` on exception (existing behavior, keep the `Log.w`).
6. `applyHeal(context, lat, lon, label)` → breadcrumb `healed`.

### New file: production location source (same file or `FusedGpsSource.kt`)

Factory producing the default `locationProvider`: moves the existing `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` → `lastLocation`-fallback `suspendCancellableCoroutine` ladder out of the worker unchanged. Follows the `ConfigActivity.waitForCurrentLocation(client, …)` precedent (ConfigActivity.kt:221) of parameterizing the client rather than a Hilt binding for `FusedLocationProviderClient`.

### Wiring

- `di/AppModule.kt`: `@Provides @Singleton fun provideGpsResampler(appLogDao, sharedLocationResolver, @ApplicationContext ctx)` constructing it with the fused-client provider. (Pattern: `provideAppLogDao` at AppModule.kt:146.)
- `WeatherWidgetWorker.kt`: replace the `sharedLocationResolver` constructor param with `gpsResampler: GpsResampler` (worker no longer uses the resolver directly); the private method body becomes `gpsResampler.resample(context)` inside the existing try/catch and mode guards (`!uiOnlyRefresh && !currentTempOnly && …`). Delete the now-moved imports (`LocationServices`, `Priority`, `CancellationTokenSource`, `suspendCancellableCoroutine`).
- `MainActivity.maybeAutoHealLocationFromGps()` (MainActivity.kt:198–230): its post-GPS tail (shouldHealTo → label lookup → applyToAllWidgets) is byte-for-byte the same logic. Extract that tail as `GpsResampler.healIfNeeded(context, lat, lon)` (public suspend, steps 4–6 above) and have both the worker path and MainActivity's `lifecycleScope.launch` call it. Keep MainActivity's foreground listener-based fix acquisition as-is (no battery gate there — foreground is intentional). ConfigActivity untouched (different semantics: explicit user configuration flow).

### Unit tests

**New: `app/src/test/java/com/weatherwidget/widget/GpsResamplerGateTest.kt`** — plain JUnit, `@Category(ShortDuration::class)` (model: `BatteryStatePolicyTest`):
- charging + low battery → sample; unplugged 71% → sample; unplugged 70% → skip (boundary: gate is `<= TIER_HIGH_THRESHOLD`); unplugged 30% → skip.

**New: `app/src/test/java/com/weatherwidget/widget/GpsResamplerTest.kt`** — extends `RobolectricTest`, mockk for `AppLogDao`/`SharedLocationResolver` (established: 60/202 test files use mockk). Fake widgets via `ShadowAppWidgetManager.addBoundWidget` + `SharedPreferencesUtil` prefs (model: `ActiveLocationResolverTest`). Robolectric's sticky battery intent is controllable via `ShadowApplication`/`Intent` — otherwise set battery via an injected charging state by sending a stuck broadcast shadow. Cases:
- no FINE permission → no locationProvider call, `skipped_no_permission` breadcrumb, no heal.
- provider returns null → `no_fix` breadcrumb, applyHeal not called.
- fix same-site with configured widget → `same_site` breadcrumb, applyHeal not called.
- fix differs → applyHeal called with fix coords + resolved label, `healed` breadcrumb.
- `fromCoordinates` throws → applyHeal still called with `"%.4f, %.4f"` fallback label.
- worker completes when provider suspends then returns null (fix-never-arrives path is just null here; the timeout lives in the production ladder).

## Item 2: Instrumented test of `applyToAllWidgets` propagation

### Prerequisite refactor: explicit widget ids

`LocationUpdater.applyToAllWidgets` currently derives ids from `getWidgetIds(context)` — on a real device that's the *actual placed widgets*, so a test would overwrite real widget locations. Add an ids parameter:

```kotlin
fun applyToAllWidgets(context, lat, lon, label, ids: IntArray = getWidgetIds(context))
```

Callers unchanged; test passes synthetic ids.

### New file: `app/src/androidTest/java/com/weatherwidget/ui/LocationUpdaterIntegrationTest.kt`

Extends `IsolatedIntegrationTest("location_updater")` (isolated prefs + isolated DB + refresh disabled; enqueued `WeatherWidgetWorker` no-ops because `setDatabaseForTesting` flips `isTestingMode`). **Verify during implementation** that `AndroidTestWidgetState.useIsolatedPrefs` isolation (the `SharedPreferencesUtil` `_test_default` suffix mechanism — see memory `shared_prefs_test_default_suffix`) also covers `ConfigActivity.PREFS_NAME` and `"weather_prefs"` reads/writes inside `applyToAllWidgets`; if not, snapshot-and-restore those prefs keys in `@Before/@After`.

Test cases:
1. `applyToAllWidgets(ctx, 30.2672, -97.7431, "Austin", intArrayOf(9001, 9002))` → both ids' `KEY_LAT_PREFIX`/`KEY_LON_PREFIX` floats updated in `ConfigActivity.PREFS_NAME`; `historical_pois` in `weather_prefs` gains `Austin|30.2672|-97.7431` as the last entry.
2. POI dedupe: pre-seed a POI with the same label, apply again → no duplicate, list capped per `takeLast(5)`.
3. Force refresh enqueued: `WorkManager.getInstance(ctx).getWorkInfosByTag("com.weatherwidget.widget.WeatherWidgetWorker").get()` contains a non-cancelled request (first WorkManager assertion in androidTest — real WorkManager on the emulator is fine since the worker no-ops in testing mode; no `work-testing` dependency needed).

Run: `./scripts/emulator-tests.sh -c com.weatherwidget.ui.LocationUpdaterIntegrationTest` (never `connectedDebugAndroidTest`).

## Item 3: `GPS_RESAMPLE` breadcrumb

Written inside `GpsResampler` via the injected `appLogDao.log(tag, message, level)` extension (AppLogEntity.kt ~line 103) — the shared `Log` facade does NOT persist rows, so don't use it. Single-line `key=value` message convention (model: `FREEZE_RAIN_CHANCE` at ForecastRepository.kt:346). Outcomes:

| Outcome | Message shape | Level |
|---|---|---|
| Battery gate skip | `outcome=skipped_battery level=63 charging=false` | DEBUG |
| No FINE permission | `outcome=skipped_no_permission` | DEBUG |
| Degraded (no background perm) | `mode=last_location` token on the outcome row | DEBUG |
| No fix obtained | `outcome=no_fix` | DEBUG |
| Fix same-site | `outcome=same_site lat=.. lon=..` | DEBUG |
| Healed | `outcome=healed lat=.. lon=.. label=..` | INFO |

Frequency is bounded by full-fetch cadence (1–24/day), so persisting DEBUG rows is cheap (VERBOSE is the only never-persisted level, per project convention). INFO on `healed` also mirrors to Crashlytics — appropriate, it's a rare state change.

## Files touched

| File | Change |
|---|---|
| `app/src/main/java/com/weatherwidget/widget/GpsResampler.kt` | **new** — extracted logic + seams + breadcrumbs |
| `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt` | delete `sampleGpsAndMaybeUpdateLocation` body → delegate; swap DI param |
| `app/src/main/java/com/weatherwidget/ui/MainActivity.kt` | tail of `maybeAutoHealLocationFromGps` → `gpsResampler.healIfNeeded` |
| `app/src/main/java/com/weatherwidget/ui/LocationUpdater.kt` | `applyToAllWidgets` gains `ids` param with current default |
| `app/src/main/java/com/weatherwidget/di/AppModule.kt` | `@Provides` for GpsResampler |
| `app/src/test/.../widget/GpsResamplerGateTest.kt` | **new** — plain JUnit gate boundary tests |
| `app/src/test/.../widget/GpsResamplerTest.kt` | **new** — Robolectric pipeline tests |
| `app/src/androidTest/.../ui/LocationUpdaterIntegrationTest.kt` | **new** — heal propagation + WorkManager enqueue |
| `plans/260706-gps-resample-testability.md` | **new** — repo copy of this plan (new file per task, never overwrite existing plan files) |

## Verification

1. `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.GpsResampler*" --tests "com.weatherwidget.ui.LocationUpdaterTest" --tests "com.weatherwidget.widget.ActiveLocationResolverTest"` — new + existing location tests green.
2. Full unit suite: `./gradlew test`.
3. `./scripts/emulator-tests.sh -c com.weatherwidget.ui.LocationUpdaterIntegrationTest`, then the full `./scripts/emulator-tests.sh` sweep.
4. `./gradlew installDebug` on the emulator; trigger a forced refresh while "charging" (emulator default), then `python3 scripts/backup_databases.py` and query the local DB copy: `SELECT datetime(timestamp/1000,'unixepoch','localtime'), message FROM app_logs WHERE tag='GPS_RESAMPLE'` — confirms the breadcrumb lands end-to-end (field observability, the original "is GPS being used" question, now answerable from a pulled DB).
5. Behavior-preservation check: worker still logs `doWork: Location = …` with the same coordinates as before the refactor on a normal refresh.
