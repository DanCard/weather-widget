# Unify Shared `AppLog`, OkHttp Phase Listener, and HTTP Error Telemetry

## Evidence

Running `scripts/open-meteo-stats.py --days 4` across Desktop, Pixel 7 Pro (`2A191FDH300PPW`), Galaxy Z Fold 4 (`RFCT70G352Y`), and `emulator-5554` exposed three cross-platform logging divergences:

1. **Duplicate OkHttp phase listener (~240 lines across two copies):**
   - `desktop/.../DesktopHttpClient.kt` (`DesktopHttpPhaseListener`) and `app/.../AndroidHttpPhaseListener.kt` (`AndroidHttpPhaseListener`) are identical 120-line `okhttp3.EventListener` state machines duplicated only because `:shared` did not depend on `ktor-client-okhttp`—even though both `:app` and `:desktop` already ship the exact same `ktor-client-okhttp` engine.
   - Desktop persisted `HTTP_PHASE_FAIL` to `app_logs`, while Android only wrote to `logcat` (which rolls over quickly and never reaches bug-report `logs.tsv`).
2. **Desktop `api_usage_stats` misses transport errors (`err=0` despite 18 timeouts):**
   - Desktop (`DesktopHttpClient.kt`) recorded `api_usage_stats` inside `ResponseObserver`, which only fires when an HTTP response arrives—so timeouts and dropped sockets never incremented `callCount` or `errorCount` (`forecast=122 (err=0)` despite 18 `HttpRequestTimeoutException` rows).
   - Android (`AppModule.kt`) uses `HttpSend.intercept`, which catches thrown exceptions and counts them (`Pixel 7 Pro: forecast=34 (err=12)`, `SM-F936U1: forecast=68 (err=9)`).
3. **Different fetch-outcome breadcrumbs in `app_logs`:**
   - Desktop logs `CURRENT_TEMP_STATUS source=… ok=true|false` on every fetch; Android does not (`CURRENT_TEMP: ok=0, fail=0`), so Android timeout streaks in `app_logs` could not be correlated with a recovery timestamp prior to `SOURCE_RETRY recovered`.
4. **Constructor-threaded `onAppLog` lambdas in `:shared`:**
   - `:shared` already has a pluggable `Log.install(sink)` for console/logcat, but no equivalent for persistent `app_logs`, forcing shared classes like `OpenMeteoApi` to accept platform-wired `onAppLog` callbacks.

## Root Cause

Persistent `app_logs` writes and HTTP-client telemetry were wired per-platform instead of behind a single `:shared` sink and shared HTTP helper.

## Proposed Fix

### 1. Shared `AppLog` persistent sink (`:shared`, `:app`, `:desktop`)

- Add `shared/src/main/kotlin/com/weatherwidget/shared/util/AppLog.kt` mirroring `Log.kt`:
  - `fun interface Sink { fun log(tag: String, message: String, level: String) }`
  - `install(sink: Sink)`, `resetToDefault()` (default no-op so pure unit tests need no DB), and `d/i/w/e(tag, msg)` helpers (never VERBOSE—preserving the rule that VERBOSE stays out of `app_logs`).
- Wire once at startup on each platform:
  - **Android (`AppModule.provideAppLogDao`):** `AppLog.install { tag, msg, level -> scope.launch { dao.log(tag, msg, level) } }` (on the existing background IO scope).
  - **Desktop (`DaemonRuntime` / `DesktopProcess`):** `AppLog.install { tag, msg, level -> DesktopAppLogQueue.write("app_log") { weatherDao.log(tag, msg, level) } }`.
- Simplify `OpenMeteoApi`: remove the `onAppLog` constructor parameter (and the wiring in `AppModule` and `DesktopWeatherService`); call `AppLog.w(HTTP_FALLBACK_LOG_TAG, msg)` directly when HTTP fallback succeeds.

### 2. Shared OkHttp `HttpPhaseEventListener` (`:shared`, `:app`, `:desktop`)

- Add `implementation(libs.ktor.client.okhttp)` to `shared/build.gradle.kts` (both `:app` and `:desktop` already depend on it; `:shared` remains pure JVM with zero Android dependencies).
- Add `shared/src/main/kotlin/com/weatherwidget/data/remote/HttpPhaseEventListener.kt` (one shared implementation using `HttpPhaseTiming`, emitting `Log.w` + `AppLog.w` on `callFailed` only; zero logs on success).
- Delete `AndroidHttpPhaseListener.kt` and `DesktopHttpPhaseListener`, and wire `eventListenerFactory { HttpPhaseEventListener() }` in both `AppModule.provideHttpClient` and `DesktopHttpClient.newOkHttpClient`.
  - Both Android and desktop now persist `HTTP_PHASE_FAIL` to `app_logs` (and bug-report `logs.tsv`) identically.

