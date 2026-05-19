# 2026-05-19 — Forecast Fetch Policy: Charging / Screen-State / Active-Source Aware

## Summary

This session designed and implemented a new per-source forecast-fetch policy that scales the
WorkManager schedule by charging state, screen state, and whether each source is the one currently
displayed on a widget ("active") vs. the others ("non-active"). The previous behavior was a
hardcoded 1-hour `PeriodicWorkRequest` that fetched all enabled sources together on every tick,
ignoring battery, screen, and which source the user is actually viewing. The dead-code
`BatteryFetchStrategy.computeFetchInterval()` battery tiers are now wired in for off-charger
behavior.

The user-stated rules while charging:

| State      | Active source | Non-active sources |
|------------|---------------|--------------------|
| Screen on  | every 60 min  | every 120 min      |
| Screen off | every 120 min | every 240 min      |

Off-charger uses the existing `BatteryFetchStrategy` tiers (240 min >70%, 480 min 50–70%, none <50%)
for all sources — no per-source distinction. The 10-minute global `MIN_NETWORK_INTERVAL_MS` rate
limiter in `ForecastRepository` is preserved as a defensive floor.

Plan mode was used to explore the codebase (two parallel `Explore` agents), clarify four design
choices via `AskUserQuestion`, write the plan, and exit with `ExitPlanMode`. Implementation
followed the plan with one deviation: a dedicated `ForecastRepository` integration test was
skipped in favor of relying on the existing repo test suite (which all passed unchanged), per
the project's "prefer pure-function extraction" testing philosophy.

## User Prompts

1. `How often should forecast updates run while charging: Screen on: Once an hour for active API, Once every two hours for non active API. Screen off: Once every two hours for active API, Once every 4 hours for non active API.`
2. *(AskUserQuestion responses)* — confirmed: "non-active" = all other enabled sources at the same slower rate; off-charger uses `BatteryFetchStrategy` tiers; decision made at worker fire time; replace the 1h periodic but keep the rate limiter as safety net.
3. *(plan approved)* — user accepted the plan and instructed to begin coding.
4. `write session log to session-logs/ dir, include summary.`

## Exploration Findings (Plan Phase)

Two parallel `Explore` agents mapped the existing fetch architecture:

- **Periodic forecast schedule**: hardcoded 1-hour `PeriodicWorkRequest` in
  `WeatherWidgetProvider.schedulePeriodicUpdate()`. Uses `ExistingPeriodicWorkPolicy.KEEP`.
- **Battery/screen state**: already read at `WeatherWidgetWorker.kt:57-61` via
  `BatteryStatePolicy.isEffectivelyCharging()` and `PowerManager.isInteractive`.
- **Active source per widget**: `WidgetStateManager.getCurrentDisplaySource(widgetId)` resolves the
  toggled-to source per widget id. The worker was already collecting this list at lines 134-140 —
  just in the wrong place (inside `onSuccess`, after the repo call).
- **Existing staleness policy**: `ForecastStalenessPolicy` (60/90/120 min by position in
  `visibleSourcesOrder`) — a precursor that the new policy effectively supersedes for the worker
  path while remaining the fallback for non-worker callers.
- **Existing per-source filter seam**: `ForecastRepository.isStale(source, forecasts)` is already
  the per-source decision point used inside `getWeatherData()`. The new policy slots in here with
  no flow restructuring required.
- **`BatteryFetchStrategy.computeFetchInterval()`**: existed with sensible tiers but was dead code.
  Now consumed by `ForecastFetchPolicy` for the off-charger case.
- **Fetch surface**: `WeatherRepository.getWeatherData()` already accepts `targetSourceId`, and the
  underlying repo already filters which sources to fetch per `isStale()`. No splitting into
  per-source workers was needed.

## Design Decisions (resolved via AskUserQuestion)

1. **"Non-active" = all other enabled sources** at the same slower rate. With four sources in
   `visible_sources_order` (`NWS,TOMORROW_IO,OPEN_METEO,SILURIAN`), three are non-active at any
   given time.
2. **Off-charger uses `BatteryFetchStrategy`** tiers (240/480/null). No per-source distinction.
3. **Decision at fire time** inside the worker. Single periodic worker, not per-source workers.
4. **Replace the 1h periodic**, keep `MIN_NETWORK_INTERVAL_MS` as a defensive floor.

The implementation also rescheduling the periodic worker on `ACTION_POWER_CONNECTED` /
`ACTION_POWER_DISCONNECTED` so the periodic tick interval matches charging state
(60 min charging vs. 240/480/1440 min off-charger). Without this, a single hourly worker
would keep firing on a low-battery device even when the longest case is 8 hours.

## Files Changed

