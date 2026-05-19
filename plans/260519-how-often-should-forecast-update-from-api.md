# Forecast Update Frequency While Charging

## Context

Today the forecast `WeatherWidgetWorker` runs on a hardcoded **1-hour periodic schedule** (`WeatherWidgetProvider.kt:854-858`) and fetches **all enabled sources together** on every tick. Battery state, screen state, and which source is currently *displayed* on a widget are all ignored when deciding what to fetch. `BatteryFetchStrategy.computeFetchInterval()` exists with sensible tiers but is dead code — nothing calls it from the scheduler.

We want forecast fetch cadence to scale with:
1. **Charging state** — devices on a charger can afford more network work.
2. **Screen state** — a user looking at the widget gets fresher data; a dark screen does not.
3. **Active-vs-non-active source** — the source the user is actually viewing right now refreshes more often than the others.

Stated rules while charging:

| State | Active source | Non-active sources |
|---|---|---|
| Screen on  | every 60 min  | every 120 min |
| Screen off | every 120 min | every 240 min |

Off-charger: use the existing `BatteryFetchStrategy` tiers (240 min >70%, 480 min 50–70%, none <50%) for all sources — no per-source distinction.

User-confirmed decisions:
- "Non-active" = **every other enabled source** (all 4 in `visible_sources_order` minus the currently-displayed one), all at the same slower rate.
- Off-charger uses `BatteryFetchStrategy` intervals.
- Decision is made **at worker fire time** (no separate per-source workers, no reschedule on screen events).
- This **replaces** the 1-hour periodic schedule; the global `MIN_NETWORK_INTERVAL_MS = 600_000L` rate limiter stays as a defensive floor.

## Approach

Slot the policy into the existing staleness check that already gates per-source fetches in `ForecastRepository.getWeatherData()`. The worker continues to call `getWeatherData()` once; the repo's source-filter loop becomes policy-aware. Replace the 1-hour `PeriodicWorkRequest` with a charging-aware interval, and reschedule it when charging state flips so we don't burn wakeups off-charger.

### Files to modify

1. **NEW: `app/src/main/java/com/weatherwidget/widget/ForecastFetchPolicy.kt`**
   Pure-decision object mirroring the style of `CurrentTempFetchPolicy.kt` (Android-free, unit-testable).

   ```kotlin
   object ForecastFetchPolicy {
       const val CHARGING_SCREEN_ON_ACTIVE_MINUTES = 60L
       const val CHARGING_SCREEN_ON_NONACTIVE_MINUTES = 120L
       const val CHARGING_SCREEN_OFF_ACTIVE_MINUTES = 120L
       const val CHARGING_SCREEN_OFF_NONACTIVE_MINUTES = 240L

       /** Interval for one source. null = do not fetch on this tick. */
       fun intervalMinutes(
           isCharging: Boolean,
           isScreenInteractive: Boolean,
           isActiveSource: Boolean,
           batteryLevel: Int,
       ): Long? {
           if (!isCharging) {
               // Off-charger: delegate to existing battery tiers; no per-source distinction.
               return BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = batteryLevel)
           }
           return when {
               isScreenInteractive && isActiveSource  -> CHARGING_SCREEN_ON_ACTIVE_MINUTES
               isScreenInteractive && !isActiveSource -> CHARGING_SCREEN_ON_NONACTIVE_MINUTES
               !isScreenInteractive && isActiveSource -> CHARGING_SCREEN_OFF_ACTIVE_MINUTES
               else                                   -> CHARGING_SCREEN_OFF_NONACTIVE_MINUTES
           }
       }

       /** Returns the periodic worker interval — the *shortest* possible per-source interval given current state. */
       fun periodicTickMinutes(isCharging: Boolean, batteryLevel: Int): Long {
           if (isCharging) return CHARGING_SCREEN_ON_ACTIVE_MINUTES // 60 — covers the most aggressive case
           return BatteryFetchStrategy.computeFetchInterval(false, batteryLevel) ?: 24 * 60L // 24h fallback if <50%
       }

       fun isDue(lastFetchTimeMs: Long, intervalMinutes: Long, nowMs: Long, graceMs: Long = 120_000L): Boolean {
           return nowMs - lastFetchTimeMs >= (intervalMinutes * 60_000L) - graceMs
       }
   }
   ```

