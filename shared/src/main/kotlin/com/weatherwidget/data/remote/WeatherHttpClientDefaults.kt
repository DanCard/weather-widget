package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClientConfig
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The client behavior every platform's weather [io.ktor.client.HttpClient] shares: JSON negotiation,
 * timeouts and retries. Android (`AppModule`) and both desktop clients (`DesktopHttpClient`) call
 * [install]; each platform supplies only its engine and its own observers (API usage, network bytes).
 *
 * Ktor's request timeout covers the whole call **including retries and their delays**, so with a
 * per-request override such as Open-Meteo's HTTP fallback (6 s socket / 12 s request) two socket-limit attempts
 * already fill the budget.
 */
object WeatherHttpClientDefaults {
    const val REQUEST_TIMEOUT_MS = 30_000L
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val SOCKET_TIMEOUT_MS = 15_000L
    const val MAX_RETRIES = 2
    const val MAX_RETRY_DELAY_MS = 2_000L

    private const val TAG = "HttpRetry"

    /** Transient transport failures worth one more attempt; HTTP status errors are never retried here. */
    fun isRetryable(cause: Throwable): Boolean =
        cause is ConnectTimeoutException ||
            cause is SocketTimeoutException ||
            cause is HttpRequestTimeoutException ||
            cause is java.io.IOException

    /**
     * [retryDelay] replaces the sleep between retries (tests pass a no-op; the backoff carries up to
     * 1 s of random jitter). Production leaves it null.
     */
    fun install(
        config: HttpClientConfig<*>,
        json: Json,
        retryDelay: (suspend (Long) -> Unit)? = null,
    ) {
        with(config) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            install(HttpRequestRetry) {
                maxRetries = MAX_RETRIES
                retryOnExceptionIf { request, cause ->
                    val retryable = isRetryable(cause)
                    if (retryable) {
                        Log.w(
                            TAG,
                            "retryOnException host=${request.url.host} path=${request.url.build().encodedPath} " +
                                "cause=${cause::class.simpleName}:${cause.message}",
                        )
                    }
                    retryable
                }
                exponentialDelay(base = 2.0, maxDelayMs = MAX_RETRY_DELAY_MS)
                retryDelay?.let { delay(it) }
            }
        }
    }
}
