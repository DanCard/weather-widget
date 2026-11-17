# Open-Meteo HTTPS-First with HTTP Fallback Retry

## Evidence
- Open-Meteo HTTPS requests (`https://api.open-meteo.com/v1/forecast`) continue to experience intermittent timeouts across desktop (`00:44:30` `ms=16732`, `00:45:29` `ms=16624` on 2026-11-17), Fold 4 (`08:21:37` `durationMs=22730`, `08:22:58` `durationMs=20218` on 2026-11-17), and emulator.
- Verified with `curl -I`:
  - `http://api.open-meteo.com/v1/forecast?...` returns `HTTP/1.1 200 OK` (no HTTPS redirect).
  - `http://previous-runs-api.open-meteo.com/v1/forecast?...` returns `HTTP/1.1 200 OK` (no HTTPS redirect).
- Currently [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) has no `networkSecurityConfig` or `usesCleartextTraffic`, so Android API 28+ blocks cleartext `http://` requests unless explicitly permitted for Open-Meteo's domains.

## Proposed Fix
1. **Shared HTTPS-first → delayed HTTP fallback in [OpenMeteoApi.kt](../shared/src/main/kotlin/com/weatherwidget/data/remote/OpenMeteoApi.kt):**
   - Add a shared helper `getWithHttpFallback(httpsUrl: String, block: HttpRequestBuilder.() -> Unit): String`:
     - First attempts `httpClient.get(httpsUrl, block)`.
     - On `CancellationException`, rethrows immediately.
     - On transient transport/TLS/timeout failures (`HttpRequestTimeoutException`, `ConnectTimeoutException`, `SocketTimeoutException`, `SSLException`, `IOException`), logs `Log.w(TAG, "https failed, retrying over http in ${httpFallbackDelayMs}ms: ...")`, waits `httpFallbackDelayMs` (default **`2_000L` ms**, injectable constructor/companion or internal parameter for fast unit tests), and retries once against `httpsUrl.replaceFirst("https://", "http://")`.
     - If the HTTP fallback also fails (or the HTTPS error is a non-transport/HTTP status error), throws so existing `SOURCE_RETRY` handles longer-term backoff.
   - Use `getWithHttpFallback` for:
     - `getForecast` (`$BASE_URL/forecast`)
     - `getCurrent` (`$BASE_URL/forecast`)
     - `getPriorDayCloudForecast` (`$PREVIOUS_RUNS_URL/forecast`)
   - Keep `getHistoricalDailyTemps` (`$ARCHIVE_URL/archive`) on HTTPS (infrequent 20-year climate-normals fetch), or include `archive-api.open-meteo.com` if desired (out of scope by default).
   - **API Usage classification check:** verify `ApiUsageClassifier` (`shared/`) classifies by host (`api.open-meteo.com` / `previous-runs-api.open-meteo.com`) regardless of `http` vs `https` scheme.
2. **Android cleartext domain exception (scoped strictly to Open-Meteo):**
   - Add `app/src/main/res/xml/network_security_config.xml`:
     - `<base-config cleartextTrafficPermitted="false" />` (keep cleartext disabled everywhere else).
     - `<domain-config cleartextTrafficPermitted="true">` for `api.open-meteo.com` and `previous-runs-api.open-meteo.com` (`includeSubdomains="false"`).
   - Reference `android:networkSecurityConfig="@xml/network_security_config"` in `app/src/main/AndroidManifest.xml`.
3. **Tests:**
   - **Unit tests in `shared/src/test/kotlin/com/weatherwidget/data/remote/OpenMeteoApiTest.kt`** (using `MockEngine` + `runTest` virtual clock):
     - `getForecast`: HTTPS succeeds on first try → only `https://` is called (`http://` never attempted).
     - `getForecast`: HTTPS fails with `SocketTimeoutException` / `SSLException` / `HttpRequestTimeoutException` → waits `httpFallbackDelayMs` (`2_000L` ms), retries with `http://`, and succeeds.
     - `getCurrent` and `getPriorDayCloudForecast`: HTTPS failure falls back to `http://` after `httpFallbackDelayMs` and parses the response.
     - `getHistoricalDailyTemps`: stays HTTPS-only (does not fall back to `http://`).
     - Both HTTPS and HTTP fail → propagates the failure.
     - `CancellationException` on HTTPS is rethrown immediately without HTTP fallback.
   - **API usage tracking test in `ApiUsageClassifierTest` (`shared`):**
     - Verify `http://api.open-meteo.com` and `http://previous-runs-api.open-meteo.com` classify identically to `https://`.
   - **Android network security config test (`app` unit test):**
     - Parse `app/src/main/res/xml/network_security_config.xml` and `AndroidManifest.xml` to assert `networkSecurityConfig` is wired, `base-config` disables cleartext globally, and only `api.open-meteo.com` and `previous-runs-api.open-meteo.com` allow cleartext.
   - **Integration test (`desktop`):**
     - Wire `OpenMeteoApi` + Ktor (`HttpTimeout`, `HttpRequestRetry`, `ResponseObserver` → `ApiUsageClassifier`) + `DesktopWeatherService` + `DesktopWeatherRepository` + `DesktopWeatherDao` (real SQLite) with `MockEngine`: simulate an HTTPS `SSLException` / `SocketTimeoutException`, verify the delayed `http://` fallback succeeds, persists forecast rows in SQLite, and logs `api_usage_stats` under `OPEN_METEO`.

## Verification
1. Run `./scripts/unit-tests.sh` (including `OpenMeteoApiTest` and `ApiUsageClassifier` tests).
2. Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
3. Verify on Android (`emulator-5554` / Fold 4) and desktop that normal HTTPS succeeds, and if HTTPS stalls, `OpenMeteoApi` falls back to `http://` after 2 s without `CLEARTEXT communication ... not permitted` errors.
