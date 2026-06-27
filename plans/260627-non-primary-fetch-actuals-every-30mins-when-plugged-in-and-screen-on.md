# Desktop: dedicated non-primary actuals fetch (30 min, charging + screen-on)

## Context

While debugging stale Open-Meteo actuals we mapped the desktop fetch cadence:

- The **active/displayed** source's temperature actuals refresh every **10 min on AC**
  (observation loop, `DaemonProcess.kt:244-284`, `getObservationRefreshDelayMs`).
- **Non-primary** (other visible) sources' actuals only come bundled in the non-active **forecast**
  refresh — every **120 min on AC** / 240–480 min on battery (`DaemonProcess.kt:326-366`).

So when the user toggles to a non-primary source, its pink actual line / current temp can be up to
2 h stale. The user wants the non-primary sources' **actuals specifically** kept fresher — but only
when it's cheap to do so: **every 30 min, and only while plugged in AND the screen is on.**

(Already shipped earlier by Gemini, not part of this plan: `HttpRequestRetry` on the CIO clients and
the `currentTempFetchError` failure label. Those are committed; they just need a rebuild to go live.)

## Goal

Add a dedicated **non-primary observations (actuals) loop** to the desktop daemon:
- Runs every **30 min**.
- Fetches **observations only** (`refreshObservations()`), for each **non-active** visible source.
- Gated: fires **only when `isCharging == true` AND screen is on**; otherwise skips and re-checks
  soon (so it resumes promptly when the screen wakes).

Scope: desktop only. (Android parity is a separate follow-up — see end.)

## Design

### 1. Pure scheduling decision — `DesktopFetchStrategy.kt`

Add a pure function next to the existing ones (lines 22-47), keeping the strategy testable and
side-effect-free (screen state is passed in, not probed here):

```kotlin
private const val AC_NONPRIMARY_OBSERVATION_MINUTES = 30L

/** Delay (ms) until the next non-primary actuals fetch, or null to skip this cycle. */
fun getNonPrimaryObservationDelayMs(isCharging: Boolean, screenOn: Boolean): Long? =
    if (isCharging && screenOn) AC_NONPRIMARY_OBSERVATION_MINUTES * MS_PER_MINUTE else null
```

Battery / screen-off → `null` → the loop skips and re-checks (no non-primary polling off-charger,
matching the request "while plugged in").

### 2. Screen-on detection — new `ScreenStateDetector.kt` (desktop)

Best-effort, fire-and-forget, in the style of the existing `gdbus`/`notify-send` shell-outs:

```kotlin
object ScreenStateDetector {
    /** True if the display is powered on. Best-effort; fail-open (true) when undetectable. */
    fun isScreenOn(): Boolean { /* try xset, then loginctl, else true */ }

    /** Pure parser, unit-tested: "Monitor is On" -> true; Off/Standby/Suspend -> false; else null. */
    fun parseXsetMonitorState(xsetOutput: String): Boolean?
}
```

- **Primary:** run `xset -q` (daemon has `DISPLAY=:0`), parse the `Monitor is On/Off/Standby/Suspend`
  line via `parseXsetMonitorState`. (Verified live: reports "Monitor is On".)
- **Fallback:** `loginctl show-session <id> -p LockedHint -p IdleHint` → on = not locked.
  (Verified live: `LockedHint=no`.)
- **Fail-open:** if neither tool is usable, return `true` and log once — never silently freeze data.
- Short subprocess timeout; swallow all errors (best-effort, like `notify-send` at
  `DesktopProcess.kt:236`).

### 3. New daemon loop — `DaemonProcess.kt` (add `3d`, after the forecast loop ~line 368)

Mirror the existing loop scaffolding; reuse the non-active `otherService`/`otherRepo` construction
from `DaemonProcess.kt:339-354`, but call `refreshObservations()` (actuals-only) instead of
`refresh()`:

