package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.TargetedForecastSyncRun.Enqueue
import com.weatherwidget.shared.util.TargetedForecastSyncRun.TailOutcome
import com.weatherwidget.shared.util.TargetedForecastSyncRun.TailState
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TargetedForecastSyncRunTest {

    private class RecordingHost : TargetedForecastSyncRun.Host {
        val calls = mutableListOf<String>()
        val scheduled = mutableListOf<Triple<Enqueue, Long, Set<String>>>()
        var state = TailState.NONE
        var visible = setOf("NWS")
        var signatures = ArrayDeque(listOf<Any?>("a", "a"))
        var userSync = false
        var lastInteraction: Long? = null
        var failObservationHistory = false
        val logs = mutableListOf<Pair<String, String>>()

        override suspend fun afterFetch() { calls += "afterFetch" }
        override suspend fun paint() { calls += "paint" }
        override suspend fun tailState() = state
        override suspend fun scheduleTail(enqueue: Enqueue, delayMs: Long, sourceIds: Set<String>) {
            calls += "schedule:$enqueue"
            scheduled += Triple(enqueue, delayMs, sourceIds)
        }
        override suspend fun visibleSourceIds() = visible
        override suspend fun fillObservationHistory(sourceIds: Set<String>) {
            calls += "history:${sourceIds.sorted().joinToString("|")}"
            if (failObservationHistory) error("nws down")
        }
        override suspend fun refreshObservationFeeds(sourceIds: Set<String>) { calls += "feeds" }
        override suspend fun actualsSignature(): Any? = signatures.removeFirst()
        override suspend fun recomputeActuals() { calls += "recompute" }
        override suspend fun repaint(reason: String) { calls += "repaint:$reason" }
        override fun userSyncActive() = userSync
        override fun lastInteractionMs() = lastInteraction
        override suspend fun log(tag: String, message: String, level: String) {
            println("[$level] $tag $message")
            logs += tag to message
        }
    }

    @Test
    fun `fast path runs afterFetch then paint then schedules the tail, with no observation or recompute work`() = runBlocking {
        val host = RecordingHost()
        TargetedForecastSyncRun { 0L }.onFetched(host, "OPEN_METEO", stored = true)
        assertEquals(listOf("afterFetch", "paint", "schedule:SCHEDULE"), host.calls)
        assertEquals(TargetedForecastSyncRun.TAIL_DELAY_MS, host.scheduled.single().second)
        assertEquals(setOf("OPEN_METEO"), host.scheduled.single().third)
    }

    @Test
    fun `a fetch that stored nothing paints from cache and asks for no tail`() = runBlocking {
        val host = RecordingHost()
        TargetedForecastSyncRun { 0L }.onFetched(host, "OPEN_METEO", stored = false)
        assertEquals(listOf("paint"), host.calls)
    }

    @Test
    fun `a burst keeps one pending tail, which settles on the newest request and serves every source`() = runBlocking {
        var now = 0L
        val run = TargetedForecastSyncRun { now }
        val host = RecordingHost()
        run.requestTail(host, setOf("GOOGLE_WEATHER"))
        host.state = TailState.PENDING
        now = 5_000L
        run.requestTail(host, setOf("OPEN_METEO"))
        assertEquals(Enqueue.KEEP_PENDING, host.scheduled.last().first)
        assertEquals(setOf("GOOGLE_WEATHER", "OPEN_METEO"), host.scheduled.last().third)

        // The first tail comes due 12 s after the FIRST request: 7 s after the newest, so it re-arms for 5 s.
        now = 12_000L
        host.state = TailState.RUNNING
        assertEquals(TailOutcome.YIELDED, run.runTail(host, setOf("GOOGLE_WEATHER")))
        assertEquals(Enqueue.APPEND_AFTER_RUNNING, host.scheduled.last().first)
        assertEquals(5_000L, host.scheduled.last().second)
        assertFalse("recompute" in host.calls)

        now = 17_000L
        host.calls.clear()
        assertEquals(TailOutcome.RAN, run.runTail(host, setOf("GOOGLE_WEATHER")))
        assertEquals("history:GOOGLE_WEATHER|NWS|OPEN_METEO", host.calls.first())
    }

    @Test
    fun `a burst cannot push its tail past the cap`() = runBlocking {
        var now = 0L
        val run = TargetedForecastSyncRun { now }
        val host = RecordingHost()
        run.requestTail(host, setOf("NWS"))
        host.state = TailState.PENDING
        while (now < TargetedForecastSyncRun.MAX_PUSH_OUT_MS) {
            now += 4_000L
            run.requestTail(host, setOf("NWS"))
        }
        assertEquals(TailOutcome.RAN, run.runTail(host, setOf("NWS")))
    }

    @Test
    fun `a running tail is appended to, never replaced`() = runBlocking {
        val host = RecordingHost().apply { state = TailState.RUNNING }
        TargetedForecastSyncRun { 0L }.requestTail(host, setOf("NWS"))
        assertEquals(Enqueue.APPEND_AFTER_RUNNING, host.scheduled.single().first)
    }

    @Test
    fun `tail serves requested plus visible sources in order and skips the repaint when actuals did not change`() = runBlocking {
        var now = 0L
        val run = TargetedForecastSyncRun { now }
        val host = RecordingHost()
        run.requestTail(host, setOf("OPEN_METEO"))
        now = TargetedForecastSyncRun.TAIL_DELAY_MS
        host.calls.clear()
        assertEquals(TailOutcome.RAN, run.runTail(host, setOf("SILURIAN")))
        assertEquals(listOf("history:NWS|OPEN_METEO|SILURIAN", "feeds", "recompute"), host.calls)
        assertTrue(host.logs.any { it.first == TargetedForecastSyncRun.TAG_RESULT && "changed=false" in it.second })
    }

    @Test
    fun `tail repaints when the displayed actuals changed`() = runBlocking {
        val host = RecordingHost().apply { signatures = ArrayDeque(listOf("a", "b")) }
        TargetedForecastSyncRun { 0L }.runTail(host, setOf("NWS"))
        assertEquals("repaint:${TargetedForecastSyncRun.REPAINT_REASON}", host.calls.last())
    }

    @Test
    fun `a failing step is logged and the rest of the tail still runs`() = runBlocking {
        val host = RecordingHost().apply { failObservationHistory = true }
        TargetedForecastSyncRun { 0L }.runTail(host, setOf("NWS"))
        assertTrue("recompute" in host.calls)
        assertTrue(host.logs.any { it.first == TargetedForecastSyncRun.TAG_FAIL && "observation_history" in it.second })
    }

    @Test
    fun `tail yields while a user sync runs or a tap is fresh, then runs once the yield cap passes`() = runBlocking {
        var now = 100_000L
        val run = TargetedForecastSyncRun { now }
        val host = RecordingHost().apply { userSync = true }
        assertEquals(TailOutcome.YIELDED, run.runTail(host, setOf("NWS")))
        assertEquals(Enqueue.APPEND_AFTER_RUNNING, host.scheduled.last().first)
        assertEquals(TargetedForecastSyncRun.YIELD_DELAY_MS, host.scheduled.last().second)
        assertFalse("recompute" in host.calls)

        host.userSync = false
        host.lastInteraction = now - 1_000L
        now += 20_000L
        host.lastInteraction = now - 1_000L
        assertEquals(TailOutcome.YIELDED, run.runTail(host, setOf("NWS")))

        now += TargetedForecastSyncRun.MAX_YIELD_MS
        host.lastInteraction = now - 1_000L
        assertEquals(TailOutcome.RAN, run.runTail(host, setOf("NWS")))
        assertTrue("recompute" in host.calls)
    }

    @Test
    fun `an old interaction does not make the tail yield`() = runBlocking {
        val now = 100_000L
        val host = RecordingHost().apply { lastInteraction = now - TargetedForecastSyncRun.INTERACTION_QUIET_MS }
        assertEquals(TailOutcome.RAN, TargetedForecastSyncRun { now }.runTail(host, setOf("NWS")))
    }

    @Test
    fun `isTargeted is a forced, non-UI sync for one source`() {
        assertTrue(TargetedForecastSyncRun.isTargeted(forceRefresh = true, uiOnly = false, targetSourceId = "NWS"))
        assertFalse(TargetedForecastSyncRun.isTargeted(forceRefresh = true, uiOnly = false, targetSourceId = null))
        assertFalse(TargetedForecastSyncRun.isTargeted(forceRefresh = false, uiOnly = false, targetSourceId = "NWS"))
        assertFalse(TargetedForecastSyncRun.isTargeted(forceRefresh = true, uiOnly = true, targetSourceId = "NWS"))
    }

    @Test
    fun `source ids round-trip through the work-data encoding`() {
        val ids = setOf("NWS", "OPEN_METEO")
        assertEquals(ids, TargetedForecastSyncRun.decodeSources(TargetedForecastSyncRun.encodeSources(ids)))
        assertEquals(emptySet<String>(), TargetedForecastSyncRun.decodeSources(null))
    }
}
