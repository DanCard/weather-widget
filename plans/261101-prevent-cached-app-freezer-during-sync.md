# Prevent Cached Apps Freezer from suspending background sync

Date: 2026-11-01. Status: implemented.

## Goal

Prevent Android's Cached Apps Freezer (`ActivityManager` / `CachedAppOptimizer`) from suspending background sync operations and tearing down in-flight TCP connections when an app has no visible Activity.

Trigger: On emulator-5554 at 23:16, a widget navigation tap queued an NWS forecast refresh ("NWS · Forecast refresh queued"), but the fetch hung for over 3 minutes, eventually displaying "Couldn't refresh NWS".

## Evidence

The sequence from `emulator-5554` logcat on 2026-11-01:

| Time | Component | Event |
|---|---|---|
| 23:16:06.794 | `DAILY_NAV_APPLY` | User taps right arrow on daily widget 63 (source: NWS) |
| 23:16:07.120 | `VIEWING_FORECAST` | NWS forecast age is 176 min (> threshold), triggers refresh |
| 23:16:07.134 | `FetchBanner` | Banner shows: `NWS · Forecast refresh queued` |
| 23:16:07.171 | `SYNC_DEFERRED_STARTUP` | Young process (`processAgeMs=10291`); `StartupCooldown` defers run by `delayMs=9573` (work ID `6ced2e17`) |
| 23:16:16.756 | `SYNC_START` | Deferred `WeatherWidgetWorker` begins execution (`reason=viewing_forecast_stale`, `force=true`) |
| 23:16:16.778 | `NET_FETCH_START` | `force=true target=NWS` dispatches HTTP GET to `api.weather.gov/points/...` |
| 23:16:17.187 | `ActivityManager` | **`freezing 24077 com.weatherwidget`** |
| 23:16:17.192 | `InetDiagMessage` | **`Destroyed 1 sockets, proto=IPPROTO_TCP ... Destroyed live tcp sockets for uids={10220} in 2ms`** |
| 23:19:27.257 | `ActivityManager` | **`sync unfroze 24077 com.weatherwidget for 3`** (woken up 190s later by `ui_update_alarm`) |
| 23:19:27.317 | `FETCH_NWS_FAIL` | `source=NWS error=Request timeout has expired [url=https://api.weather.gov/points/..., request_timeout=30000 ms]` |
| 23:19:27.407 | `NET_FETCH_COMPLETE`| `durationMs=190646 sources=NWS` |
| 23:19:28.177 | `FetchBanner` | Banner updates: `Couldn't refresh NWS` |

Process state at the time of freezing (`dumpsys activity processes`):
* `cached=true empty=true`
* `oom adj: curRaw=905 setRaw=905 cur=905`
* `isFreezeExempt=false isPendingFreeze=false isFrozen=true`

JobScheduler state (`dumpsys jobscheduler`):
* `START: #u0a220/4554 ... SystemJobService`
* `STOP: #u0a220/4554 ... SystemJobService app called jobFinished` (4 ms after start)

## Root cause

1. **GreedyScheduler vs. SystemJobService race**:
   When WorkManager enqueues work in an active process, both `GreedyScheduler` (in-process) and `SystemJobScheduler` (OS-level) are scheduled.
   When the initial delay expires, `GreedyScheduler` starts `WeatherWidgetWorker` on its coroutine dispatcher without acquiring a system `WakeLock`.
   When Android's `JobScheduler` dispatches `SystemJobService.onStartJob()`, WorkManager sees the work is already executing via `GreedyScheduler`, so `SystemJobService` returns `false` and calls `jobFinished()` immediately (4 ms).
2. **OS treats process as empty/cached**:
   With `SystemJobService` unbound and no foreground components or wake locks held, Android classifies `com.weatherwidget` as `CACHED_EMPTY` (`oom_adj: 905`).
3. **Cached Apps Freezer kills TCP sockets**:
   On Android 12+ (API 31+), `ActivityManager`'s `CachedAppOptimizer` freezes cached processes within ~400 ms. As part of freezing, `InetDiagMessage` forcibly destroys all open TCP sockets to prevent wakeups.
4. **Resumed process fails with timeout**:
   When the process is thawed minutes later by an alarm, the HTTP client's elapsed wall-clock time exceeds the 30-second request timeout on the dead socket, resulting in a timeout failure and `"Couldn't refresh NWS"`.

## Proposed Fix

### 1. Acquire a `PowerManager.WakeLock` with a safety timeout in `WeatherWidgetWorker`
Wrap non-UI-only sync runs in `WeatherWidgetWorker.doWork()` with a `PARTIAL_WAKE_LOCK`:
* Acquire a partial wake lock with a conservative safety timeout (e.g., 60 seconds) before starting `fullSyncPipeline.run` or background observation backfills.
* Release the wake lock in a `finally` block when work completes or throws.
* **Effect on OS**: Android's `PowerManagerService` reports active wake locks to `CachedAppOptimizer`, setting `isFreezeExempt = true` on the process record. The OS will not freeze the process or sever its TCP sockets while the wake lock is held.
* Permission `android.permission.WAKE_LOCK` is already granted to the app (`granted=true`).

### 2. Set Network Constraints on deferred and forced sync requests
In `WidgetWorkScheduler.kt`:
* In `enqueueStartupDeferred`: add `setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())`.
* In `enqueueForcedSync`: pass `NetworkType.CONNECTED` constraints to `enqueueFullSync`.
* This ensures that JobScheduler recognizes these requests as network-requiring jobs rather than unconstrained background work.

### 3. Lower Startup Cooldown from 16s to 6s
In `StartupCooldown.kt`:
* Reduce `COOLDOWN_MS` to `6_000L` and `INTERACTION_QUIET_MS` to `6_000L`.
* Initial cold-start cache paints finish in < 2 seconds (`total=1723ms` measured on emulator).
* This eliminates unnecessary 10–20 second deferrals when users tap or navigate widgets shortly after process launch.

### 4. Clarify Enqueue and Worker Start Logs
* In `WidgetWorkScheduler.kt`, distinguish targeted syncs from all-source syncs: log `"Targeted sync (target=$targetSourceId)"` when `targetSourceId != null` instead of generic `"Full sync"`.
* In `WeatherWidgetWorker.kt`, include `target=${input.targetSourceId ?: "all"}` in the `SYNC_START` log row.

## Testing & Verification

1. **Unit & Robolectric Tests**:
   * Add a test verifying `WeatherWidgetWorker` acquires and releases the wake lock during `doWork()`.
   * Verify `WidgetWorkScheduler.enqueueStartupDeferred` and `enqueueForcedSync` produce requests with `NetworkType.CONNECTED`.
   * Run `./gradlew testDebugUnitTest` across `:app`.
2. **On-Device / Emulator Verification**:
   * Trigger a forced refresh during startup cooldown on `emulator-5554`.
   * Monitor `adb logcat | grep -E "ActivityManager|freezing|NET_FETCH|SyncDoneNotice"`.
   * Verify `isFreezeExempt=true` in `adb shell dumpsys activity processes com.weatherwidget` during the fetch.
   * Verify the fetch succeeds within 1-3 seconds and displays `"<source> refreshed"` instead of `"Couldn't refresh <source>"`.
