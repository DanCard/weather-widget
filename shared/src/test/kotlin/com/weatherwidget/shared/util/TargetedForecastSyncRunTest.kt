package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import com.weatherwidget.shared.actuals.InMemoryReducedSignatureStore
import com.weatherwidget.shared.observations.NwsGapFillRun
import com.weatherwidget.shared.observations.ObservationBackfillRetryPolicy
import com.weatherwidget.shared.observations.ObservationWriteRun
import com.weatherwidget.shared.observations.StationNetworkRefreshRun
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
import java.time.LocalDate
import java.time.ZoneOffset

@Category(ShortDuration::class)
class TargetedForecastSyncRunTest {

    private class RecordingHost : TargetedForecastSyncRun.Host {
        val calls = mutableListOf<String>()
        val scheduled = mutableListOf<Triple<Enqueue, Long, Set<String>>>()
        val fetchedMetarHours = mutableListOf<Int>()
        val recomputedDateKeys = mutableListOf<Long>()
        val historyRanges = mutableListOf<Pair<Long, Long>>()
        var state = TailState.NONE
        var visible = setOf("NWS")
        var bgSources = listOf(WeatherSource.NWS, WeatherSource.SILURIAN)
        var displayedSources = setOf("NWS")
        var actualsPref: (WeatherSource) -> WeatherSource? = { if (it == WeatherSource.SILURIAN) WeatherSource.METAR else null }
        var historySnapshots = ArrayDeque(listOf(emptyList<DailyHistory>(), emptyList()))
        var fillResult: NwsGapFillRun.Result? = null
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
        override suspend fun fillObservationHistory(sourceIds: Set<String>): NwsGapFillRun.Result? {
            calls += "history:${sourceIds.sorted().joinToString("|")}"
            if (failObservationHistory) error("nws down")
            return fillResult
        }
        override fun stationNetworkHost(): StationNetworkRefreshRun.Host = object : StationNetworkRefreshRun.Host {
            override val lat = 37.4220
            override val lon = -122.0841
            override suspend fun backgroundSources() = bgSources
            override suspend fun displayedSourceIds() = displayedSources
            override fun actualsPreference(source: WeatherSource) = actualsPref(source)
            override suspend fun newestStoredTimestampMs(api: String): Long? = null
            override suspend fun fetchMetar(hours: Int): List<ObservationReading> {
                calls += "metar:$hours"
                fetchedMetarHours += hours
                return emptyList()
            }
            override fun writeHost(): ObservationWriteRun.Host = object : ObservationWriteRun.Host {
                override val latitude = 37.4220
                override val longitude = -122.0841
                override suspend fun insert(rows: List<ObservationReading>) = Unit
                override suspend fun storedKeys(api: String, stationIds: Set<String>, startMs: Long, endMs: Long) = emptyList<ObservationWriteRun.StoredKey>()
                override suspend fun delete(api: String, stationId: String, timestamps: List<Long>) = 0
                override suspend fun retag(api: String, stationIds: Set<String>, stationType: StationType) = 0
                override suspend fun retireProducts(api: String) = Unit
                override suspend fun log(tag: String, message: String, level: String) = Unit
            }
            override suspend fun log(tag: String, message: String, level: String) {
                logs += tag to message
            }
        }
        override fun blendRecomputeHost(): DailyBlendRecomputeRun.Host = object : DailyBlendRecomputeRun.Host {
            override val lat = 37.4220
            override val lon = -122.0841
            override fun personalStationWeight() = 0.5
            override suspend fun observations(startMs: Long, endMs: Long) = emptyList<ObservationReading>()
            override suspend fun hourlyAllSources(startMs: Long, endMs: Long) = emptyList<HourlyForecast>()
            override suspend fun observationSignature(startMs: Long, endMs: Long): String? = null
            override suspend fun dailyHistoryAt(dateKey: Long): List<DailyHistory> {
                recomputedDateKeys += dateKey
                return emptyList()
            }
            override suspend fun insert(rows: List<DailyHistory>) = Unit
            override suspend fun updateBlendIfUnchanged(merged: DailyHistory, newUpdatedAt: Long, expectedUpdatedAt: Long) = 0
            override val signatures = InMemoryReducedSignatureStore()
            override suspend fun log(tag: String, message: String, level: String) = Unit
            override fun elapsedMs() = 0L
        }
        override suspend fun repairsAndUpkeep(nowMs: Long) { calls += "repairs" }
        override suspend fun dailyHistoryRows(startDateKey: Long, endDateKey: Long): List<DailyHistory> {
            historyRanges += startDateKey to endDateKey
            return if (historySnapshots.isNotEmpty()) historySnapshots.removeFirst() else emptyList()
        }
        override suspend fun repaint(reason: String) { calls += "repaint:$reason" }
        override fun userSyncActive() = userSync
        override fun lastInteractionMs() = lastInteraction
        override suspend fun log(tag: String, message: String, level: String) {
            println("[$level] $tag $message")
            logs += tag to message
        }
    }

