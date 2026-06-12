# Desktop: fast current-temp refresh on resume-from-suspend

## Context

**Problem:** After the laptop wakes from suspend, the desktop app's current temperature
stays stale for a long time — up to ~10 min on AC, and **4–8 hours on battery** (fully
suspended below 50%).

**Root cause (confirmed in code + live logs):**
- The daemon's three fetch loops in `DaemonProcess.kt` (`startFetchLoops`, lines 178–312)
  sleep with `kotlinx.coroutines.delay(...)`. On the JVM that schedules against
  `System.nanoTime()` → Linux `CLOCK_MONOTONIC`, which **freezes during suspend**. So a
  `delay(10 min)` that was 1 min in when the lid closed still has ~9 *awake*-minutes left
  on resume — it does **not** fire immediately.
- The catch-up logic that would fix this (`determineLaunchRefreshAction`,
  `DesktopProcess.kt:42`) only runs **once at daemon startup**, inside the first child of
  `startFetchLoops`. The daemon is long-lived and survives suspend, so it never
  re-evaluates on resume.

**Empirical proof** (`app_logs`, AC power, normal cadence ~10 min):
```
14:01:17  OBS_REFRESH
20:00:49  OBS_REFRESH      <- next one ~6h later; no LAUNCH_REFRESH_CHECK => same daemon resuming
```
Another overnight gap 05:25 → 09:02. Confirms: no restart, no resume-triggered fetch.

**Intended outcome:** On resume, the daemon detects the wake and immediately runs one
catch-up refresh, so the current temp is fresh within seconds — regardless of battery tier.

## Approach

Detect resume two ways (whichever fires first wins, debounced) and trigger a single
catch-up refresh that reuses the existing, tested launch-refresh decision.

### 1. Extract a reusable catch-up refresh (`DaemonProcess.kt`)

The startup refresh body (lines 103–176) already does exactly the right thing: load cache,
call `determineLaunchRefreshAction`, then `refresh()` / `refreshObservations()` / nothing
based on freshness. Extract it into a function so it can be called on resume too:

```kotlin
suspend fun runLaunchRefresh(repo, config, reason: String) { /* moved body of lines 103–176 */ }
```
- Call it from the startup `launch { }` (unchanged behavior).
- Add `fun kickResumeRefresh(reason)` that, guarded on non-null `repo`/`currentConfig` and a
  debounce, does `daemonScope.launch { runLaunchRefresh(repo!!, currentConfig!!, reason) }`.

After a real suspend, `determineLaunchRefreshAction` sees obs older than
`FRESHNESS_THRESHOLD_MS` (10 min) → `OBSERVATIONS`, or forecast older than
`FORECAST_FRESHNESS_THRESHOLD_MS` (1 h) → `FULL_FORECAST`. So the kick fetches exactly what's
stale and skips work if (improbably) still fresh. The periodic loops are left untouched —
the kick just adds one immediate catch-up fetch. Note this intentionally ignores the
battery-suspend gate for the single catch-up (consistent with startup), so even on low
battery the temp refreshes once on wake.

### 2. Resume detection — primary: logind D-Bus signal

In `runDaemon()` launch a best-effort subprocess on `daemonScope` (`Dispatchers.IO`):
```
gdbus monitor --system --dest org.freedesktop.login1 --object-path /org/freedesktop/login1
```
Read stdout lines; on a `PrepareForSleep (false)` line (false = waking), call
`kickResumeRefresh("logind")`. Fully fire-and-forget per the project's best-effort/
interrupt-driven preference: if `gdbus` is missing or the stream dies, log once and rely on
the fallback. Keep the `Process` handle and `destroy()` it in `quit()`.

### 3. Resume detection — fallback: wall-clock heartbeat

A coroutine that mirrors the user's own proven detector in `~/bin/sys-logging.sh`
(`RESUME_DETECT_THRESHOLD_MS = 75s`, wall-clock jump):
```kotlin
var expected = System.currentTimeMillis()
while (true) {
    delay(HEARTBEAT_INTERVAL_MS)            // 30s
    val now = System.currentTimeMillis()
    if (isSuspendJump(HEARTBEAT_INTERVAL_MS, now - expected, SUSPEND_JUMP_SLACK_MS))
        kickResumeRefresh("heartbeat")       // jump >> interval => we were suspended
    expected = now
}
```
This catches resume on systems without gdbus and as a safety net if the signal is dropped.

### 4. Debounce

Both detectors will observe the same wake (gdbus instantly, heartbeat within ~30s). In
`kickResumeRefresh`, track `lastResumeKickMs` and ignore kicks within `RESUME_DEBOUNCE_MS`
(~2 min) of the last, so a wake triggers exactly one catch-up fetch.

### Pure, testable seams (put in `DesktopProcess.kt`, alongside `determineLaunchRefreshAction`)

Matches the repo's "extract pure functions for testability" pattern (no mocking framework):
```kotlin
const val HEARTBEAT_INTERVAL_MS = 30_000L
const val SUSPEND_JUMP_SLACK_MS = 60_000L      // jump > interval + slack (~90s) => suspend; cf. sys-logging 75s
const val RESUME_DEBOUNCE_MS   = 2 * 60_000L

fun isResumeSignalLine(line: String): Boolean =
    line.contains("PrepareForSleep") && line.contains("false")

fun isSuspendJump(expectedElapsedMs: Long, actualElapsedMs: Long, slackMs: Long): Boolean =
    actualElapsedMs > expectedElapsedMs + slackMs
```

## Files to modify

- `desktop/src/main/kotlin/com/weatherwidget/desktop/DaemonProcess.kt`
  — extract `runLaunchRefresh`; add `kickResumeRefresh` (with debounce); launch gdbus-monitor
  subprocess + heartbeat coroutine in `runDaemon()`; `destroy()` the gdbus process in `quit()`.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopProcess.kt`
  — add the three constants + `isResumeSignalLine` / `isSuspendJump` pure helpers.
- `desktop/src/test/kotlin/com/weatherwidget/desktop/RefreshDelayTest.kt`
  — add unit tests for `isResumeSignalLine` (true on `...PrepareForSleep (false)`, false on
  `(true)` / unrelated lines) and `isSuspendJump` (true past slack, false within jitter).

## Verification

1. **Unit:** `./gradlew :desktop:test --tests "*RefreshDelayTest"` — pure-helper tests pass.
2. **Manual gdbus parse:** run `gdbus monitor --system --dest org.freedesktop.login1
   --object-path /org/freedesktop/login1` in a terminal, `systemctl suspend`, wake, and
   confirm the `PrepareForSleep (false)` line appears (sanity-check the parse string).
3. **End-to-end (the real test):** deploy with
   `scripts/restart-desktop-distributable.sh`, suspend the laptop for a few minutes, wake it,
   then query the daemon's own log:
   ```
   sqlite3 ~/.local/share/weather-widget/weather.db \
     "SELECT datetime(timestamp/1000,'unixepoch','localtime'), tag, substr(message,1,80)
      FROM app_logs WHERE tag IN ('OBS_REFRESH','REFRESH','LAUNCH_REFRESH_CHECK')
      ORDER BY timestamp DESC LIMIT 10;"
   ```
   Expect an `OBS_REFRESH` (or `REFRESH`) within seconds of wake instead of the old multi-
   minute/multi-hour gap.
4. **Cross-check timeline:** correlate the resume instant against the `⚡` markers in
   `~/misc/logs/sys-logging-<date>.log` — the new fetch should land right after the `⚡`.
