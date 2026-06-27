# Android parity: non-primary actuals (30 min, charging+screen-on), fetch-failure indicator, retry

## Context

Desktop recently gained three things (retry + failure label by Gemini; the non-primary 30-min
actuals loop just now). The goal is Android+desktop feature parity. This plan brings the three to
Android. Good news from exploration: Android's fetch architecture is *richer* than desktop's and
already has the hooks needed — this is mostly slotting into existing patterns, not new subsystems.

Three parity items, smallest-to-largest:

1. **Retry parity** — Android's HTTP client lacks `HttpRequestRetry`.
2. **Non-primary actuals @30 min (charging + screen-on)** — Android's non-primary sources' actuals
   only refresh via the 120-min forecast cadence, same gap desktop had.
3. **Current-temp fetch-failure indicator on the widget** — parity with desktop's
   `currentTempFetchError` label.

## Item 1 — Retry parity (trivial)

Android builds its client in `app/.../di/AppModule.kt:72-114` (`HttpClient(OkHttp)`) with only
`HttpTimeout`/`ContentNegotiation`. Add the **same** block desktop uses (`DesktopWeatherService.kt:53`):

```kotlin
install(HttpRequestRetry) {
    maxRetries = 2
    retryOnExceptionIf { _, cause ->
        cause is io.ktor.client.network.sockets.ConnectTimeoutException ||
        cause is io.ktor.client.network.sockets.SocketTimeoutException ||
        cause is io.ktor.client.plugins.HttpRequestTimeoutException ||
        cause is java.io.IOException
    }
    exponentialDelay(base = 2.0, maxDelayMs = 2_000)
}
```
`HttpRequestRetry` is engine-agnostic (works with the OkHttp engine). One import + one block.

## Item 2 — Non-primary actuals @30 min, charging + screen-on

### 2a. Shared decision (per the share-logic rule)

Extract the identical cross-platform rule into `:shared` so both platforms delegate (no duplicated
constant). Add to a shared spot near `BatteryTier` (`shared/.../shared/util/`), e.g.
`NonPrimaryObservationPolicy`:

```kotlin
const val CHARGING_SCREEN_ON_MINUTES = 30L
/** Interval for refreshing non-primary sources' actuals, or null to not run this cycle. */
fun intervalMinutes(isCharging: Boolean, screenOn: Boolean): Long? =
    if (isCharging && screenOn) CHARGING_SCREEN_ON_MINUTES else null
```

Then:
- **Desktop**: `DesktopFetchStrategy.getNonPrimaryObservationDelayMs` delegates to this (refactor the
  code just shipped: `intervalMinutes(...)?.let { it * 60_000 }`).
- **Android**: `ForecastFetchPolicy` gains `nonPrimaryObservationIntervalMinutes(isCharging, isScreenInteractive)`
  delegating to the same shared function.

### 2b. Android scheduler + worker mode

Mirror the existing `CurrentTempUpdateScheduler` (which already does exactly this shape for the
*active* source at 10/16 min, charging-gated, managed by `ScreenOnReceiver`):

- New `NonPrimaryObservationScheduler` (or a sibling method on `CurrentTempUpdateScheduler`) that
  enqueues unique periodic-ish work at the shared 30-min interval **only when charging && screen
  interactive**, and cancels it on screen-off / power-disconnected — wired through the existing
  `ScreenOnReceiver` handlers (`handleUserPresent` / `handleScreenOff` / `ACTION_POWER_*`).
- New worker input flag `KEY_NONPRIMARY_CURRENT_TEMP_ONLY` in `WeatherWidgetWorker`: fetches
  current-temp/observations for the **non-active** visible sources only
  (`visibleSources − activeSourceIds`), reusing the same per-source current-temp fetch path that
  `KEY_CURRENT_TEMP_ONLY` uses for the active source. Constraint: `NetworkType.CONNECTED`.
- No change to the active-source 10/16-min loop or the 60/120/240 forecast cadence.

### 2c. Reuse

`ForecastFetchContext` (isCharging/isScreenInteractive/activeSourceIds), `BatteryStatePolicy.isEffectivelyCharging`,
`PowerManager.isInteractive`, the `ScreenOnReceiver` enqueue/cancel pattern, and the existing
per-source current-temp fetch in `WeatherWidgetWorker`/`ForecastRepository`.

