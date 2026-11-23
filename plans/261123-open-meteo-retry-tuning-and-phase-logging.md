# Open-Meteo Retry Tuning and Transport Phase Logging

## Evidence

Desktop `~/.local/share/weather-widget/weather.db` (`app_logs`) and `~/.local/state/weather-widget/autostart-*.log` (25 most recent log files, 2026-11-20 through 2026-11-23):

1. **High HTTPS timeout rate vs sub-second healthy latency:**
   - Clean HTTPS `getForecast` calls: **16** (all **200–512 ms**).
   - HTTPS timeouts (`SocketTimeoutException: socket_timeout=6000`): **13** (`getForecast` + `getCurrent`). Of the 10 `getForecast` timeouts, **3** recovered on the HTTP fallback in ~8.4 s (6 s HTTPS timeout + 2 s sleep + ~0.3 s HTTP) and **7** failed completely.
2. **Every full failure burns ~25.1 s:**
   - 6 s HTTPS socket timeout (`FORECAST_SOCKET_TIMEOUT_MS = 6_000`) + 2 s `DEFAULT_HTTP_FALLBACK_DELAY_MS` + 12 s HTTP `requestTimeoutMillis` (two 6 s socket stalls truncated at 12 s) = **~20.1 s**.
   - Plus `probeAfterTimeout` runs **synchronously** before throwing and failed **7/7 times** (`probe=failed error=HttpRequestTimeoutException probeMs=5001–5002`), adding **5.0 s** to every failure.
3. **Recent desktop recovery times (start of first request → forecast stored):**
   - `2026-11-23 12:04:05`: failed in 25.2 s → `SOURCE_RETRY 1/4` fired 55 s later → HTTPS timed out again (6 s) → 2 s wait → HTTP answered in ~270 ms → **88.6 s total**.
   - `2026-11-23 09:34:29`: failed in 25.1 s → `SOURCE_RETRY 1/4` fired 55 s later → HTTPS answered in 475 ms → **80.7 s total**.
   - `2026-11-22 23:14:26`: 3 consecutive user-triggered failures (25.1 s each) → 4th try fell back to HTTP (~300 ms) → **116.5 s total**.
   - `2026-11-22 00:25:52`: 2 failures (25.1 s each) → `SOURCE_RETRY` succeeded in 492 ms → **173.2 s total**.
   - `2026-11-20 16:06:01`: initial failure (25.0 s) → retry 1 (+1 min) failed (25.0 s) → retry 2 (+2 min) succeeded in 204 ms → **230.6 s total**.
4. **Logging blind spots:**
   - `SocketTimeoutException: socket_timeout=6000` (never `ConnectTimeoutException`) does not distinguish a stalled **TLS handshake** from a stalled **HTTP response (TTFB)** or a **specific bad DNS IP** / **HTTP/2 vs HTTP/1.1** difference.
   - `OpenMeteoApi` logs only to the console (`autostart-*.log`), never to `app_logs`, so HTTP fallback recoveries (like `12:05:33`) are invisible in `app_logs` and bug-report `logs.tsv`.
   - `SOURCE_RETRY` logs `scheduled` and `fire`, but not `recovered` when a retry succeeds.

## Root Cause

1. **Per-call budget is tuned for slow servers rather than dead connections on a sub-second API:** 6 s socket timeout, 2 s dead sleep before HTTP fallback, 12 s HTTP retry budget, and a blocking 5 s diagnostic probe that has already answered its question (0/7 `probe=ok`) stretch every failure to 25.1 s.
2. **First `SourceRetry` wait (1 min) dominates recovery for brief stalls:** In both 2026-11-23 episodes, Open-Meteo was healthy again well within a minute, so ~65–70% of the ~85 s wait was idle sleep.
3. **No OkHttp phase telemetry on failure:** Without an `okhttp3.EventListener` capturing DNS / TCP connect / TLS / request-headers / TTFB timings and remote IP on failure, we cannot tell why `https://api.open-meteo.com` times out on ~45% of calls.

## Proposed Fix

Keep behavior consistent across desktop and Android (`:shared` owns timeouts, retry schedule, and formatting; `:desktop` and `:app` wire the OkHttp listener and `app_logs` sink).

