package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The cooldown runs from a backfill that STARTED its fetch, not from the request.
 * See plans/261009-observation-backfill-cooldown-starts-when-it-runs.md.
 */
@Category(ShortDuration::class)
class HourlyBackfillGateTest {

    private val now = 1_791_600_000_000L
    private val cooldown = 30 * 60_000L
    private fun ago(min: Long) = now - min * 60_000L

    private fun decide(
        requestedAgoMin: Long?,
        attemptedAgoMin: Long?,
        pending: Boolean = false,
        tracked: Boolean = true,
        onPendingLookup: () -> Unit = {},
    ) = runBlocking {
        HourlyBackfillGate.decide(
            nowMs = now,
            cooldownMs = cooldown,
            requestedAtMs = requestedAgoMin?.let(::ago) ?: 0L,
            attemptedAtMs = attemptedAgoMin?.let(::ago) ?: 0L,
            attemptTracked = tracked,
            isPending = { onPendingLookup(); pending },
        )
    }

    @Test
    fun `never requested and never attempted requests`() {
        assertTrue(decide(requestedAgoMin = null, attemptedAgoMin = null).request)
    }

    /** Emulator 2026-10-09: enqueued, consumed by a test process, never reached the fetch. */
    @Test
    fun `requested, not pending, never attempted is a dropped request and asks again`() {
        val d = decide(requestedAgoMin = 17, attemptedAgoMin = null, pending = false)
        assertTrue(d.request)
        assertTrue(d.reason, d.reason.startsWith("dropped"))
    }

    @Test
    fun `requested and still pending waits`() {
        val d = decide(requestedAgoMin = 1, attemptedAgoMin = null, pending = true)
        assertFalse(d.request)
        assertTrue(d.reason, d.reason.startsWith("pending"))
    }

    @Test
    fun `an attempt inside the window cools down whatever its outcome`() {
        val d = decide(requestedAgoMin = 12, attemptedAgoMin = 10)
        assertFalse(d.request)
        assertTrue(d.reason, d.reason.startsWith("cooldown"))
    }

    /**
     * A backfill that throws after starting its fetch stamped the attempt first, so it is NOT read as
     * dropped: no re-request on every repaint, and no WorkManager lookup either.
     */
    @Test
    fun `a crashed attempt still cools down without consulting WorkManager`() {
        var lookups = 0
        val d = decide(requestedAgoMin = 3, attemptedAgoMin = 2, pending = false, onPendingLookup = { lookups++ })
        assertFalse(d.request)
        assertEquals(0, lookups)
    }

    @Test
    fun `an attempt older than the window allows a new request`() {
        assertTrue(decide(requestedAgoMin = 31, attemptedAgoMin = 31).request)
    }

    @Test
    fun `untracked paths keep the request-time cooldown`() {
        var lookups = 0
        val d = decide(requestedAgoMin = 5, attemptedAgoMin = null, tracked = false, onPendingLookup = { lookups++ })
        assertFalse(d.request)
        assertEquals(0, lookups)
        assertTrue(decide(requestedAgoMin = 31, attemptedAgoMin = null, tracked = false).request)
    }

    @Test
    fun `pre-check is certain only about a recent attempt`() {
        fun cooling(req: Long?, att: Long?, tracked: Boolean = true) =
            HourlyBackfillGate.certainlyCoolingDown(now, cooldown, req?.let(::ago) ?: 0L, att?.let(::ago) ?: 0L, tracked)
        assertTrue(cooling(req = 12, att = 10))
        assertFalse("recent request without an attempt must reach the full check", cooling(req = 5, att = null))
        assertFalse(cooling(req = 40, att = 35))
        assertTrue(cooling(req = 5, att = null, tracked = false))
    }
}