## Item 3 — Widget current-temp fetch-failure indicator

Parity with desktop's `currentTempFetchError` (shows when the **displayed** source's current-temp
fetch is failing). Reuse Android's existing failure plumbing rather than inventing a table:

- **Detect**: add a DAO read (next to `getCurrentObservationFetchLogs`) for the latest
  current-temp fetch outcome of the **active display source** — i.e. newest `CURR_FETCH_FAIL` /
  `CURR_FETCH_EXCEPTION` for that source, newer than its last success. (Optionally also gate on
  `CurrentTemperatureResolution.isStaleEstimate`, already computed during render.)
- **Render (decided): compact glyph + tap-for-detail.** A small warning glyph (e.g. ⚠) next to the
  current temp, bound via `HeaderRemoteViewsBinder` (new `bindFetchFailureIndicator()` called from
  `TemperatureViewBinder`). Non-blocking — the graph stays visible. Tapping it shows the full detail
  as a toast (source, error class, last-good-obs age) reusing the toast/`PendingIntent` path
  `ApiSourceWarningHelper` already uses. Prefer attaching the glyph to an existing header view slot
  (or the current-temp touch zone) to avoid a layout change; only edit `widget_weather.xml` if no
  suitable slot exists.
- Scope to the temperature/hourly view and the displayed source (mirror desktop's gating); clears
  automatically when the next current-temp fetch for that source succeeds.

## Critical files

| Item | File | Change |
|------|------|--------|
| 1 | `app/.../di/AppModule.kt:72` | add `HttpRequestRetry` to `provideHttpClient()` |
| 2a | `shared/.../shared/util/NonPrimaryObservationPolicy.kt` (new) | shared 30-min decision |
| 2a | `desktop/.../DesktopFetchStrategy.kt` | delegate to shared (refactor just-shipped code) |
| 2a | `app/.../widget/ForecastFetchPolicy.kt` | add `nonPrimaryObservationIntervalMinutes(...)` delegating to shared |
| 2b | `app/.../widget/CurrentTempUpdateScheduler.kt` (or new `NonPrimaryObservationScheduler.kt`) | schedule/cancel 30-min non-primary work |
| 2b | `app/.../widget/WeatherWidgetWorker.kt` | new `KEY_NONPRIMARY_CURRENT_TEMP_ONLY` mode (non-active sources) |
| 2b | `app/.../widget/ScreenOnReceiver.kt` | enqueue on charging+unlock, cancel on screen-off/unplug |
| 3 | `app/.../widget/data/local/AppLogDao` | DAO read: latest current-temp failure for a source |
| 3 | `app/.../widget/handlers/HeaderRemoteViewsBinder.kt` + `TemperatureViewBinder.kt` | bind the indicator |
| 3 | `app/.../res/layout/widget_weather.xml` | (only if a new view is needed) |

## Verification

1. **Unit**: shared `NonPrimaryObservationPolicy.intervalMinutes` (30 when charging+screen-on, null
   otherwise); `ForecastFetchPolicy.nonPrimaryObservationIntervalMinutes` delegates correctly;
   desktop `DesktopFetchStrategy` still returns 30 min via shared. Retry predicate selects the
   timeout exception types.
2. **Instrumented / device** (`./scripts/emulator-tests.sh`; never `connectedDebugAndroidTest`):
   with NWS displayed and OPEN_METEO/SILURIAN visible, on charger + screen on, confirm non-active
   sources' current-temp/observation rows refresh ~30 min apart (logs + DB); screen off or unplug →
   the 30-min work cancels; active-source loop unaffected.
3. **Failure indicator**: simulate a current-temp fetch failure for the displayed source (e.g.
   airplane mode during a current-temp fetch) and confirm the indicator appears, taps to detail, and
   clears on recovery. Pull logcat/DB/screenshots per the project debugging workflow.
4. Build: `./gradlew installDebug`; unit: `./gradlew testDebugUnitTest`.

## Sequencing

Items are independent; suggest shipping in order 1 → 2 → 3 (smallest/safest first). Item 1 can land
immediately; item 3 is the most design-heavy and depends on the presentation decision below.
