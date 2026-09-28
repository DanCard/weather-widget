package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SynopticFetchGateTest {

    private class Store(var streak: Int = 0, var until: Long = 0L) : SynopticBackoffStore {
        override fun failStreak() = streak
        override fun backoffUntilMs() = until
        override fun save(failStreak: Int, backoffUntilMs: Long) {
            streak = failStreak
            until = backoffUntilMs
        }
    }

    private val logs = mutableListOf<String>()
    private var now = 1_000_000L

    private fun gate(store: Store) = SynopticFetchGate(store, { tag, _, _ -> logs += tag }, { now })

    @Test
    fun `a failure starts the base backoff and the next fetch is skipped`() = runBlocking {
        val store = Store()
        gate(store).run("reason=t") { FetchOutcome.Failed("timeout") }
        assertEquals(1, store.streak)
        assertEquals(now + SynopticBackoff.BASE_BACKOFF_MS, store.until)

        var called = false
        val skipped = gate(store).run("reason=t") { called = true; FetchOutcome.NoData }
        assertNull(skipped)
        assertEquals(false, called)
        assertTrue("SYNOPTIC_FETCH_BACKOFF_SKIP" in logs)
    }

    @Test
    fun `a user location change fetches through the backoff and a success clears it`() = runBlocking {
        // 2026-09-28: a Kyiv timeout at 09:13 blanked Warsaw's yesterday after a 09:14 move.
        val store = Store(streak = 1, until = now + SynopticBackoff.BASE_BACKOFF_MS)
        val outcome = gate(store).run("reason=t", userLocationChange = true) { FetchOutcome.Success(listOf(1)) }
        assertEquals(FetchOutcome.Success(listOf(1)), outcome)
        assertEquals(0, store.streak)
        assertEquals(0L, store.until)
        assertTrue("SYNOPTIC_FETCH_BACKOFF_BYPASS" in logs)
    }

    @Test
    fun `a failed bypass escalates the backoff like any failure`() = runBlocking {
        val store = Store(streak = 1, until = now + SynopticBackoff.BASE_BACKOFF_MS)
        gate(store).run("reason=t", userLocationChange = true) { FetchOutcome.Failed("timeout") }
        assertEquals(2, store.streak)
        assertEquals(now + SynopticBackoff.backoffFor(2), store.until)
    }
}
