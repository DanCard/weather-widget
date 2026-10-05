package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.EOFException
import java.net.SocketTimeoutException

/**
 * Pins [withStallRetry], the bound on NWS's 7-day observation-window pull. Post-wake, those
 * streams stalled mid-body for the client's full 30 s and were never retried
 * (performance/261004-desktop-wake-refresh-stalls-on-7day-obs-window.md).
 */
@Category(ShortDuration::class)
class StallRetryTest {

    @Test
    fun `a stalled first attempt is abandoned at the bound and the retry's value returned`() = runTest {
        var calls = 0
        val retries = mutableListOf<Exception>()
        val value = withStallRetry(timeoutMs = 15_000, onRetry = { _, e -> retries += e }) {
            calls++
            if (calls == 1) awaitCancellation() // the post-wake stall: no bytes, ever
            "rows"
        }
        assertEquals("rows", value)
        assertEquals(2, calls)
        assertEquals(15_000L, currentTime) // one bound, not the client's 30 s
        assertTrue(retries.single() is SocketTimeoutException)
    }

    @Test
    fun `a truncated body is retried, which Ktor's HttpRequestRetry never did`() = runTest {
        var calls = 0
        val value = withStallRetry(timeoutMs = 15_000) {
            calls++
            if (calls == 1) throw EOFException("Invalid chunk: content block of size 49152 ended unexpectedly")
            42
        }
        assertEquals(42, value)
        assertEquals(2, calls)
    }

    @Test
    fun `a persistent stall fails as a timeout, not a cancellation, and spares its siblings`() = runTest {
        // Callers rethrow CancellationException; if the bound leaked TimeoutCancellationException,
        // one dead station would cancel every other station's fetch in the same coroutineScope.
        val (stalled, healthy) = coroutineScope {
            val stalled = async {
                runCatching { withStallRetry(timeoutMs = 15_000) { awaitCancellation() } }
            }
            val healthy = async { delay(20_000); "sibling done" }
            stalled.await() to healthy.await()
        }
        val failure = stalled.exceptionOrNull()
        assertTrue("got $failure", failure is SocketTimeoutException)
        assertTrue(failure !is CancellationException)
        assertEquals("sibling done", healthy)
        assertEquals(30_000L, currentTime) // two attempts × 15 s
    }

    @Test
    fun `outer cancellation propagates without a retry`() = runTest {
        var calls = 0
        val job = launch {
            withStallRetry(timeoutMs = 15_000) {
                calls++
                awaitCancellation()
            }
        }
        delay(1_000)
        job.cancel()
        job.join()
        assertEquals(1, calls)
    }

    @Test
    fun `non-IO failures are not retried`() = runTest {
        var calls = 0
        try {
            withStallRetry(timeoutMs = 15_000) {
                calls++
                throw IllegalStateException("bad JSON")
            }
            fail("expected the failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals(1, calls)
        }
    }
}