2. **`app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`**
   Replace the staleness filter at lines 166–168 with a policy-driven one.

   - Read battery + charging + screen state once at the top of `getWeatherData()` (mirror what `WeatherWidgetWorker.kt:57-61` already does). Inject these as a small `FetchContext` data class so the repo isn't tightly coupled to Android — or accept them as parameters from the worker (cleaner: caller-supplied).
   - Determine the **active source set** by reading `WidgetStateManager.getCurrentDisplaySource(widgetId).id` for each `AppWidgetIds`. This list already gets built inside `WeatherWidgetWorker.kt:134-140` — extract it into a helper and pass it down. A source is "active" if it appears as *any* widget's currently-displayed source (a single source covers all widgets that are toggled to it).
   - Compute `lastFetchedAt(source)` from the existing `ForecastEntity.fetchedAt` / max(`fetchedAt`) per source in the cache (no new SharedPreferences key needed — DB rows already carry per-source timestamps).
   - Replace lines 166-168 with:
     ```kotlin
     val sourcesToFetch = enabledSources.filter { source ->
         if (shouldForceSource(source)) return@filter true
         val lastFetched = cachedForecasts.filter { it.source == source.id }.maxOfOrNull { it.fetchedAt } ?: 0L
         val interval = ForecastFetchPolicy.intervalMinutes(
             isCharging = fetchContext.isCharging,
             isScreenInteractive = fetchContext.isScreenInteractive,
             isActiveSource = source.id in fetchContext.activeSourceIds,
             batteryLevel = fetchContext.batteryLevel,
         ) ?: return@filter false
         ForecastFetchPolicy.isDue(lastFetched, interval, System.currentTimeMillis())
     }.toSet() - WeatherSource.GENERIC_GAP
     ```
   - Leave the `MIN_NETWORK_INTERVAL_MS = 600_000L` global throttle at line 78 untouched as the safety net.
   - Log the per-source decision to `appLogDao` (e.g., tag `FETCH_POLICY`) so we can debug interval drift.

3. **`app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`**
   - Build `FetchContext { isCharging, isScreenInteractive, batteryLevel, activeSourceIds }` from the already-collected battery/screen values at lines 57-61 and the active-source list at lines 134-140 (move that collection *before* the `getWeatherData()` call instead of after).
   - Pass it through `weatherRepository.getWeatherData(..., fetchContext = ...)`.
   - Tighten the cooldown at line 75 to short-circuit only if **no** source is due (cheap check via the same `ForecastFetchPolicy.isDue` logic) — currently `lastFullFetchAge in 0..300` blocks all syncs for 5 min, which is fine to keep since 60-min intervals are well over that floor.

4. **`app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`**
   - Replace `schedulePeriodicUpdate()` at lines 835-861. New version:
     ```kotlin
     fun schedulePeriodicUpdate(context: Context) {
         val battery = readBatteryState(context) // small helper, mirrors worker lines 57-60
         val tickMinutes = ForecastFetchPolicy.periodicTickMinutes(battery.isCharging, battery.level)
         val request = PeriodicWorkRequestBuilder<WeatherWidgetWorker>(tickMinutes, TimeUnit.MINUTES)
             .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
             .build()
         WorkManager.getInstance(context)
             .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
     }
     ```
     Note `ExistingPeriodicWorkPolicy.UPDATE` (was `KEEP`) so reschedules with a new interval take effect when we re-call this on charging-state change.
   - Floor enforcement: WorkManager's minimum periodic interval is 15 min — `tickMinutes` will always be ≥60 so we're safe.

