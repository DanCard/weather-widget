package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.ApiUsageClassifier
import com.weatherwidget.data.remote.HttpPhaseTiming
import com.weatherwidget.data.remote.WeatherHttpClientDefaults
import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
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

    fun newOkHttpClient(
        keepAliveMs: Long = IDLE_KEEP_ALIVE_MS,
        onPhaseFail: ((String) -> Unit)? = null,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, keepAliveMs, TimeUnit.MILLISECONDS))
            .eventListenerFactory { DesktopHttpPhaseListener(onPhaseFail) }
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
        okHttp: OkHttpClient = newOkHttpClient(
            onPhaseFail = weatherDao?.let { dao ->
                { line -> DesktopAppLogQueue.write("http_phase_fail") { dao.log(HttpPhaseTiming.LOG_TAG, line, "WARN") } }
            },
        ),
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

/**
 * Per-call OkHttp [EventListener] that records phase timings and emits one [HttpPhaseTiming.LOG_TAG]
 * warning only when a call fails or times out (zero logs on success).
 */
internal class DesktopHttpPhaseListener(
    private val onPhaseFail: ((String) -> Unit)? = null,
) : EventListener() {
    private var callStartNs = -1L
    private var dnsStartNs = -1L
    private var dnsMs = -1L
    private var connectStartNs = -1L
    private var connectMs = -1L
    private var tlsStartNs = -1L
    private var tlsMs = -1L
    private var reqHeadersStartNs = -1L
    private var reqHeadersMs = -1L
    private var ttfbStartNs = -1L
    private var ttfbMs = -1L
    private var remoteIp: String? = null
    private var protocol: String? = null
    private var connected = false
    private var reused = false

    private fun elapsedMs(startNs: Long): Long =
        if (startNs >= 0L) (System.nanoTime() - startNs) / 1_000_000L else -1L

    override fun callStart(call: Call) {
        callStartNs = System.nanoTime()
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartNs = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsMs = elapsedMs(dnsStartNs)
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartNs = System.nanoTime()
        remoteIp = inetSocketAddress.address?.hostAddress
    }

    override fun secureConnectStart(call: Call) {
        tlsStartNs = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = elapsedMs(tlsStartNs)
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        connectMs = elapsedMs(connectStartNs)
        this.protocol = protocol?.toString()
        connected = true
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        connectMs = elapsedMs(connectStartNs)
        this.protocol = protocol?.toString()
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        if (!connected) reused = true
        if (remoteIp == null) remoteIp = connection.socket().inetAddress?.hostAddress
        if (protocol == null) protocol = connection.protocol().toString()
    }

    override fun requestHeadersStart(call: Call) {
        reqHeadersStartNs = System.nanoTime()
    }

    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
        reqHeadersMs = elapsedMs(reqHeadersStartNs)
        ttfbStartNs = System.nanoTime()
    }

    override fun responseHeadersStart(call: Call) {
        ttfbMs = elapsedMs(ttfbStartNs)
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        if (ttfbMs < 0L) ttfbMs = elapsedMs(ttfbStartNs)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val url = call.request().url
        val line = HttpPhaseTiming(
            host = url.host,
            scheme = url.scheme,
            remoteIp = remoteIp,
            protocol = protocol,
            dnsMs = dnsMs,
            connectMs = connectMs,
            tlsMs = tlsMs,
            reqHeadersMs = reqHeadersMs,
            ttfbMs = ttfbMs,
            totalMs = elapsedMs(callStartNs),
            reused = reused,
            error = "${ioe::class.simpleName}:${ioe.message ?: ""}".take(120),
        ).formatLine()
        Log.w(HttpPhaseTiming.LOG_TAG, line)
        onPhaseFail?.invoke(line)
    }
}
