package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.HttpPhaseEventListener
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
 * `DesktopClients`). Shared timeouts/retries, OkHttp phase listener, and API-usage error accounting
 * come from `:shared` ([WeatherHttpClientDefaults], [HttpPhaseEventListener]); this owns the
 * desktop pool policy and network-bytes observer.
 */
object DesktopHttpClient {
    const val IDLE_KEEP_ALIVE_MS = 30_000L
    const val MAX_IDLE_CONNECTIONS = 5

    fun newOkHttpClient(
        keepAliveMs: Long = IDLE_KEEP_ALIVE_MS,
        onPhaseFail: ((String) -> Unit)? = null,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, keepAliveMs, TimeUnit.MILLISECONDS))
            .eventListenerFactory { HttpPhaseEventListener(onPhaseFail) }
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
                    }
                }
            }
        }.apply {
            if (weatherDao != null) {
                WeatherHttpClientDefaults.installApiUsageInterceptor(this) { date, source, endpoint, isError, isQuotaRefusal ->
                    DesktopAppLogQueue.write("api_usage_stats") {
                        weatherDao.logApiCall(date, source, endpoint, isError = isError, isQuotaRefusal = isQuotaRefusal)
                    }
                }
            }
        }
}
