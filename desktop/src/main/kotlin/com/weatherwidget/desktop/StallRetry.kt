package com.weatherwidget.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Runs [block] under a [timeoutMs] bound, retrying once more on a timeout or an [IOException],
 * up to [attempts] in all.
 *
 * Built for NWS's 7-day station-observation pull (~2 MB, normally 1–2.5 s). Right after a wake,
 * individual streams stall mid-body and only die at the client's 30 s socket/request timeout,
 * surfacing as `EOFException: Invalid chunk … ended unexpectedly`. Ktor's `HttpRequestRetry`
 * never retries those: `.body()` reads the body after the send pipeline the plugin wraps. See
 * performance/261004-desktop-wake-refresh-stalls-on-7day-obs-window.md.
 *
 * Our own timeout is rethrown as a [SocketTimeoutException], never as
 * [TimeoutCancellationException]. That one is a [CancellationException], and callers rethrow
 * cancellation, so a stalled station would otherwise cancel every sibling station's fetch.
 */
internal suspend fun <T> withStallRetry(
    timeoutMs: Long,
    attempts: Int = 2,
    onRetry: (attempt: Int, cause: Exception) -> Unit = { _, _ -> },
    block: suspend () -> T,
): T {
    require(attempts >= 1)
    var attempt = 1
    while (true) {
        val failure: Exception = try {
            return withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            SocketTimeoutException("no complete response within ${timeoutMs}ms").apply { initCause(e) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            e
        }
        if (attempt >= attempts) throw failure
        onRetry(attempt, failure)
        attempt++
    }
}
