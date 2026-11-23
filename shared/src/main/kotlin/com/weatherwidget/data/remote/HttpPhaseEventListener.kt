package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.AppLog
import com.weatherwidget.shared.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Shared per-call OkHttp [EventListener] (Android `AppModule` + Desktop `DesktopHttpClient`).
 * Records nanosecond phase timings and emits one [HttpPhaseTiming.LOG_TAG] warning to both [Log]
 * and persistent [AppLog] only when a call fails or times out (zero logs on success).
 */
class HttpPhaseEventListener(
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

    override fun requestHeadersEnd(call: Call, request: Request) {
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
        AppLog.w(HttpPhaseTiming.LOG_TAG, line)
        onPhaseFail?.invoke(line)
    }
}
