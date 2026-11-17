package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.ApiUsageClassifier
import com.weatherwidget.data.remote.WeatherHttpClientDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The desktop's one way to build a weather [HttpClient] (daemon: `DesktopWeatherService`; UI:
 * `DesktopClients`). Shared timeouts/retries come from [WeatherHttpClientDefaults]; this owns the
 * engine and the desktop usage observer.
 *
 * Engine is OkHttp, the same as Android. It replaced Ktor CIO while investigating Open-Meteo timeouts
 * (plans/261116-desktop-okhttp-engine.md), but the comparison there found CIO handled stale and new
 * connections correctly, so the switch is for parity, not a proven fix. OkHttp uses the JDK's TLS
 * and a health-checked pool, and:
 * - [OkHttpClient.Builder.retryOnConnectionFailure]: a request on a reused connection the peer closed is
 *   resent on a new connection, below Ktor's retry.
 * - [IDLE_KEEP_ALIVE_MS]: idle connections are evicted after 30 s. Background fetches are 10+ min apart,
 *   so they open a new connection instead of reusing one a NAT may have dropped without a FIN (which no
 *   pool can detect before writing).
 */
object DesktopHttpClient {
    const val IDLE_KEEP_ALIVE_MS = 30_000L
    const val MAX_IDLE_CONNECTIONS = 5

    fun newOkHttpClient(keepAliveMs: Long = IDLE_KEEP_ALIVE_MS): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, keepAliveMs, TimeUnit.MILLISECONDS))
            .build()

    /**
     * [weatherDao] null: no usage accounting (tests, tools). [isForeground] tags the network-usage rows.
     * [okHttp] is injectable so tests can inspect its pool; Ktor derives per-timeout clients from it with
     * `newBuilder()`, which shares this pool.
     */
    fun newWeatherClient(
        json: Json,
        weatherDao: DesktopWeatherDao?,
        isForeground: Boolean,
        okHttp: OkHttpClient = newOkHttpClient(),
    ): HttpClient =
        HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            WeatherHttpClientDefaults.install(this, json)
            if (weatherDao != null) {
                install(ResponseObserver) {
                    onResponse { response ->
                        val bodyBytes = runCatching {
                            response.contentLength()
                                ?: response.bodyAsText().toByteArray(Charsets.UTF_8).size.toLong()
                        }.getOrDefault(0L)
                        val totalBytes = bodyBytes + 500L
                        // Queued: an inline insert per response crowded the lock during fetch bursts (DesktopAppLogQueue).
                        val nowMs = System.currentTimeMillis()
                        DesktopAppLogQueue.write("network_usage") {
                            weatherDao.recordNetworkUsage(totalBytes, isForeground, timestamp = nowMs)
                        }
                        val url = response.call.request.url
                        ApiUsageClassifier.classify(url.host, url.encodedPath, url.encodedQuery)?.let { key ->
                            val day = ApiUsageClassifier.usageDayMs(key.source, java.time.Instant.now(), java.time.ZoneId.systemDefault())
                            DesktopAppLogQueue.write("api_usage_stats") { weatherDao.logApiCall(day, key.source, key.endpoint, response.status.value) }
                        }
                    }
                }
            }
        }
}
