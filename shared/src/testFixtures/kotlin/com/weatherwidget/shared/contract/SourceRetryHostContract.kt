package com.weatherwidget.shared.contract

import com.weatherwidget.shared.util.SourceRetry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [SourceRetry.Host] means on Android (widget-state prefs + a delayed targeted sync) and desktop
 * (`SOURCE_RETRY_ARMED` rows the forecast loop sleeps toward): an armed retry reads back as armed, a
 * chain continues per source, and a success ends it (plans/261112-refresh-failure-reason-and-retry.md).
 */
abstract class SourceRetryHostContract {
    /** A fresh store with [displayed] on screen, on a charger with the screen on. */
    protected abstract fun open(displayed: String): SourceRetry.Host

    /** The platform's record of a successful forecast fetch of [sourceId], after every retry armed so far. */
    protected abstract suspend fun recordSuccess(sourceId: String)

    /** The wakes the host arranged, as (sourceId, delayMs). */
    protected abstract fun wakes(): List<Pair<String, Long>>

    private val now = System.currentTimeMillis()

    @Test
    fun `nothing armed reads back as nothing`() {
        assertNull(open("OPEN_METEO").pending("OPEN_METEO"))
    }

    @Test
    fun `the displayed source is displayed and no other`() {
        val host = open("OPEN_METEO")
        assertTrue(host.isDisplayed("OPEN_METEO"))
        assertFalse(host.isDisplayed("NWS"))
    }

    @Test
    fun `an armed retry reads back with its time, delay and attempt, and wakes once`() = runBlocking {
        val host = open("OPEN_METEO")
        val at = SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "contract", now)
        assertEquals(SourceRetry.Pending(at!!, SourceRetry.DELAYS_MS[0], attempt = 1), host.pending("OPEN_METEO"))
        assertEquals(listOf("OPEN_METEO" to SourceRetry.DELAYS_MS[0]), wakes())
    }

    @Test
    fun `a second failure continues the chain, per source`() = runBlocking {
        val host = open("OPEN_METEO")
        SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "contract", now)
        SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "contract", now + 1_000L)
        assertEquals(2, host.pending("OPEN_METEO")?.attempt)
        assertEquals(SourceRetry.DELAYS_MS[1], host.pending("OPEN_METEO")?.delayMs)
        assertNull(host.pending("NWS"))
    }

    @Test
    fun `a success ends the chain, and the next failure starts at the first delay`() = runBlocking {
        val host = open("OPEN_METEO")
        SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "contract", now)
        recordSuccess("OPEN_METEO")
        assertNull(host.pending("OPEN_METEO"))
        SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "contract", now + 2_000L)
        assertEquals(1, host.pending("OPEN_METEO")?.attempt)
    }
}
