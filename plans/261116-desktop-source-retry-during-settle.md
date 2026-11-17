# Desktop `SOURCE_RETRY` Delayed by 5-Minute `awaitLaunch` Settle

## Evidence
Observed on desktop (`~/.local/share/weather-widget/weather.db`, `app_logs`):
1. **17:31:33 → 17:36:33 incident:**
   - `17:30:34`: `OPEN_METEO` timed out (`launch:source_change`), armed `SOURCE_RETRY` (`attempt=1/4`, `delayMs=60000`, due `17:31:34`).
   - `17:31:33`: `CONFIG_SAVE` switched `OPEN_WEATHER_MAP -> NWS` (`source_change`), restarting fetch loops.
   - `17:31:42`: `OPEN_METEO` timed out (`request:history`), armed `SOURCE_RETRY` (`attempt=2/4`, `delayMs=120000`, due **`17:33:42`**) and called `wakeForecastLoop("source_retry_armed")`.
   - `17:33:42` passed with no retry. At **`17:36:33`** (5 minutes after `17:31:33`), `FORECAST_LOOP_START` finally ran and fired `SOURCE_RETRY` (which succeeded at `17:36:37`).
   - Immediately after `17:36:37 FORECAST_LOOP_SLEEP`, `FORECAST_LOOP_WAKE reason=source_retry_armed` fired from the stale `CONFLATED` channel signal that had been sitting unhandled since `17:31:42`.
2. **14:45:21 → 14:49:56 incident (same pattern):**
   - `14:45:21`: `OPEN_METEO` timed out (`launch:source_change`), armed `SOURCE_RETRY` for `14:46:21` (`delayMs=60000`).
   - `14:49:56`: `FORECAST_LOOP_START reason=source_change endedBy=settle waitedSec=300` finally ran (3.5 min late), fired `SOURCE_RETRY` (succeeded `14:49:58`), and then logged `FORECAST_LOOP_WAKE reason=source_retry_armed` from the stale signal at `14:49:59`.

## Root Cause
In `desktop/src/main/kotlin/com/weatherwidget/desktop/LaunchRefreshJobs.kt`:
- When `forecastLoopWaitsForLaunch(reason)` is `false` (for `source_change` / `location_change`), `awaitLaunch(...)` executes an uninterruptible `delay(FORECAST_LOOP_STARTUP_SETTLE_MS)` (**5 minutes** = `300_000L`) before the forecast loop (`3c` in `DaemonRuntime.kt`) even enters `while (true)` or starts receiving from `forecastLoopWake`.
- Any `SOURCE_RETRY` armed before or during that 5-minute settle window (whose 1-minute or 2-minute deadline falls inside the 5 minutes) is ignored until the full 5-minute `delay` finishes.
- Meanwhile, `wakeForecastLoop("source_retry_armed")` buffers in `forecastLoopWake` (`Channel.CONFLATED`) and causes a redundant immediate wake after the first `FORECAST_LOOP_SLEEP`.

## Proposed Fix
1. **Make `awaitLaunch` retry-aware and wakeable during the settle window** in `desktop/src/main/kotlin/com/weatherwidget/desktop/LaunchRefreshJobs.kt` and `DaemonRuntime.kt`:
   - Add optional parameters to `awaitLaunch` (defaulting to no-ops so existing callers/tests remain compatible):
     - `wake: ReceiveChannel<String>? = null` (the `forecastLoopWake` channel)
     - `nextRetryAtMs: (() -> Long?)? = null` (returns the earliest armed `SOURCE_RETRY` timestamp across configured sources, using `sourceRetryState.retryAtMs`)
     - `nowMs: () -> Long = System::currentTimeMillis` (injectable for `runTest` virtual time)
   - During the `settle` branch (`!forecastLoopWaitsForLaunch(reason)`):
     - Compute `deadlineMs = nowMs() + settleMs`.
     - Loop while `nowMs() < deadlineMs`:
       - Check `retryAt = nextRetryAtMs?.invoke()`.
       - If `retryAt != null && retryAt <= nowMs()`, return `"source_retry"` immediately.
       - Sleep for `waitMs = minOf(deadlineMs - nowMs(), (retryAt - nowMs()).coerceAtLeast(1L))` via `withTimeoutOrNull(waitMs) { wake?.receive() }`.
       - If `wake` receives a signal (e.g. `"source_retry_armed"`), re-evaluate `nextRetryAtMs()` on the next iteration (so a newly armed 1 min / 2 min retry shortens the remaining wait without waking for non-retry signals or losing the settle guard for normal cadence fetches). If `wake` returns `null` (timeout reached) and `retryAt != null && retryAt <= nowMs()`, return `"source_retry"`; otherwise when `nowMs() >= deadlineMs`, return `"settle"`.
   - In `DaemonRuntime.kt`:
     - Pass `wake = forecastLoopWake` and `nextRetryAtMs = { earliestSourceRetryAtMs(config, System.currentTimeMillis()) }` (extracting the existing `nextRetry` lookup at lines 1500–1502 into a small helper reused in both `awaitLaunch` and the sleep calculation).
     - Note: in `while (true)` of `3c`, normal cadence fetches still respect `DesktopForecastPlanner` (which checks DB freshness), while `retryDue` runs the due `SOURCE_RETRY` on time.
2. **Tests:**
   - Add unit tests in `desktop/src/test/kotlin/com/weatherwidget/desktop/ForecastLoopStartTest.kt` (using `runTest` virtual time) covering:
     - A `SOURCE_RETRY` already armed before `source_change` cuts the 5-minute settle short at the retry's `atMs` (`endedBy = "source_retry"`).
     - A `SOURCE_RETRY` armed *during* the 5-minute settle (via `wake` channel) wakes and ends `awaitLaunch` at the retry's `atMs` instead of waiting 5 minutes, and consumes the wake signal.
     - Existing `source_change` with no pending retry still waits the full 5-minute `settle`.

## Verification
1. Run `./scripts/unit-tests.sh` (including `ForecastLoopStartTest`).
2. Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh` and verify `FORECAST_LOOP_START` / `SOURCE_RETRY` behavior in desktop logs.
