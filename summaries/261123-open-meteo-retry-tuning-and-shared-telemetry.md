# Open-Meteo Retry Tuning, Shared Telemetry, and Cross-Platform Stats Script

**Date:** 2026-11-23
**Plans:**
- [plans/261123-open-meteo-retry-tuning-and-phase-logging.md](../plans/261123-open-meteo-retry-tuning-and-phase-logging.md)
- [plans/261123-unify-shared-applog-and-http-telemetry.md](../plans/261123-unify-shared-applog-and-http-telemetry.md)

**Commits:** `c5b6b8e9d`, `d705e8ec7` (plus uncommitted `scripts/open-meteo-stats.py` table/parallelism polish)

## User prompts

1. "Desktop: tell me about the retry strategy when meteo times out. Does retry strategy feel optimal to you? Do we need more logging? Tell me about last few times meteo timed out and how long it took to recover. Can we recover more quickly?"
2. "yes" (write plan for retry tuning and phase logging)
3. "yes" (implement retry tuning and phase logging plan)
4. "desktop: worked surprising well this time or do we have to wait to try it several times before knowing if it works well?"
5. "commit"
6. "What do you think about creating a script to parse Open meteo calls and print stats about success, failures, retries, etc... Or I wonder if it would be overkill to create an app database that kinds of tracks this info."
7. "Script should also fetch data from adb attached devices."
8. "What do you think about unifying how desktop and android log and maximizing shared code?"
9. "yes" (write plan to unify shared `AppLog`, OkHttp phase listener, and HTTP telemetry)
10. "yes" (implement unification plan)
11. "commit"
12. "I'd like for ./scripts/open-meteo-stats.py to output a summary of statistics table"
13. "Would the script be faster if outputted the table at end?"
14. "yes" (move summary table to end and parallelize adb device pulls)
15. "write a session summary to summaries/ dir"

## Findings from live logs & DB (2026-11-20 – 2026-11-23)

1. **Per-call failure burned ~25.1 s on a sub-second API:** healthy Open-Meteo HTTPS forecasts answered in **200–512 ms**, while a timeout burned 6 s (HTTPS socket) + 2 s (fallback sleep) + 12 s (HTTP request cap) + **5 s blocking `probeAfterTimeout`** (which failed 7/7 times).
2. **Slow initial recovery:** the 1-minute first `SourceRetry` step dominated recovery for brief stalls (`2026-11-23 12:04`: **88.6 s**; `09:34`: **80.7 s**; `11-22 23:14`: **116.5 s**; `11-22 00:25`: **173.2 s**; `11-20 16:06`: **230.6 s**).
3. **Cross-platform telemetry gaps exposed by the new stats script:**
   - Desktop's `ResponseObserver` missed thrown timeouts in `api_usage_stats` (`forecast=122 (err=0)` despite 18 timeouts), while Android's `HttpSend` interceptor counted them (`Pixel 7 Pro: forecast=34 (err=12)`, `SM-F936U1: forecast=68 (err=9)`).
   - Desktop and Android duplicated a 120-line OkHttp `EventListener`, and Android only logged `HTTP_PHASE_FAIL` to rolling `logcat` rather than `app_logs`.
   - Android lacked a persistent per-source forecast outcome row matching desktop's `CURRENT_TEMP_STATUS`.

## Changes

