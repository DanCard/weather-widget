# Open-Meteo Per-Request Timeout

## Evidence
- **Current configuration:** All three `HttpClient` instances—Android (`app/src/main/java/com/weatherwidget/di/AppModule.kt`) and desktop (`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt`, `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopProcess.kt`)—install `HttpTimeout` with:
  - `requestTimeoutMillis = 30_000`
  - `connectTimeoutMillis = 10_000`
  - `socketTimeoutMillis = 30_000`
  - `HttpRequestRetry`: `maxRetries = 2`, `exponentialDelay(base = 2.0, maxDelayMs = 2_000)`
- **Observed desktop timeouts (`~/.local/share/weather-widget/weather.db`, `app_logs`):**
  - 2026-11-16 (through 14:47): 18 ok, **4** `HttpRequestTimeoutException` timeouts
  - 2026-11-15: 68 ok, **3** timeouts, 4 other errors
  - 2026-11-14: 48 ok, **2** timeouts
  - 2026-11-13: 14 ok, 0 timeouts
- **Observed healthy latency (`~/.local/state/weather-widget/autostart-202611*.log`, Nov 13–16, 27 matched `OPEN_METEO` `getForecast` calls):**
  - Min: 206 ms, Median: **253 ms**, p90: 323 ms, p95: 342 ms, Max: 408 ms.
- **Exception already in repo:** `RainGridRepository` (`shared/src/main/kotlin/com/weatherwidget/shared/rainmap/RainGridRepository.kt`) already overrides its Open-Meteo grid request to `REQUEST_TIMEOUT_MS = 45_000L` and disables `HttpRequestRetry` (`noRetry()`).

## Root Cause / Problem
1. Because `OpenMeteoApi` (`shared/src/main/kotlin/com/weatherwidget/data/remote/OpenMeteoApi.kt`) uses the shared client's 30 s `requestTimeoutMillis`, any stalled Open-Meteo forecast/current request hangs for the full **30 seconds** before failing and handing off to `SOURCE_RETRY` (which schedules a 1-minute retry). Most recent desktop timeouts occurred on interactive `source_change` or `settings` refreshes.
2. In the shared `HttpClient` setup, `HttpTimeout` is installed *before* `HttpRequestRetry`. When a single attempt hangs for 30 s, the outer `requestTimeoutMillis = 30_000` expires (`14:45:21.956 E/DaemonProcess: Refresh failed: Request timeout has expired [..., request_timeout=30000 ms]`) without giving Ktor's `HttpRequestRetry` a chance to retry on a fresh socket.

## Proposed Fix
1. **Add a per-request timeout override in `OpenMeteoApi.kt` that gives Ktor `HttpRequestRetry` room to retry a stalled socket** (matching the `httpClient.pluginOrNull(HttpTimeout) != null` guard pattern used in `RainGridRepository.kt` and `RadarRepository.kt` so test clients without `HttpTimeout` continue to work):
   - Define:
     - `FORECAST_CONNECT_TIMEOUT_MS = 5_000L` (5 s per connect attempt)
     - `FORECAST_SOCKET_TIMEOUT_MS = 6_000L` (6 s per read/inactivity stall — >17× the 342 ms p95)
     - `FORECAST_REQUEST_TIMEOUT_MS = 15_000L` (15 s total budget across attempts)
   - With `socketTimeoutMillis = 6_000L` < `requestTimeoutMillis = 15_000L`, a stalled Open-Meteo attempt throws `SocketTimeoutException` at ~6 s instead of hanging the full `requestTimeoutMillis`, allowing the client's `HttpRequestRetry` (`maxRetries = 2`, `maxDelayMs = 2_000`) to immediately open a fresh attempt within the 15 s budget. If the retry also stalls, the call fails at ~14–15 s (half the current 30 s) and hands off to `SOURCE_RETRY`.
   - Apply this override to the three short-window endpoints:
     - `getForecast` (`$BASE_URL/forecast`)
     - `getCurrent` (`$BASE_URL/forecast`)
     - `getPriorDayCloudForecast` (`$PREVIOUS_RUNS_URL/forecast`)
   - Leave `getHistoricalDailyTemps` (`$ARCHIVE_URL/archive`, 20-year climate-normals fetch) on the client's default timeout since archive queries can legitimately take longer.
2. **Lower global `socketTimeoutMillis` from `30_000` to `15_000` (keeping `requestTimeoutMillis = 30_000` and `connectTimeoutMillis = 10_000`)** in all three shared `HttpClient` configurations:
   - `app/src/main/java/com/weatherwidget/di/AppModule.kt`
   - `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt`
   - `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopProcess.kt`
   - This ensures **all** sources (not just Open-Meteo) get an immediate Ktor retry on a stalled socket within the 30 s `requestTimeoutMillis` budget, without shrinking the 30 s total budget for slower upstreams like NWS. (`RainGridRepository` and `RadarRepository` continue to use their own per-request `socketTimeoutMillis` overrides.)
3. **Tests:**
   - Update/add a unit test in `shared/src/test/kotlin/com/weatherwidget/data/remote/OpenMeteoApiTest.kt` (using Ktor `MockEngine` with `HttpTimeout` and `HttpRequestRetry` installed) verifying that:
     - `OpenMeteoApi` forecast/current/previous-runs requests configure `requestTimeoutMillis = 15_000L`, `socketTimeoutMillis = 6_000L`, and `connectTimeoutMillis = 5_000L`, while `getHistoricalDailyTemps` does not override them.
     - A simulated `SocketTimeoutException` on attempt 1 immediately retries and succeeds on attempt 2.

## Verification
1. Run `./scripts/unit-tests.sh` (all JVM unit tests across `:shared`, `:app`, `:desktop`).
2. Verify on desktop (`./gradlew :desktop:run` or `./scripts/buildStart-desktop.sh`) that Open-Meteo forecast fetches succeed normally and any stalled socket retries within 6 s / fails within 15 s instead of hanging 30 s.
