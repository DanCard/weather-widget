package com.weatherwidget.data.remote

/**
 * Pure formatting for OkHttp per-call phase timings emitted when a request fails or times out.
 * Kept in `:shared` so desktop (`DesktopHttpClient`) and Android (`AppModule`) emit identical
 * diagnostic lines without pulling `okhttp3` into `:shared`.
 *
 * All durations are in milliseconds; `-1` means that phase did not complete before failure.
 */
data class HttpPhaseTiming(
    val host: String,
    val scheme: String,
    val remoteIp: String? = null,
    val protocol: String? = null,
    val dnsMs: Long = -1L,
    val connectMs: Long = -1L,
    val tlsMs: Long = -1L,
    val reqHeadersMs: Long = -1L,
    val ttfbMs: Long = -1L,
    val totalMs: Long = -1L,
    val reused: Boolean = false,
    val error: String,
) {
    fun formatLine(): String =
        "host=$host scheme=$scheme remoteIp=${remoteIp ?: "none"} protocol=${protocol ?: "none"} " +
            "dnsMs=$dnsMs connectMs=$connectMs tlsMs=$tlsMs reqHeadersMs=$reqHeadersMs " +
            "ttfbMs=$ttfbMs totalMs=$totalMs reused=$reused error=$error"

    companion object {
        const val LOG_TAG = "HTTP_PHASE_FAIL"
    }
}