### 1. Tighten `OpenMeteoApi` timeouts and remove the blocking probe (`:shared`)

In `shared/src/main/kotlin/com/weatherwidget/data/remote/OpenMeteoApi.kt`:
- **Remove `probeAfterTimeout`** (and `PROBE_TIMEOUT_MS`). It ran 7/7 `probe=failed`, proved the whole host/path stalls, and adds 5 dead seconds to every timeout.
- **Tighten short-window timeouts** (normal responses are 200–512 ms):
  - HTTPS attempt (`noRetry()`): `FORECAST_HTTPS_CONNECT_TIMEOUT_MS = 3_000L`, `FORECAST_SOCKET_TIMEOUT_MS = 4_000L`, `FORECAST_HTTPS_REQUEST_TIMEOUT_MS = 5_000L`.
  - HTTP fallback delay: `DEFAULT_HTTP_FALLBACK_DELAY_MS = 250L` (down from `2_000L`; saves 1.75 s on every HTTPS→HTTP fallback while still avoiding an instantaneous burst).
  - HTTP fallback: `FORECAST_CONNECT_TIMEOUT_MS = 3_000L`, `FORECAST_SOCKET_TIMEOUT_MS = 4_000L`, `FORECAST_REQUEST_TIMEOUT_MS = 9_000L` (fits two 4 s socket attempts + backoff cleanly inside the 9 s budget).
  - **Result:** HTTPS→HTTP fallback recovery drops from **~8.4 s → ~4.6 s**; a full call failure drops from **25.1 s → ~13.3 s** (nearly 2× faster).
- **Persist fallback breadcrumb to `app_logs` via optional callback:**
  - Add an optional `onAppLog: (tag: String, message: String, level: String) -> Unit = { _, _, _ -> }` parameter to `OpenMeteoApi` (preserving the `@Inject` two-arg constructor or wiring `AppLogDao` in `AppModule` and `DesktopAppLogQueue` + `weatherDao.log` in `DesktopWeatherService`).
  - When `getWithHttpFallback` falls back to HTTP and **succeeds**, log `OPEN_METEO_HTTP_FALLBACK host=… httpsMs=… httpMs=… cause=…` at `WARN` to `onAppLog` (and `Log.w`). When both fail, `REFRESH_FAIL` / `CURRENT_TEMP_STATUS` already persist to `app_logs`.

### 2. Add a fast first `SourceRetry` step and log recoveries (`:shared`, `:desktop`, `:app`)

In `shared/src/main/kotlin/com/weatherwidget/shared/util/SourceRetry.kt`:
- Change `DELAYS_MS` from `[1m, 2m, 5m, 10m]` to **`[20s, 1m, 2m, 5m, 10m]`** (5 retries, 18m 20s total).
  - For brief stalls (like 2026-11-23 `09:34` and `12:04`), failing in ~13 s + retrying in 20 s cuts total recovery from **81–89 s → ~34–38 s** (or ~38 s if HTTPS falls back to HTTP).
  - For multi-minute stalls (like 2026-11-20 `16:06`), backoff still scales cleanly to 1, 2, 5, 10 min.
- Lower `DUE_GRACE_MS` from `5_000L` to **`2_000L`** so it stays well below `DELAYS_MS.min() / 4` (5 s) and doesn't fire a 20 s retry at 15 s.
- Update the `scheduled` log line from `delayMin=${delayMs / 60_000L}` to **`delaySec=${delayMs / 1_000L}`** (so 20 s logs `delaySec=20`, not `delayMin=0`).
- Add `SourceRetry.logRecovered(log, sourceId, attempt, totalMs)` emitting `SOURCE_RETRY recovered source=… attempt=…/<max> totalMs=…` at `INFO`, called on both desktop (`DaemonRuntime.runDueSourceRetries`) and Android (`FullSyncPipeline` / `ForecastFetchCoordinator` when `reason == SourceRetry.REASON` succeeds).
- Note on Android WorkManager: `AndroidSourceRetryHost` already passes `delayMs` in milliseconds to `WidgetWorkScheduler.enqueueSourceRetrySync`; verify `setInitialDelay(delayMs, TimeUnit.MILLISECONDS)` is used so 20 s is not truncated to 0 min.

