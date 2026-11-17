# Desktop: Replace Ktor CIO with OkHttp (Open-Meteo Timeouts the Server Does Not Cause)

## Evidence
Desktop `app_logs` (`~/.local/share/weather-widget/weather.db`) and curl from the same machine, 2026-11-16:

1. **The server answers in ~0.1 s.** curl of the exact failing URL (forecast, `past_days=7`,
   `forecast_days=16`, hourly + `minutely_15` + current): 4/4 runs `ttfb≈0.11 s total≈0.13 s`,
   HTTP 200, 18 KB, IPv4 `51.81.167.44` (DNS has no AAAA record, so this is not the JVM IPv6 trap).
2. **The app times out at 15 s.** `21:44:23 REFRESH_ENTER launch:source_change` → `21:44:40`
   `HttpRequestTimeoutException ... request_timeout=15000 ms`, then the 1-min and 2-min retries ran
   early (`21:44:40`, `21:44:57`) and **both** failed the same way (~17 s each).
3. **Open-Meteo is the only affected source.** Of 7 sources on the same daemon client, 7-day
   `CURRENT_TEMP_STATUS ... HttpRequestTimeoutException` counts: `OPEN_METEO=14`, every other source 0.
   Failures arrive in bursts (11-14 20:15/20:16, 11-15 00:04/00:05, 11-16 04:32/04:34, 17:30/17:31,
   21:44/21:44/21:45).
4. Open-Meteo is the host the app calls most often in parallel (forecast, current, prior-day cloud,
   history, rain map grid), so it leaves the most idle keep-alive connections in the pool.
5. Instrumentation from `ab02c319d` (`getForecast: ... ms=`, `HttpRetry retryOnException`) is now
   live on desktop (restarted 21:47:46); the running build before that had none of it.

## Root cause (hypothesis: confirm with step 0)
Both desktop clients use Ktor CIO 2.3.7 (`DesktopWeatherService.kt:92`, `DesktopProcess.kt:329`
`DesktopClients`). CIO reuses pooled keep-alive connections without checking their health. A connection
the server or a NAT has dropped while idle then takes the request and nothing returns until the timeout.
`HttpRequestRetry` retries through the same pool, so a pool holding several dead connections to one host
fails every attempt, which matches item 2. curl opens a new connection every time, so it never sees this.

Lower timeouts (`0bb098b77`) only make the failure arrive sooner.

## Step 0 result (2026-11-17, instrumented build)
The shared `Log` (`HttpRetry`, `OpenMeteoApi`) goes to the console only
(`~/.local/state/weather-widget/autostart-20261116-214746.log`), not `app_logs`.
1. The last Open-Meteo call before the burst was `21:49:15` (`getForecast ms=345`), so the host had
   ~3 h of no traffic.
2. `00:43:40` source switch → `00:43:46.6` and `00:43:55.0` `HttpRetry host=api.open-meteo.com
   cause=SocketTimeoutException`, i.e. each attempt received **zero bytes for the full 6 s socket
   limit**. `00:43:57.2 getForecast: failed ms=16656`: the 15 s request budget kills attempt 3 at once.
3. Same pattern at `00:44:13`, `00:44:30` and `00:45:29`; then `00:45:30.2 getForecast: Raw response
   length=126424 ms=161`.
4. Since the restart: 9 `HttpRetry` events, 8 on `api.open-meteo.com`, 1 on `api.weather.gov`.

Conclusion: the server is not slow (no bytes at all, then 161 ms). The fault is in client connection
handling after idle and clears itself. These logs cannot tell **reuse of a dead pooled connection** from
**a new connection stalled in CIO's own TLS implementation** (CIO documents a 5 s idle keep-alive, which
counts against simple reuse). Both are CIO-engine faults; OkHttp (JSSE TLS, health-checked pool)
addresses both. Adjust the tests to match (see Tests 2).

## Engine comparison (2026-11-17): the engine premise did not hold
Run during implementation with temporary tests (deleted afterwards):
1. Server closes a pooled keep-alive connection, then a second request: **CIO recovered too** (6 ms, no
   `HttpRetry`), the same as OkHttp. So the stale-connection test below does not tell the engines apart.
2. 25 new TLS connections each to `api.open-meteo.com`: CIO 99–152 ms, OkHttp 146–303 ms, no stalls.
3. A connection left idle past the engine's limit: CIO opened a new one after 8 s (it closes idle ones
   after 5 s), OkHttp after 31 s. So the failing attempts were on **new** connections, not reused ones.
4. Correlation: 6 of 9 timeout bursts since 11-14 started 1–4 min after a host resume from suspend
   (`journalctl` `PM: suspend exit`: 11-14 20:12, 11-15 00:02, 18:45, 11-16 04:31, 08:10, 11-17 00:42).
   The other three (11-16 14:45, 17:30, 21:44) were source-switch fetches 10+ min after the last
   Open-Meteo call.

