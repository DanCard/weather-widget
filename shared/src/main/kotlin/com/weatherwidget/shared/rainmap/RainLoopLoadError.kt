package com.weatherwidget.shared.rainmap

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/** The rain-loop host answered a non-2xx status (WeatherNext manifest/frames, Open-Meteo grid). */
open class RainLoopHttpException(val status: Int, what: String) : java.io.IOException("$what HTTP $status")

/**
 * Why a rain loop (WeatherNext or the Open-Meteo rain map) could not be shown, explicit enough to say
 * so on screen. Each viewer turns it into its own text (Android string resources, desktop English).
 * The WeatherNext viewer always offers the Open-Meteo rain map beside it; [retryable] decides whether
 * a Retry is offered too.
 */
data class RainLoopLoadError(
    val kind: Kind,
    /** Where the data comes from, for the message ("ajoros.github.io", "api.open-meteo.com"). */
    val host: String,
    /** HTTP status for [Kind.GONE] / [Kind.HTTP] / [Kind.RATE_LIMITED]. */
    val status: Int? = null,
    /** Frames asked for, for [Kind.NO_FRAMES]; 0 = the run has none for the layer. */
    val frameCount: Int = 0,
) {
    enum class Kind { GONE, RATE_LIMITED, HTTP, TIMEOUT, OFFLINE, BAD_DATA, NO_FRAMES, OTHER }

    /** A 404/410 from a gone host does not come back on a second click; everything else might. */
    val retryable: Boolean get() = kind != Kind.GONE

    /** `SIM_LOOP_ERROR` fields. */
    fun logFields(): String = "kind=$kind host=$host status=${status ?: "-"} frames=$frameCount"

    /**
     * The on-screen line for an error. [english] is desktop's text and the same template as Android's
     * `sim_loop_error_<name lowercased>` resource (same `%n$` positions; `RainLoopErrorLineParityTest`
     * pins the two), so both platforms pick the line and fill it the same way.
     */
    enum class Line(val english: String) {
        GONE("%1\$s no longer serves the rain loop (HTTP %2\$d). It may have been taken down."),
        RATE_LIMITED("%1\$s refused the request: too many requests (HTTP 429). Try again later."),
        HTTP("%1\$s answered HTTP %2\$d."),
        TIMEOUT("%1\$s didn't answer in time."),
        OFFLINE("Couldn't reach %1\$s — check the network connection."),
        BAD_DATA("The data from %1\$s couldn't be read (unexpected format)."),
        NO_LAYER_FRAMES("This model run has no images for this layer."),
        NO_FRAMES("None of the rain loop's %1\$d images downloaded from %2\$s."),
        OTHER("The rain loop from %1\$s couldn't be loaded."),
    }

    /** A [Line] and its format arguments, in the template's order. */
    data class Message(val line: Line, val args: List<Any>)

    /** Which line this error shows, with what. A missing status reads 404 for GONE, 0 for HTTP. */
    fun message(): Message = when (kind) {
        Kind.GONE -> Message(Line.GONE, listOf(host, status ?: 404))
        Kind.RATE_LIMITED -> Message(Line.RATE_LIMITED, listOf(host))
        Kind.HTTP -> Message(Line.HTTP, listOf(host, status ?: 0))
        Kind.TIMEOUT -> Message(Line.TIMEOUT, listOf(host))
        Kind.OFFLINE -> Message(Line.OFFLINE, listOf(host))
        Kind.BAD_DATA -> Message(Line.BAD_DATA, listOf(host))
        Kind.NO_FRAMES ->
            if (frameCount == 0) Message(Line.NO_LAYER_FRAMES, emptyList())
            else Message(Line.NO_FRAMES, listOf(frameCount, host))
        Kind.OTHER -> Message(Line.OTHER, listOf(host))
    }

    /** Desktop's error line (English). Android resolves [message] against its string resources. */
    fun englishText(): String = message().let { String.format(java.util.Locale.ROOT, it.line.english, *it.args.toTypedArray()) }

    companion object {
        fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

        fun noFrames(host: String, frameCount: Int) = RainLoopLoadError(Kind.NO_FRAMES, host, frameCount = frameCount)

        fun gone(host: String, status: Int) = RainLoopLoadError(Kind.GONE, host, status)

        /**
         * [goneStatuses]: statuses that mean the host dropped the data for good (WeatherNext's
         * 404/410). Open-Meteo passes none — a 404 there is a bad request, not a vanished site.
         */
        fun of(error: Throwable, host: String, goneStatuses: Set<Int> = emptySet()): RainLoopLoadError {
            var e: Throwable? = error
            while (e != null) {
                when {
                    e is RainLoopHttpException -> return when (e.status) {
                        in goneStatuses -> gone(host, e.status)
                        429 -> RainLoopLoadError(Kind.RATE_LIMITED, host, e.status)
                        else -> RainLoopLoadError(Kind.HTTP, host, e.status)
                    }
                    // Ktor's connect timeout subclasses ConnectException on the JVM, so it goes first.
                    e is SocketTimeoutException || e.javaClass.simpleName.contains("Timeout") ->
                        return RainLoopLoadError(Kind.TIMEOUT, host)
                    e is UnknownHostException || e is ConnectException || e is UnresolvedAddressException ->
                        return RainLoopLoadError(Kind.OFFLINE, host)
                    e is kotlinx.serialization.SerializationException -> return RainLoopLoadError(Kind.BAD_DATA, host)
                }
                e = e.cause?.takeIf { it !== e }
            }
            return RainLoopLoadError(Kind.OTHER, host)
        }
    }
}