    private val fixedWallMs = LocalDate.of(2026, 11, 23).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun runner(clock: () -> Long = { 0L }) =
        TargetedForecastSyncRun(clock = clock, wallClock = { fixedWallMs }, zone = { ZoneOffset.UTC })

    @Test
    fun `fast path runs afterFetch then paint then schedules the tail, with no observation or recompute work`() = runBlocking {
        val host = RecordingHost()
        runner().onFetched(host, "OPEN_METEO", stored = true)
        assertEquals(listOf("afterFetch", "paint", "schedule:SCHEDULE"), host.calls)
        assertEquals(TargetedForecastSyncRun.TAIL_DELAY_MS, host.scheduled.single().second)
        assertEquals(setOf("OPEN_METEO"), host.scheduled.single().third)
    }

    @Test
    fun `a fetch that stored nothing paints from cache and asks for no tail`() = runBlocking {
        val host = RecordingHost()
        runner().onFetched(host, "OPEN_METEO", stored = false)
        assertEquals(listOf("paint"), host.calls)
    }

    @Test
    fun `a burst keeps one pending tail, which settles on the newest request and serves every source`() = runBlocking {
        var now = 0L
        val run = runner { now }
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
        assertFalse("repairs" in host.calls)

        now = 17_000L
        host.calls.clear()
        assertEquals(TailOutcome.RAN, run.runTail(host, setOf("GOOGLE_WEATHER")))
        assertEquals("history:GOOGLE_WEATHER|NWS|OPEN_METEO", host.calls.first())
    }

    @Test
    fun `a burst cannot push its tail past the cap`() = runBlocking {
        var now = 0L
        val run = runner { now }
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
        runner().requestTail(host, setOf("NWS"))
        assertEquals(Enqueue.APPEND_AFTER_RUNNING, host.scheduled.single().first)
    }

    @Test
    fun `tail runs history, deep METAR station step, 9-day recompute, repairs, and skips repaint when unchanged`() = runBlocking {
        var now = 0L
        val run = runner { now }
        val host = RecordingHost()
        run.requestTail(host, setOf("OPEN_METEO"))
        now = TargetedForecastSyncRun.TAIL_DELAY_MS
        host.calls.clear()
        assertEquals(TailOutcome.RAN, run.runTail(host, setOf("SILURIAN")))
        assertEquals(listOf("history:NWS|OPEN_METEO|SILURIAN", "metar:24", "repairs"), host.calls)
        val today = LocalDate.of(2026, 11, 23)
        val expectedStartKey = today.minusDays(DailyBlendRecomputeRun.MAX_DAYS_BACK).toEpochDay() * 86_400_000L
        val expectedEndKey = today.toEpochDay() * 86_400_000L
        assertEquals(listOf(expectedStartKey to expectedEndKey, expectedStartKey to expectedEndKey), host.historyRanges)
        assertTrue(host.logs.any { it.first == TargetedForecastSyncRun.TAG_RESULT && "changed=false" in it.second })
    }