```kotlin
// 3d. Non-primary actuals (observations) loop — 30 min, only while charging + screen on.
launch {
    while (true) {
        val (isCharging, level) = PowerDetector.getPowerState()
        val delayMs = DesktopFetchStrategy.getNonPrimaryObservationDelayMs(isCharging, ScreenStateDetector.isScreenOn())
        if (delayMs == null) { delay(SUSPEND_RECHECK_INTERVAL_MS); continue }   // re-check ~5 min
        delay(delayMs)

        val nonActive = config.visibleSources.filter { it != config.weatherSource }
        for (otherSource in nonActive) {
            try {
                val otherService = DesktopWeatherService(config.lat, config.lon, otherSource, config.apiKeys, weatherDao)
                val otherRepo = DesktopWeatherRepository(otherService, weatherDao, config.lat, config.lon, otherSource, config.personalStationWeight())
                otherRepo.refreshObservations()
                otherService.close()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                weatherDao.log("REFRESH_FAIL", "non-primary actuals $otherSource: ${if (isOfflineException(e)) "offline" else "source_error"} ${e.message}", "WARN")
            }
        }
    }
}
```

Notes:
- No overlap with loop `3b` (active source only) — `3d` covers `visibleSources` minus the active one.
- Re-check interval when gated-off uses the existing `SUSPEND_RECHECK_INTERVAL_MS` (~5 min) so the
  loop wakes the non-primary data within minutes of the screen turning back on, not a full 30 min.
- Uses the same per-source actuals path that already works for the active source, so observation
  storage / `daily_extremes` recompute / location-matching all behave identically.

## Critical files

| File | Change |
|------|--------|
| `desktop/.../DesktopFetchStrategy.kt:22` | add pure `getNonPrimaryObservationDelayMs(isCharging, screenOn)` + 30-min const |
| `desktop/.../ScreenStateDetector.kt` (new) | best-effort `isScreenOn()` (xset → loginctl → fail-open) + pure `parseXsetMonitorState` |
| `desktop/.../DaemonProcess.kt:~368` | add loop `3d`; reuse `otherService`/`otherRepo` pattern from lines 339-354 |

Reuse: `PowerDetector.getPowerState()`, `DesktopWeatherRepository.refreshObservations()`
(`:264`), `WeatherSource`, `config.visibleSources`, `SUSPEND_RECHECK_INTERVAL_MS`, `isOfflineException`.

## Verification

1. **Unit (`desktop/src/test/.../DesktopFetchStrategyTest.kt`)**: `getNonPrimaryObservationDelayMs`
   → 30 min when `(charging, screenOn)=(true,true)`; `null` for `(false,*)` and `(*,false)`.
2. **Unit** `parseXsetMonitorState`: "Monitor is On" → true; "Off"/"Standby"/"Suspend" → false;
   garbage → null.
3. **End-to-end** (`scripts/buildStart.sh` to rebuild+restart): with NWS displayed and
   OPEN_METEO/SILURIAN as non-primary, confirm an `OBS_REFRESH source=OPEN_METEO …` (and SILURIAN)
   row appears ~30 min apart in `app_logs` while plugged in; then `xset dpms force off` (or let the
   monitor sleep) and confirm the non-primary `OBS_REFRESH` rows stop, and resume after wake.
   Cross-check the non-primary observation timestamps advance in `observations` (api=OPEN_METEO).
4. **No regression**: active-source 10-min loop and the 120-min forecast loop unchanged.

## Android parity — next plan (intended, but done incrementally)

Goal is full Android+desktop parity; we're doing **desktop first**, then an Android plan
immediately after. Android is a separate plan (not padded into this one) because its mechanisms
differ and need their own Phase-1 exploration:

1. **Non-primary actuals cadence + charging/screen-on gate** → maps onto Android's WorkManager
   two-tier scheduler (not a daemon loop); "screen on" → `PowerManager.isInteractive()` /
   `ACTION_SCREEN_ON/OFF`, "charging" already battery-aware. Per the project's "share logic" rule,
   factor the 30-min decision into the existing shared strategy where possible so both platforms
   delegate.
2. **Failure indicator** (parity with desktop's `currentTempFetchError`) → surfaced on the
   **widget** (stale/last-updated or error glyph) rather than a Compose overlay; reuses the
   `CURRENT_TEMP_STATUS` signal concept.
3. **Retry parity** → confirm the Android HttpClient has equivalent `HttpRequestRetry` (desktop got
   it via Gemini); add if missing.

This plan ships item-1 for desktop; the Android plan will cover items 1–3 for Android.