5. **`app/src/main/java/com/weatherwidget/widget/ScreenOnReceiver.kt`**
   - In `handlePowerConnected()` and a new `handlePowerDisconnected()` branch (for `Intent.ACTION_POWER_DISCONNECTED`), call `WeatherWidgetProvider.schedulePeriodicUpdate(context)` to re-enqueue with the new interval.
   - Register `ACTION_POWER_DISCONNECTED` in the manifest if not already (check `AndroidManifest.xml`).

6. **`app/src/main/java/com/weatherwidget/data/repository/FetchMetadata.kt`** — **no change.** Per-source timestamps come from `ForecastEntity.fetchedAt` (already in the DB row), not from SharedPreferences.

### Tests to add

- **NEW: `app/src/test/java/com/weatherwidget/widget/ForecastFetchPolicyTest.kt`**
  - `intervalMinutes` returns 60 / 120 / 120 / 240 for the four charging cells.
  - `intervalMinutes` off-charger delegates to `BatteryFetchStrategy`.
  - `isDue` honors the 2-min grace window.
  - `periodicTickMinutes` returns 60 while charging, matches `BatteryFetchStrategy` off-charger.

- **Extend `ForecastRepositoryTest` (or whichever existing test covers `getWeatherData`)**
  - With a fake `FetchContext` for each cell of the matrix, assert which sources land in `sourcesToFetch`.
  - Verify cached `fetchedAt` < interval skips that source; > interval includes it.

## Verification

1. `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.ForecastFetchPolicyTest"`
2. `./gradlew testDebugUnitTest --tests "com.weatherwidget.data.repository.*Test"`
3. `./gradlew installDebug`, place a 2x3 widget, then watch logcat with the device on charger and screen on:
   ```bash
   adb logcat | grep -E "FETCH_POLICY|SYNC_START|NET_FETCH_START"
   ```
   Expect: active source fetches once per hour, non-active sources fetch once per two hours.
4. Toggle the widget's API indicator to a different source — within 1 worker tick (≤60 min), confirm the newly-active source begins refreshing on the active cadence and the previously-active one slips to the non-active cadence. (Pull DB to verify per-source `fetchedAt`: `python3 scripts/backup_databases.py` then `sqlite3` over `forecasts` table.)
5. Unplug charger; confirm next worker enqueue uses a 240-min (or 480-min, or none) tick via `adb shell dumpsys jobscheduler | grep weather_widget`.
6. Plug back in; confirm tick reverts to 60 min.

## Critical files

- `app/src/main/java/com/weatherwidget/widget/ForecastFetchPolicy.kt` *(new)*
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt` *(staleness filter lines 166-168, signature of `getWeatherData`)*
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt` *(lines 57-61, 113-121, 134-140)*
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` *(lines 835-861)*
- `app/src/main/java/com/weatherwidget/widget/ScreenOnReceiver.kt` *(`handlePowerConnected`, add `handlePowerDisconnected`)*
- `app/src/main/java/com/weatherwidget/widget/BatteryFetchStrategy.kt` *(reuse `computeFetchInterval` from `ForecastFetchPolicy`)*
- `app/src/test/java/com/weatherwidget/widget/ForecastFetchPolicyTest.kt` *(new)*

## Open risks

- **DB-row `fetchedAt` as timestamp source**: confirmed each `ForecastEntity` has a `fetchedAt` field. If multiple rows per source exist (one per day), use `max(fetchedAt)` per source — already shown in the snippet.
- **Active source set across widgets**: if the user has two widgets toggled to different sources, both count as "active" for that tick. This matches user intent ("the source they're viewing") for the multi-widget case.
- **`ExistingPeriodicWorkPolicy.UPDATE`**: switching from `KEEP` will cancel any in-flight run when the schedule is replaced. Acceptable, but worth flagging during code review.