    @Test
    fun `tail repaints when the daily_history rows changed`() = runBlocking {
        val before = listOf(
            DailyHistory(
                date = 0L, source = "NWS", locationLat = 37.4220, locationLon = -122.0841,
                computedHighTemp = 70f, computedLowTemp = 50f, condition = "Clear", updatedAt = 1L,
            ),
        )
        val after = listOf(
            DailyHistory(
                date = 0L, source = "NWS", locationLat = 37.4220, locationLon = -122.0841,
                computedHighTemp = 72f, computedLowTemp = 50f, condition = "Clear", updatedAt = 2L,
            ),
        )
        val host = RecordingHost().apply { historySnapshots = ArrayDeque(listOf(before, after)) }
        runner().runTail(host, setOf("NWS"))
        assertEquals("repaint:${TargetedForecastSyncRun.REPAINT_REASON}", host.calls.last())
    }

    @Test
    fun `unreachable history fill re-arms the tail on 10s, 1m, 4m then stops`() = runBlocking {
        val run = runner()
        val host = RecordingHost().apply {
            fillResult = NwsGapFillRun.Result(
                needed = setOf(LocalDate.of(2026, 11, 23)),
                remaining = setOf(LocalDate.of(2026, 11, 23)),
                stationsTried = 0,
                rows = 0,
                unreachable = true,
            )
        }

        // Attempts 1..3 schedule 10s, 60s, 240s; attempt 4 stops.
        repeat(4) { run.runTail(host, setOf("NWS")) }

        assertEquals(
            ObservationBackfillRetryPolicy.RETRY_DELAYS_MS.toList(),
            host.scheduled.map { it.second },
        )
        assertTrue(host.scheduled.all { it.first == Enqueue.APPEND_AFTER_RUNNING })
        assertEquals(
            3,
            host.logs.count { it.first == ObservationBackfillRetryPolicy.LOG_TAG && "reason=${TargetedForecastSyncRun.REPAINT_REASON}" in it.second },
        )
    }

    @Test
    fun `a failing step is logged and the rest of the tail still runs`() = runBlocking {
        val host = RecordingHost().apply { failObservationHistory = true }
        runner().runTail(host, setOf("NWS"))
        assertTrue("repairs" in host.calls)
        assertTrue(host.logs.any { it.first == TargetedForecastSyncRun.TAG_FAIL && "observation_history" in it.second })
    }

    @Test
    fun `tail yields while a user sync runs or a tap is fresh, then runs once the yield cap passes`() = runBlocking {
        var now = 100_000L
        val run = runner { now }
        val host = RecordingHost().apply { userSync = true }
        assertEquals(TailOutcome.YIELDED, run.runTail(host, setOf("NWS")))
        assertEquals(Enqueue.APPEND_AFTER_RUNNING, host.scheduled.last().first)
        assertEquals(TargetedForecastSyncRun.YIELD_DELAY_MS, host.scheduled.last().second)
        assertFalse("repairs" in host.calls)

        host.userSync = false
        host.lastInteraction = now - 1_000L
        now += 20_000L
        host.lastInteraction = now - 1_000L
        assertEquals(TailOutcome.YIELDED, run.runTail(host, setOf("NWS")))

        now += TargetedForecastSyncRun.MAX_YIELD_MS
        host.lastInteraction = now - 1_000L
        assertEquals(TailOutcome.RAN, run.runTail(host, setOf("NWS")))
        assertTrue("repairs" in host.calls)
    }

    @Test
    fun `an old interaction does not make the tail yield`() = runBlocking {
        val now = 100_000L
        val host = RecordingHost().apply { lastInteraction = now - TargetedForecastSyncRun.INTERACTION_QUIET_MS }
        assertEquals(TailOutcome.RAN, runner { now }.runTail(host, setOf("NWS")))
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