### `:shared`
1. [OpenMeteoApi.kt](../shared/src/main/kotlin/com/weatherwidget/data/remote/OpenMeteoApi.kt): removed blocking 5 s `probeAfterTimeout`; tightened HTTPS (`3 s / 4 s / 5 s`, `noRetry()`), HTTP fallback delay (`250 ms`), and HTTP fallback (`3 s / 4 s / 9 s`); emits `OPEN_METEO_HTTP_FALLBACK` to `AppLog.w` on fallback recovery.
2. [SourceRetry.kt](../shared/src/main/kotlin/com/weatherwidget/shared/util/SourceRetry.kt): changed `DELAYS_MS` to `[20 s, 1 m, 2 m, 5 m, 10 m]` (5 retries), lowered `DUE_GRACE_MS` to `2 s`, switched `scheduled` log to `delaySec=`, and added `logRecovered(...)`.
3. [AppLog.kt](../shared/src/main/kotlin/com/weatherwidget/shared/util/AppLog.kt): added shared persistent `app_logs` sink facade (`install`, `resetToDefault`, `d/i/w/e`, drops `VERBOSE`), mirroring `Log.kt`.
4. [HttpPhaseTiming.kt](../shared/src/main/kotlin/com/weatherwidget/data/remote/HttpPhaseTiming.kt) & [HttpPhaseEventListener.kt](../shared/src/main/kotlin/com/weatherwidget/data/remote/HttpPhaseEventListener.kt) (with `ktor-client-okhttp` in [shared/build.gradle.kts](../shared/build.gradle.kts)): single shared OkHttp `EventListener` emitting `HTTP_PHASE_FAIL` (`host`, `scheme`, `remoteIp`, `protocol`, `dnsMs`, `connectMs`, `tlsMs`, `reqHeadersMs`, `ttfbMs`, `totalMs`, `reused`, `error`) to `Log.w` and `AppLog.w` on failure only.
5. [WeatherHttpClientDefaults.kt](../shared/src/main/kotlin/com/weatherwidget/data/remote/WeatherHttpClientDefaults.kt) & [DesktopWeatherDao.kt](../shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt): added shared `installApiUsageInterceptor` (`HttpSend`) and boolean `isError`/`isQuotaRefusal` `logApiCall` overload.
6. [ForecastFetchLog.kt](../shared/src/main/kotlin/com/weatherwidget/data/remote/ForecastFetchLog.kt): uniform `FORECAST_FETCH source=… ok=true|false ms=…` persistent breadcrumb.

### `:desktop` & `:app`
1. [DesktopHttpClient.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopHttpClient.kt) & [AppModule.kt](../app/src/main/java/com/weatherwidget/di/AppModule.kt): wired shared `HttpPhaseEventListener` and `WeatherHttpClientDefaults.installApiUsageInterceptor`; deleted duplicated platform phase listeners.
2. [DaemonProcess.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DaemonProcess.kt), [DesktopUiApplication.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopUiApplication.kt), & [AppModule.kt](../app/src/main/java/com/weatherwidget/di/AppModule.kt): installed `AppLog` sink once at startup (`DesktopAppLogQueue` on desktop, `AppLogDao` on Android).
3. [DaemonRuntime.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DaemonRuntime.kt), [FullSyncPipeline.kt](../app/src/main/java/com/weatherwidget/widget/FullSyncPipeline.kt), [DesktopWeatherRepository.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt), & [ForecastFetchCoordinator.kt](../app/src/main/java/com/weatherwidget/data/repository/ForecastFetchCoordinator.kt): wired `SourceRetry.logRecovered(...)` and `ForecastFetchLog`.

### Tooling, tests, & living docs
1. [scripts/open-meteo-stats.py](../scripts/open-meteo-stats.py): read-only Python script querying Desktop (`weather.db` + `autostart-*.log`) and all connected `adb` devices (`run-as` Room DB + `logcat -d`) in parallel via `ThreadPoolExecutor`, streaming per-platform details and printing an aligned summary table at the end (`--days`, `--since`, `--summary-only`, `--no-desktop`, `--no-devices`, `--serial`).
2. Updated [arch/forecast-and-observation-cadence.md](../arch/forecast-and-observation-cadence.md) and [arch/operations.md](../arch/operations.md).
3. Added/updated unit & integration tests: `AppLogTest`, `ForecastFetchLogTest`, `HttpPhaseTimingTest`, `OpenMeteoApiTest`, `SourceRetryTest`, `WeatherHttpClientDefaultsTest`, `ForecastLoopStartTest`, and `DesktopHttpClientStaleConnectionTest` (real-socket + real-SQLite `HTTP_PHASE_FAIL` integration test).

## Verification

1. `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`:
   - **7,330** JVM unit/integration tests passed (`:shared` 3,653, `:desktop` 980, `:app` 2,697).
   - **130** Android emulator tests passed on `emulator-5554 (Generic_Foldable_API36)`.
   - `installDebug` succeeded on 3 devices; dex method budget clean (`0` of 28,845 over limit).
   - Desktop distributable built and restarted cleanly.
2. `time ./scripts/open-meteo-stats.py --days 4`: completed in **3.74 s** (down from ~9–10 s sequential) across Desktop, Pixel 7 Pro, SM-F936U1, and `emulator-5554`.
