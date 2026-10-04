package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    private fun gate(store: Store, flights: SynopticFetchGate.Flights = SynopticFetchGate.Flights()) =
        SynopticFetchGate(store, { tag, _, _ -> logs += tag }, { now }, flights)

    /** Two callers overlap on [siteKey]; returns how many fetches actually ran. */
    private suspend fun overlappingCalls(first: SynopticFetchGate, second: SynopticFetchGate): Int =
        kotlinx.coroutines.coroutineScope {
            var calls = 0
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val a = async {
                first.run("reason=periodic_60m", siteKey = "site") {
                    calls++
                    started.complete(Unit)
                    release.await()
                    FetchOutcome.Failed("HttpRequestTimeoutException: timeout after 30000 ms")
                }
            }
            started.await()
            val b = async {
                second.run("reason=on_update_stale", siteKey = "site") {
                    calls++
                    FetchOutcome.Failed("HttpRequestTimeoutException: timeout after 30000 ms")
                }
            }
            delay(20)
            release.complete(Unit)
            a.await()
            b.await()
            calls
        }

    @Test
    fun `a rejection starts the base backoff and the next fetch is skipped`() = runBlocking {
        val store = Store()
        gate(store).run("reason=t") { FetchOutcome.Failed("synoptic: RESPONSE_CODE=2 (no message)") }
        assertEquals(1, store.streak)
        assertEquals(now + SynopticBackoff.BASE_BACKOFF_MS, store.until)

        var called = false
        val skipped = gate(store).run("reason=t") { called = true; FetchOutcome.NoData }
        assertNull(skipped)
        assertEquals(false, called)
        assertTrue("SYNOPTIC_FETCH_BACKOFF_SKIP" in logs)
    }

    @Test
    fun `a transport failure waits five minutes and does not escalate the streak`() = runBlocking {
        // One 30-second stall must not cost an hour of Synoptic (the pre-split behaviour).
        val store = Store()
        gate(store).run("reason=t") { FetchOutcome.Failed("HttpRequestTimeoutException: timeout after 30000 ms") }
        assertEquals(0, store.streak)
        assertEquals(now + SynopticBackoff.TRANSPORT_BACKOFF_MS, store.until)
        assertTrue(logs.any { it == "SYNOPTIC_FETCH_BACKOFF_SET" })

        // A second stall still waits five minutes — no doubling.
        now += SynopticBackoff.TRANSPORT_BACKOFF_MS + 1
        gate(store).run("reason=t") { FetchOutcome.Failed("ConnectException: Connection refused") }
        assertEquals(0, store.streak)
        assertEquals(now + SynopticBackoff.TRANSPORT_BACKOFF_MS, store.until)
    }

    @Test
    fun `transport and rejection classify independently`() = runBlocking {
        val store = Store()
        // Two rejections escalate (advance past each backoff so the next call is not skipped).
        gate(store).run("reason=t") { FetchOutcome.Failed("synoptic: Invalid token") }
        now += SynopticBackoff.BASE_BACKOFF_MS + 1
        gate(store).run("reason=t") { FetchOutcome.Failed("synoptic: Invalid token") }
        assertEquals(2, store.streak)

        // A stall does not touch the streak but does set the short backoff.
        now += SynopticBackoff.backoffFor(2) + 1
        gate(store).run("reason=t") { FetchOutcome.Failed("HttpRequestTimeoutException: timeout") }
        assertEquals(2, store.streak)
        assertEquals(now + SynopticBackoff.TRANSPORT_BACKOFF_MS, store.until)

        // The next rejection continues the rejection streak (3), not restart it.
        now += SynopticBackoff.TRANSPORT_BACKOFF_MS + 1
        gate(store).run("reason=t") { FetchOutcome.Failed("synoptic: Invalid token") }
        assertEquals(3, store.streak)
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
        gate(store).run("reason=t", userLocationChange = true) { FetchOutcome.Failed("synoptic: RESPONSE_CODE=2 (no message)") }
        assertEquals(2, store.streak)
        assertEquals(now + SynopticBackoff.backoffFor(2), store.until)
    }

    @Test
    fun `overlapping fetches for the same site share one request`() = runBlocking {
        val store = Store()
        val g = gate(store)
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            g.run("reason=periodic", siteKey = "site-a") {
                calls++
                started.complete(Unit)
                release.await()
                FetchOutcome.Success(listOf(1))
            }
        }
        started.await()
        val second = async {
            g.run("reason=on_update_stale", siteKey = "site-a") {
                calls++
                FetchOutcome.Success(listOf(2))
            }
        }
        // Give the second caller a moment to join, then release the first fetch.
        delay(20)
        release.complete(Unit)

        val a = first.await()
        val b = second.await()
        assertEquals(1, calls)
        assertEquals(a, b)
        assertTrue("SYNOPTIC_FETCH_JOINED" in logs)
    }

    @Test
    fun `a completed fetch under the freshness floor skips the next one`() = runBlocking {
        val store = Store()
        val g = gate(store)
        var calls = 0
        g.run("reason=t", siteKey = "site-b") {
            calls++
            FetchOutcome.Success(listOf(1))
        }
        // 7 seconds later — the 16:49:30 / 16:49:37 pair.
        now += 7_000L
        val skipped = g.run("reason=t", siteKey = "site-b") {
            calls++
            FetchOutcome.Success(listOf(2))
        }
        assertNull(skipped)
        assertEquals(1, calls)
        assertTrue("SYNOPTIC_FETCH_FRESH_SKIP" in logs)

        // Past the floor, the next sync fetches again.
        now += SynopticFetchGate.FRESHNESS_FLOOR_MS
        val again = g.run("reason=t", siteKey = "site-b") {
            calls++
            FetchOutcome.Success(listOf(3))
        }
        assertEquals(FetchOutcome.Success(listOf(3)), again)
        assertEquals(2, calls)
    }

    @Test
    fun `freshness floor does not block a user location change`() = runBlocking {
        val store = Store()
        val g = gate(store)
        var calls = 0
        g.run("reason=t", siteKey = "site-c") {
            calls++
            FetchOutcome.Success(listOf(1))
        }
        now += 1_000L
        val outcome = g.run("reason=t", userLocationChange = true, siteKey = "site-c") {
            calls++
            FetchOutcome.Success(listOf(2))
        }
        assertEquals(FetchOutcome.Success(listOf(2)), outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `different sites do not share a single-flight or freshness floor`() = runBlocking {
        val store = Store()
        val g = gate(store)
        var calls = 0
        g.run("reason=t", siteKey = "site-x") {
            calls++
            FetchOutcome.Success(listOf(1))
        }
        val outcome = g.run("reason=t", siteKey = "site-y") {
            calls++
            FetchOutcome.Success(listOf(2))
        }
        assertEquals(FetchOutcome.Success(listOf(2)), outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `two gates sharing one Flights send one request`() = runBlocking {
        // Android rebuilds the gate for every worker run; the 21:58:39 / 21:58:52 pair were two runs.
        val store = Store()
        val shared = SynopticFetchGate.Flights()
        assertEquals(1, overlappingCalls(gate(store, shared), gate(store, shared)))
        assertEquals(1, logs.count { it == "SYNOPTIC_FETCH_BACKOFF_SET" })
    }

    @Test
    fun `two gates with their own Flights send two requests`() = runBlocking {
        // The control: per-gate state is what made single-flight a no-op on Android.
        val store = Store()
        assertEquals(2, overlappingCalls(gate(store), gate(store)))
    }

    @Test
    fun `a cancelled owner releases its slot even while the state lock is contended`() = runBlocking {
        val store = Store()
        val flights = SynopticFetchGate.Flights()
        val g = gate(store, flights)
        val started = CompletableDeferred<Unit>()
        val owner = launch {
            g.run("reason=t", siteKey = "site-k") {
                started.complete(Unit)
                CompletableDeferred<Unit>().await() // hangs until cancelled (WorkManager stop)
                FetchOutcome.NoData
            }
        }
        started.await()
        // Hold the state lock so the owner's cleanup has to suspend while already cancelled.
        flights.mutex.lock()
        owner.cancel()
        yield()
        flights.mutex.unlock()
        owner.join()

        var called = false
        val outcome = g.run("reason=t", siteKey = "site-k") { called = true; FetchOutcome.NoData }
        assertTrue("a leaked in-flight entry made the next caller join a dead fetch", called)
        assertEquals(FetchOutcome.NoData, outcome)
    }

    @Test
    fun `a transport failure never shortens a longer backoff already in force`() = runBlocking {
        // A location-change bypass runs through a 6 h rejection backoff and stalls.
        val longUntil = now + SynopticBackoff.MAX_BACKOFF_MS
        val store = Store(streak = 5, until = longUntil)
        gate(store).run("reason=t", userLocationChange = true) {
            FetchOutcome.Failed("SocketTimeoutException: connect timed out")
        }
        assertEquals(longUntil, store.until)
        assertEquals(5, store.streak)
    }
}