### 3. Add OkHttp transport phase logging on failure (`:shared`, `:desktop`, `:app`)

To diagnose *why* `https://api.open-meteo.com` times out ~45% of the time without adding OkHttp to `:shared`:
- In `:shared`, add a small pure formatter `HttpPhaseTiming` (`shared/src/main/kotlin/com/weatherwidget/data/remote/HttpPhaseTiming.kt`) that formats one compact line:
  `HTTP_PHASE_FAIL host=… scheme=… remoteIp=… protocol=… dnsMs=… connectMs=… tlsMs=… reqHeadersMs=… ttfbMs=… totalMs=… reused=… error=…`
- In `:desktop` (`DesktopHttpClient.newOkHttpClient`) and `:app` (`AppModule.provideHttpClient`), attach an `okhttp3.EventListener.Factory` that records nanosecond timestamps per call (`dnsStart/End`, `connectStart/End`, `secureConnectStart/End`, `connectionAcquired`, `requestHeadersEnd`, `responseHeadersStart`, `callFailed`) and emits **zero logs on success**, logging `HTTP_PHASE_FAIL …` at `WARN` only in `callFailed` / `connectFailed` (to console/logcat, and on desktop also via `DesktopAppLogQueue` to `app_logs` when `weatherDao != null` so it survives in bug reports).

### 4. Living Documentation Updates

Update the single canonical doc and verify cross-links:
- `arch/forecast-and-observation-cadence.md`: update Open-Meteo timeout/fallback numbers, remove `probeAfterTimeout` mention, and update `SourceRetry` delays (`20 s, 1, 2, 5, 10 min`, 5 retries) and `DUE_GRACE_MS`.
- `arch/operations.md`: replace `OPEN_METEO_TIMEOUT_PROBE` with `HTTP_PHASE_FAIL` and `OPEN_METEO_HTTP_FALLBACK` under "Provider timeouts (HTTP client)".

## Tests

1. **`:shared` unit tests:**
   - `OpenMeteoApiTest`: update timeout/fallback tests for the new constants (`250 ms` default delay, no probe requests on timeout, `OPEN_METEO_HTTP_FALLBACK` callback invoked on HTTPS→HTTP recovery and not on clean HTTPS or double failure).
   - `SourceRetryTest`: update delay sequence assertions (`20s, 1m, 2m, 5m, 10m`, 5 retries, cap on 6th, `DUE_GRACE_MS < DELAYS_MS.min() / 4`, `delaySec=` log format, `logRecovered`).
   - `HttpPhaseTimingTest`: verify formatting for partial phases (e.g., stalled during TLS vs stalled waiting for TTFB vs reused connection).
2. **`:desktop` integration tests:**
   - Extend `DesktopHttpClientStaleConnectionTest` (or add `DesktopOkHttpPhaseLoggingIntegrationTest`) with a real local `ServerSocket` that accepts a connection and stalls without writing bytes: verify `DesktopHttpClient` emits `HTTP_PHASE_FAIL` with `remoteIp`, `connectMs`, `ttfbMs=-1`, `totalMs`, and persists it to real SQLite `app_logs`.
   - Verify `DesktopSourceRetryHostContractTest` passes with the updated `DELAYS_MS` and `DUE_GRACE_MS`.
3. **`:app` unit/Robolectric tests:**
   - Verify `AndroidSourceRetryHostContractTest` and `WidgetWorkScheduler` preserve sub-minute millisecond `initialDelay` (20 s) without rounding to 0.

## Verification

1. Run `./scripts/unit-tests.sh` (all `:shared`, `:desktop`, `:app` unit & integration tests).
2. Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh` (full cross-platform gate + Android install + desktop distributable restart).
3. Runtime check on desktop: trigger an Open-Meteo refresh (or wait for the next timeout in `~/.local/state/weather-widget/autostart-*.log` and `weather.db`), confirm normal calls remain sub-second, any HTTPS stall falls back in ~4–5 s and logs `HTTP_PHASE_FAIL` + `OPEN_METEO_HTTP_FALLBACK`, and a full timeout arms `delaySec=20`.