1. **`app/src/main/java/com/weatherwidget/widget/ForecastFetchPolicy.kt`** *(new)*
   - `data class ForecastFetchContext(isCharging, isScreenInteractive, batteryLevel, activeSourceIds)`.
   - `intervalMinutes(...)`: pure-decision function returning the per-source interval (60/120/120/240
     while charging; delegates to `BatteryFetchStrategy.computeFetchInterval` off-charger; null =
     don't fetch).
   - `periodicTickMinutes(isCharging, batteryLevel)`: returns the shortest applicable interval so the
     periodic worker fires often enough to cover the most aggressive cell.
   - `isDue(lastFetchTimeMs, intervalMinutes, nowMs, graceMs = 120_000L)`: 2-min grace window.

2. **`app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`**
   - Added `fetchContext: ForecastFetchContext? = null` to `getWeatherData()`.
   - Threaded the context through `requiresNetworkFetch()` and `isStale()`.
   - `isStale()`: when `fetchContext` is non-null, consult `ForecastFetchPolicy.intervalMinutes(...)`
     keyed on `source.id in fetchContext.activeSourceIds`; otherwise fall back to the legacy
     position-based `ForecastStalenessPolicy.getStalenessThresholdMs(position)`.
   - Logged the per-source decision context to `appLogDao` (`NET_FETCH_START` payload extended).
   - `MIN_NETWORK_INTERVAL_MS = 600_000L` rate limiter at line 78 unchanged.

3. **`app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`**
   - Outer facade forwards `fetchContext` into `ForecastRepository.getWeatherData(...)`.

4. **`app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`**
   - Moved the active-source collection (was at lines 134-140 inside `onSuccess`) ahead of the
     `getWeatherData()` call.
   - Built `ForecastFetchContext` from the already-collected `isPlugged`, `isScreenInteractive`,
     `batteryLevel`, and `activeSourceList.toSet()`.
   - Gated context construction with `if (!forceRefresh && !uiOnlyRefresh)` so manual-refresh and
     UI-only paths keep their legacy behavior (still hit the network/cache without applying the
     new policy intervals).
   - Removed the duplicate inline collection inside `onSuccess` — `activeSourceList` is now in the
     outer scope and still available for `fetchDailyActuals(activeSourceList=...)`.

5. **`app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`**
   - Rewrote `schedulePeriodicUpdate()`: reads battery state via `ACTION_BATTERY_CHANGED`, picks
     tick from `ForecastFetchPolicy.periodicTickMinutes(isCharging, batteryLevel)`, builds
     `PeriodicWorkRequestBuilder` in `TimeUnit.MINUTES`.
   - Switched `ExistingPeriodicWorkPolicy.KEEP` to `UPDATE` so re-enqueues from `ScreenOnReceiver`
     on power state changes actually replace the interval.
   - Moved the function to the companion object (`@JvmStatic internal fun`) so
     `ScreenOnReceiver` can call `WeatherWidgetProvider.schedulePeriodicUpdate(context)` without
     instantiating the provider. Existing in-class callers (lines 206 and 405) work unchanged
     because Kotlin resolves companion members from instance scope.
   - Extended the schedule log message to include `intervalMinutes=$tickMinutes charging=$isCharging
     battery=$batteryLevel policy=update`.

6. **`app/src/main/java/com/weatherwidget/widget/ScreenOnReceiver.kt`**
   - `onReceive` now handles `Intent.ACTION_POWER_DISCONNECTED` → new `handlePowerDisconnected()`.
   - `handlePowerConnected()` calls `WeatherWidgetProvider.schedulePeriodicUpdate(context)` **before**
     the debounce check — the reschedule must always fire even if the lazy current-temp refresh is
     debounced.
   - `handlePowerDisconnected()` is a thin handler that reschedules the periodic worker to pick up
     the off-charger interval.

7. **`app/src/main/AndroidManifest.xml`**
   - Added `<action android:name="android.intent.action.ACTION_POWER_DISCONNECTED" />` to the
     `ScreenOnReceiver` intent-filter alongside the existing `ACTION_POWER_CONNECTED` and
     `USER_PRESENT` actions. Both `ACTION_POWER_*` are on Android's implicit-broadcast exemption
     list, so manifest registration is allowed.

8. **`app/src/test/java/com/weatherwidget/widget/ForecastFetchPolicyTest.kt`** *(new)*
   - 15 unit tests covering: the 4-cell charging matrix; off-charger delegation to
     `BatteryFetchStrategy`; the off-charger <50% null result; "off-charger ignores screen/active"
     symmetry; `periodicTickMinutes` charging/non-charging behavior; the 24-hour fallback when
     `BatteryFetchStrategy` returns null; `isDue` boundary conditions including the 2-minute grace
     window; and zero-last-fetch handling under a realistic clock.

## Test Bug Hit During Verification

The first `ForecastFetchPolicyTest` run had 14/15 pass and 1 fail:
`isDue treats zero last-fetch as overdue` used `nowMs = 1_000L`, which made
`nowMs - lastFetchTimeMs = 1_000` — less than the 60-min interval minus the 2-min grace
(3,480,000 ms). The assertion was wrong relative to the policy's arithmetic.

In real usage `System.currentTimeMillis()` is in the trillions, so `nowMs - 0` always vastly exceeds
any interval. Fixed by changing the test to use `nowMs = 1_700_000_000_000L`. Lesson: time-arithmetic
tests need realistic clock values; the function under test does not (and does not need to) special-case
`lastFetchTimeMs == 0L`.

## Verification

- `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.ForecastFetchPolicyTest"` —
  15/15 pass.
- `./gradlew testDebugUnitTest --tests "com.weatherwidget.data.repository.*" --tests
  "com.weatherwidget.widget.WeatherWidgetWorkerTest" --tests
  "com.weatherwidget.widget.WeatherWidgetProviderTest" --tests
  "com.weatherwidget.widget.ScreenOnReceiverTest"` — all pass unchanged, including
  `WeatherRepositoryRateLimitIntegrationTest`, `WeatherRepositoryTest`,
  `WeatherRepositoryNwsParallelTest`, `WeatherRepositoryStationFallbackTest`,
  `ForecastRepositoryPhantomDayTest`, `ForecastRepositoryHourlyChangeTest`,
  and all `ScreenOnReceiverTest` cases (POWER_CONNECTED debounce, USER_PRESENT, SCREEN_OFF
  charging/non-charging branches).
- `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --rerun-tasks` —
  BUILD SUCCESSFUL. Only warnings emitted, all pre-existing in test files not touched this
  session (`TemperatureGraphRendererActualsTest`, `UIUpdateReceiverTest`, etc.).

## Not Done / Trade-offs

- **No dedicated `ForecastRepository` integration test for the new staleness filter.** The policy
  itself is thoroughly unit-tested in `ForecastFetchPolicyTest`. The repo integration is a 5-line
  call in `isStale()`. The existing `WeatherRepositoryRateLimitIntegrationTest` uses ~40 lines of
  `mockk` DI setup; replicating it solely to prove "the policy gets called" would have heavy
  cost for limited additional confidence. The user's memory note on testing philosophy
  ("no mocking framework — prefer pure function extraction") matched this decision. Flagged to
  the user before skipping.
- **No on-device verification (logcat / DB).** The plan's verification section documents the
  exact commands:
  ```
  adb logcat | grep -E "FETCH_POLICY|SYNC_START|NET_FETCH_START"
  python3 scripts/backup_databases.py    # then query forecasts.batchFetchedAt per source
  adb shell dumpsys jobscheduler | grep weather_widget
  ```
  These should be run on the next deployment to confirm intervals match the matrix in practice
  and that toggling a widget's active source shifts cadence within one worker tick (≤60 min).

## Architectural Notes Worth Preserving

1. **Strangler-fig migration within a single function**: `fetchContext: ForecastFetchContext? = null`
   in `getWeatherData()` and `isStale()` lets the new policy coexist with the legacy
   `ForecastStalenessPolicy`. Worker callers get the new behavior; all other call sites (manual
   refresh, settings refresh, tests) keep the old behavior with no API break. There is no
   feature flag and no scheduled cleanup — non-context callers can migrate one at a time or stay
   on the legacy path indefinitely.

2. **`ExistingPeriodicWorkPolicy.UPDATE` is load-bearing.** With `KEEP`, the first
   `schedulePeriodicUpdate()` call after install would lock in whatever charging state existed at
   that moment; the receiver's `handlePowerConnected` / `handlePowerDisconnected` reschedule calls
   would silently no-op. `UPDATE` makes the rescheduling actually take effect.

3. **Per-source timestamps from `ForecastEntity.batchFetchedAt`, not new SharedPreferences keys.**
   The existing DB row already carries the canonical per-provider-fetch timestamp shared across
   all rows from one batch. Adding a parallel SharedPreferences store would be duplicate state
   prone to drift. The plan originally suggested `fetchedAt`; on review the correct field is
   `batchFetchedAt` (per-row `fetchedAt` exists too but represents row-write time, not the
   provider fetch).

4. **The off-charger 24-hour safety-net tick** (`OFF_CHARGER_LOW_BATTERY_TICK_MINUTES`) means the
   periodic worker still fires once a day on a sub-50% battery device even though
   `BatteryFetchStrategy.computeFetchInterval` returns null. This keeps the worker schedule alive
   so subsequent battery-level recoveries or charging-state changes are noticed promptly when the
   tick fires; an `intervalMinutes(...)` returning null inside `isStale` still means no actual
   fetch happens that tick.

## Critical File Paths

- `app/src/main/java/com/weatherwidget/widget/ForecastFetchPolicy.kt` *(new)*
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`
- `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- `app/src/main/java/com/weatherwidget/widget/ScreenOnReceiver.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/test/java/com/weatherwidget/widget/ForecastFetchPolicyTest.kt` *(new)*

## Plan File

`/home/dcar/.claude/plans/how-often-should-forecast-unified-sonnet.md`