### 3. Shared `HttpSend` API usage & error accounting (`:shared`, `:app`, `:desktop`)

- Add `WeatherHttpClientDefaults.installApiUsageInterceptor(client, onCall)` in `shared/src/main/kotlin/com/weatherwidget/data/remote/WeatherHttpClientDefaults.kt` using `client.plugin(HttpSend).intercept { ... }`:
  - Classifies each request via `ApiUsageClassifier.classify(...)`, catches thrown transport exceptions (`isError = true`, `isQuotaRefusal = false`), and records completed responses via `ApiUsageClassifier.isError(status)` / `isQuotaRefusal(status)`.
- Add `DesktopWeatherDao.logApiCall(date: Long, apiSource: String, endpoint: String, isError: Boolean, isQuotaRefusal: Boolean)` (keeping the `status: Int` overload delegating to it).
- Replace the duplicated `HttpSend` block in `AppModule.kt` and the `api_usage_stats` call inside `DesktopHttpClient.kt`'s `ResponseObserver` with `WeatherHttpClientDefaults.installApiUsageInterceptor(...)`.
  - Desktop `ResponseObserver` remains only for `recordNetworkUsage(totalBytes, isForeground)`.
  - Desktop `api_usage_stats` now counts timeouts/transport failures identically to Android.

### 4. Unified `FORECAST_FETCH` outcome log (`:shared`, `:app`, `:desktop`, `scripts/`)

- Add `ForecastFetchLog` (`shared/src/main/kotlin/com/weatherwidget/data/remote/ForecastFetchLog.kt`):
  - `TAG = "FORECAST_FETCH"`
  - `logSuccess(sourceId: String, elapsedMs: Long)` → `AppLog.i(TAG, "source=$sourceId ok=true ms=$elapsedMs")`
  - `logFailure(sourceId: String, elapsedMs: Long, errorCode: String?, errorClass: String)` → `AppLog.w(TAG, "source=$sourceId ok=false ms=$elapsedMs code=${errorCode ?: "UNKNOWN"} class=$errorClass")`
- Call it from Android's `ForecastFetchCoordinator.fetchWithTracking` (for forecast `RawFetch` calls) and Desktop's `DesktopWeatherRepository.refresh`.
- Update `scripts/open-meteo-stats.py` to read `FORECAST_FETCH` (in addition to `CURRENT_TEMP_STATUS` / `SOURCE_RETRY`) so recovery episodes and success/failure counts work identically on Android and desktop.

### 5. Living Documentation Updates

- `arch/operations.md`: update "Provider timeouts (HTTP client)" to reference the single shared `HttpPhaseEventListener` (persisting `HTTP_PHASE_FAIL` to `app_logs` on both platforms), `AppLog`, and `FORECAST_FETCH`.
- `arch/ARCHITECTURE.md` (if it mentions `shared` HTTP dependencies or `Log`): note `AppLog` and shared `ktor-client-okhttp` helper.

## Tests

1. **`:shared` unit tests:**
   - `AppLogTest`: verify default no-op, `install`, `d/i/w/e` levels, and `resetToDefault`.
   - `WeatherHttpClientDefaultsTest`: verify `installApiUsageInterceptor` records both HTTP 200, HTTP 429 (`isQuotaRefusal = true`), and thrown `SocketTimeoutException` (`isError = true`) via `MockEngine`.
   - `OpenMeteoApiTest`: verify `OPEN_METEO_HTTP_FALLBACK` is emitted via `AppLog` without constructor callback plumbing.
   - `ForecastFetchLogTest`: verify `FORECAST_FETCH` success/failure formatting via `AppLog`.
2. **`:desktop` integration tests:**
   - Update `DesktopHttpClientStaleConnectionTest`: verify real-socket stall emits `HTTP_PHASE_FAIL` via `AppLog` into real SQLite `app_logs` AND increments `api_usage_stats.errorCount` for `OPEN_METEO` on timeout (catching the old `ResponseObserver` bug).
3. **`:app` unit/Robolectric tests:**
   - Verify `AppModule` / `AndroidSourceRetryHostContractTest` compile and pass cleanly with `HttpPhaseEventListener` and `WeatherHttpClientDefaults.installApiUsageInterceptor`.

## Verification

1. Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
2. Run `./scripts/open-meteo-stats.py --days 4` to verify clean output across Desktop and connected Android devices.