Remaining explanations: Open-Meteo is slow on this heavy request (`past_days=7`, 16 days, hourly +
`minutely_15`) when its cache is cold, or the network path is bad for this one host after a wake.
**User decision (2026-11-17):** keep the shared config and OkHttp (matches Android) without claiming
they fix the timeouts, and add a probe that tells the two explanations apart (item 5 below).

## Proposed fix (Claude proposal)
1. **One shared client configuration in `:shared`.** Add
   `com.weatherwidget.data.remote.WeatherHttpClientDefaults.install(config: HttpClientConfig<*>, json)`
   that installs `ContentNegotiation`, `HttpTimeout` (30 s request / 10 s connect / 15 s socket) and
   `HttpRequestRetry` (2 retries, exponential to 2 s, `HttpRetry` warning log). It depends only on
   `ktor-client-core`. `AppModule.provideHttpClient`, `DesktopWeatherService` and `DesktopClients`
   all call it. This removes three copies; the `DesktopClients` copy currently has no `HttpRetry` log.
2. **Desktop engine → OkHttp.** In `desktop/build.gradle.kts`, replace `libs.ktor.client.cio` with
   `libs.ktor.client.okhttp` (already in the catalog at the Ktor version Android uses). Add one desktop
   factory, `DesktopHttpClient.create(json, observer)`, used by both desktop clients, with:
   - `retryOnConnectionFailure(true)` (OkHttp default; set explicitly): a request on a reused
     connection the peer closed is resent on a new connection, below Ktor's retry.
   - `connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))`: evict idle connections after 30 s.
     The background loop fetches every 10+ min, so it always opens a new connection and never reuses a
     connection that may have been dropped silently with no FIN (OkHttp cannot detect that kind).
   - Platform-owned observers stay where they are (`ResponseObserver` network/API usage).
   - Remove the `DesktopUiApplication.kt:72` unused `CIO` import.
3. **Keep the Open-Meteo per-request timeouts for now** (`applyForecastTimeout`). Once step-0 data
   from the OkHttp build shows normal latency, decide separately whether to restore the 30 s budget.
4. **Docs.** Update the `shared/build.gradle.kts` engine comment (desktop: `ktor-client-okhttp`) and
   the HTTP-client section of `arch/ARCHITECTURE.md` (or `arch/operations.md` if that is where client
   behavior lives) to describe the shared defaults and the desktop pool policy.

5. **Timeout probe** in `OpenMeteoApi.getForecast` (`:shared`, both platforms): after a timeout, one
   `current=temperature_2m` request to the same host (no retry, 5 s cap), logged as
   `OPEN_METEO_TIMEOUT_PROBE forecastMs= probe= probeMs=`. Non-timeout failures send no probe; the
   forecast's own exception still surfaces.

Classification is unaffected: `isOfflineExceptionName` and `FetchErrorCode` already map OkHttp's
`UnknownHostException`. The CIO-only `UnresolvedAddressException` branch stays for compatibility.

## Tests
1. **`:shared` unit test, `WeatherHttpClientDefaultsTest`** (MockEngine): installs timeout values, retries
   twice on `SocketTimeoutException` then succeeds, and does not retry a 4xx.
2. **Desktop integration test, `DesktopHttpClientStaleConnectionTest`** (real OkHttp engine with a local
   `ServerSocket` fixture, no network). The fixture serves response 1 with `Connection: keep-alive`, then
   closes that socket and accepts a new connection for request 2. The test asserts request 2 succeeds
   within 2 s. It also asserts that after the idle period the pool is empty
   (`connectionPool.idleConnectionCount() == 0`, virtual or short real keep-alive in the test).
   Run once against CIO: it **passed** there too (see "Engine comparison"), so it guards the pool
   policy, not the engine choice.
   It covers only the stale-connection reading of step 0. The TLS-stall reading cannot be reproduced
   offline, so the runtime check below (Verification 3) is the deciding evidence for it.
3. Existing `OpenMeteoApiTest` per-request timeout tests keep passing.

## Verification
1. `./scripts/unit-tests.sh`: read the per-module summary for all modules.
2. `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh` (Android gets the shared
   config refactor, so emulator tests and install apply).
3. Desktop runtime: switch to Open-Meteo after a long idle (≥1 h, as in step 0), several times over a
   day; confirm `getForecast: ... ms=` in the autostart console log stays sub-second, no
   `HttpRetry host=api.open-meteo.com` appears, and `app_logs` has no Open-Meteo
   `HttpRequestTimeoutException` in 24 h of `CURRENT_TEMP_STATUS` (baseline 7–9/day).
