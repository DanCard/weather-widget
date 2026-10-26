package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [NwsGapFillRun.daily] against an in-memory Host: storing rows adds observations, and recomputing a
 * day files its NWS daily_history row, so the need re-check after each station sees real state.
 */
@Category(ShortDuration::class)
class NwsGapFillRunTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 25)
    private val lat = 37.417
    private val lon = -122.089

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun key(day: LocalDate) = day.toEpochDay() * 86_400_000L

    private fun station(id: String) = NwsApi.StationInfo(id = id, name = id, lat = lat, lon = lon)

    private fun reading(stationId: String, ts: Long, type: StationType = StationType.OFFICIAL) = ObservationReading(
        stationId = stationId, stationName = stationId, timestamp = ts, temperature = 60f,
        condition = "Clear", locationLat = lat, locationLon = lon, api = "NWS", stationType = type,
    )

    /** Official readings every two hours over [hours] of [day]. */
    private fun everyTwoHours(stationId: String, day: LocalDate, hours: IntProgression = 0..22) =
        hours.step(2).map { reading(stationId, at(day, it)) }

    private inner class FakeHost(
        var stationsOutcome: FetchOutcome<List<NwsApi.StationInfo>> =
            FetchOutcome.Success(listOf(station("KNUQ"), station("KSJC"), station("KPAO"))),
        val windows: (String) -> NwsGapFillRun.StationRows = { NwsGapFillRun.StationRows(emptyList(), false) },
    ) : NwsGapFillRun.Host {
        val dayRows = mutableListOf<NwsGapFillRun.DayRow>()
        val obs = mutableListOf<NwsGapFillRun.ObsStamp>()
        val fetched = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, String>>()
        val logs = mutableListOf<String>()
        var stationLookups = 0

        /** A complete stored day: an NWS daily row plus official readings every two hours. */
        fun complete(day: LocalDate) {
            dayRows += NwsGapFillRun.DayRow(key(day), WeatherSource.NWS.id)
            (0..22 step 2).forEach { obs += NwsGapFillRun.ObsStamp(at(day, it), "NWS", "KNUQ", StationType.OFFICIAL) }
        }

        override suspend fun stations(latitude: Double, longitude: Double) = stationsOutcome.also { stationLookups++ }

        override suspend fun fetchStation(
            station: NwsApi.StationInfo, index: Int, latitude: Double, longitude: Double,
            startIso: String, endIso: String, fallbackLogTag: String,
        ): NwsGapFillRun.StationRows {
            fetched += station.id
            requests += startIso to endIso
            return windows(station.id)
        }

        override suspend fun store(rows: List<ObservationReading>, latitude: Double, longitude: Double) {
            obs += rows.map { NwsGapFillRun.ObsStamp(it.timestamp, it.api, it.stationId, it.stationType) }
        }

        override suspend fun dailyHistoryRows(startDayMs: Long, endDayMs: Long, latitude: Double, longitude: Double) =
            dayRows.filter { it.dateMs in startDayMs..endDayMs }

        override suspend fun observations(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
            obs.filter { it.timestamp in startMs until endMs }

        val recomputed = mutableListOf<Set<LocalDate>>()

        override suspend fun recomputeExtremes(latitude: Double, longitude: Double, days: Set<LocalDate>) {
            recomputed += days
            days.forEach { day ->
                if (dayRows.none { it.dateMs == key(day) && it.source == "NWS" }) dayRows += NwsGapFillRun.DayRow(key(day), "NWS")
            }
        }

        override suspend fun log(tag: String, message: String, level: String) {
            logs += "$tag $message"
        }
    }

    private suspend fun run(host: FakeHost, nowMs: Long = at(today, 10)) =
        NwsGapFillRun.daily(host, lat, lon, nowMs, zone)

    @Test
    fun `complete days make no station lookup and no fetch`() = runBlocking<Unit> {
        val host = FakeHost().apply { (0L..2L).forEach { complete(today.minusDays(it)) } }

        val result = run(host)

        assertEquals(emptySet<LocalDate>(), result.needed)
        assertEquals(0, host.stationLookups)
        assertEquals(emptyList<String>(), host.fetched)
    }

    @Test
    fun `today joins the need only from 02 00`() = runBlocking<Unit> {
        val host = FakeHost().apply { complete(today.minusDays(2)); complete(today.minusDays(1)) }

        assertEquals(emptySet<LocalDate>(), run(host, nowMs = at(today, 1, 59)).needed)
        assertEquals(setOf(today), run(host, nowMs = at(today, 2)).needed)
    }

    @Test
    fun `a past day with only morning rows is needed`() = runBlocking<Unit> {
        val host = FakeHost().apply {
            complete(today.minusDays(2)); complete(today)
            dayRows += NwsGapFillRun.DayRow(key(today.minusDays(1)), "NWS")
            obs += NwsGapFillRun.ObsStamp(at(today.minusDays(1), 6), "NWS", "KNUQ")
        }

        assertEquals(setOf(today.minusDays(1)), run(host).needed)
    }

    @Test
    fun `the blend's synthetic row does not count as afternoon coverage`() = runBlocking<Unit> {
        val yesterday = today.minusDays(1)
        val host = FakeHost().apply {
            complete(today.minusDays(2)); complete(today)
            dayRows += NwsGapFillRun.DayRow(key(yesterday), "NWS")
            obs += NwsGapFillRun.ObsStamp(at(yesterday, 6), "NWS", "KNUQ")
            obs += NwsGapFillRun.ObsStamp(at(yesterday, 16), "NWS", NwsBlend.STATION_ID)
        }

        assertEquals(setOf(yesterday), run(host).needed)
    }

    @Test
    fun `another source's daily row does not satisfy the need`() = runBlocking<Unit> {
        val host = FakeHost().apply {
            complete(today.minusDays(2)); complete(today)
            dayRows += NwsGapFillRun.DayRow(key(today.minusDays(1)), "OPEN_METEO")
        }

        assertEquals(setOf(today.minusDays(1)), run(host).needed)
    }

    @Test
    fun `today with a row but an overnight official hole is needed - personal stations do not close it`() = runBlocking<Unit> {
        // Desktop, 2026-10-26: asleep 00:36-08:22; KNUQ at 00:15 then 06:55, AW020 all night.
        val host = FakeHost(windows = { id -> NwsGapFillRun.StationRows(everyTwoHours(id, today, 0..8), false) }).apply {
            complete(today.minusDays(2)); complete(today.minusDays(1))
            dayRows += NwsGapFillRun.DayRow(key(today), "NWS")
            obs += NwsGapFillRun.ObsStamp(at(today, 0, 15), "NWS", "KNUQ", StationType.OFFICIAL)
            obs += NwsGapFillRun.ObsStamp(at(today, 6, 55), "NWS", "KNUQ", StationType.OFFICIAL)
            obs += NwsGapFillRun.ObsStamp(at(today, 8, 15), "NWS", "KNUQ", StationType.OFFICIAL)
            (0..8).forEach { obs += NwsGapFillRun.ObsStamp(at(today, it, 10), "NWS", "AW020", StationType.PERSONAL) }
        }

        val result = run(host, nowMs = at(today, 8, 23))

        assertEquals(setOf(today), result.needed)
        assertEquals(listOf("KNUQ"), host.fetched)
        assertEquals(emptySet<LocalDate>(), result.remaining)
        assertEquals(listOf(setOf(today)), host.recomputed)
    }

    @Test
    fun `a past day with afternoon rows but a long midday official hole is needed`() = runBlocking<Unit> {
        val yesterday = today.minusDays(1)
        val host = FakeHost().apply {
            complete(today.minusDays(2)); complete(today)
            dayRows += NwsGapFillRun.DayRow(key(yesterday), "NWS")
            listOf(0, 2, 4, 6, 14, 16, 18, 20, 22).forEach {
                obs += NwsGapFillRun.ObsStamp(at(yesterday, it), "NWS", "KNUQ", StationType.OFFICIAL)
            }
        }

        assertEquals(setOf(yesterday), run(host).needed)
    }

    @Test
    fun `official holes of up to 3 h are not a need, today's trailing edge included`() = runBlocking<Unit> {
        val host = FakeHost().apply {
            complete(today.minusDays(2)); complete(today.minusDays(1))
            dayRows += NwsGapFillRun.DayRow(key(today), "NWS")
            listOf(0, 3, 6).forEach { obs += NwsGapFillRun.ObsStamp(at(today, it), "NWS", "KNUQ", StationType.OFFICIAL) }
        }

        assertEquals(emptySet<LocalDate>(), run(host, nowMs = at(today, 9)).needed)
        assertEquals(setOf(today), run(host, nowMs = at(today, 9, 1)).needed)
    }

    @Test
    fun `stops at the first station that completes the need`() = runBlocking<Unit> {
        val yesterday = today.minusDays(1)
        val host = FakeHost(windows = { id ->
            NwsGapFillRun.StationRows(everyTwoHours(id, yesterday), false)
        }).apply { complete(today.minusDays(2)); complete(today) }

        val result = run(host)

        assertEquals(listOf("KNUQ"), host.fetched)
        assertEquals(emptySet<LocalDate>(), result.remaining)
        assertEquals(12, result.rows)
        assertFalse(result.unreachable)
    }

    @Test
    fun `a station that leaves the day incomplete passes to the next`() = runBlocking<Unit> {
        val yesterday = today.minusDays(1)
        val host = FakeHost(windows = { id ->
            val hours = if (id == "KNUQ") 0..12 else 0..22
            NwsGapFillRun.StationRows(everyTwoHours(id, yesterday, hours), false)
        }).apply { complete(today.minusDays(2)); complete(today) }

        val result = run(host)

        assertEquals(listOf("KNUQ", "KSJC"), host.fetched)
        assertEquals(emptySet<LocalDate>(), result.remaining)
    }

    @Test
    fun `asks each station for the last 3 days`() = runBlocking<Unit> {
        val host = FakeHost()
        val now = at(today, 10)

        run(host, nowMs = now)

        val (start, end) = host.requests.first()
        assertEquals(Instant.ofEpochMilli(now).minusSeconds(3 * 86_400L), Instant.parse(start))
        assertEquals(Instant.ofEpochMilli(now), Instant.parse(end))
    }

    @Test
    fun `tries at most the shared station cap`() = runBlocking<Unit> {
        val host = FakeHost(stationsOutcome = FetchOutcome.Success((1..8).map { station("S$it") }))

        run(host)

        assertEquals(NwsGapFillRun.MAX_STATIONS, host.fetched.size)
    }

    @Test
    fun `station lookup failure is unreachable and fetches nothing`() = runBlocking<Unit> {
        val host = FakeHost(stationsOutcome = FetchOutcome.Failed("HttpRequestTimeoutException: timeout"))

        val result = run(host)

        assertTrue(result.unreachable)
        assertEquals(emptyList<String>(), host.fetched)
        assertTrue(host.logs.any { it.startsWith("NWS_DAILY_BACKFILL_FAIL") && "reason=stations_unreachable" in it })
    }

    @Test
    fun `an answered empty station list is not unreachable`() = runBlocking<Unit> {
        val host = FakeHost(stationsOutcome = FetchOutcome.NoData)

        val result = run(host)

        assertFalse(result.unreachable)
        assertTrue(host.logs.any { it.startsWith("NWS_DAILY_BACKFILL_FAIL") && "reason=no_stations" in it })
    }

    @Test
    fun `every station failing is unreachable`() = runBlocking<Unit> {
        val host = FakeHost(windows = { NwsGapFillRun.StationRows(emptyList(), false, apiFailure = "IOException") })

        assertTrue(run(host).unreachable)
    }

    @Test
    fun `stations answering empty are a real limit, logged incomplete, not unreachable`() = runBlocking<Unit> {
        val host = FakeHost()

        val result = run(host)

        assertFalse(result.unreachable)
        assertEquals(result.needed, result.remaining)
        assertTrue(host.logs.any { it.startsWith("NWS_DAILY_BACKFILL_INCOMPLETE") })
    }

    @Test
    fun `a throwing station is logged and the next one is tried`() = runBlocking<Unit> {
        val host = FakeHost(windows = { id ->
            if (id == "KNUQ") throw java.io.IOException("reset") else NwsGapFillRun.StationRows(emptyList(), false)
        })

        run(host)

        assertTrue(host.logs.any { it.startsWith("NWS_DAILY_BACKFILL_STATION_FAIL station=KNUQ") })
        assertTrue("KSJC" in host.fetched)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates`() = runBlocking<Unit> {
        run(FakeHost(windows = { throw CancellationException("stop") }))
    }

    // --- recent(): the render-gap pass ---

    private suspend fun recent(host: FakeHost, lookbackHours: Long = 72, nowMs: Long = at(today, 10)) =
        NwsGapFillRun.recent(host, lat, lon, lookbackHours, nowMs, zone)

    @Test
    fun `recent tries every station - no early stop`() = runBlocking<Unit> {
        val host = FakeHost(windows = { id -> NwsGapFillRun.StationRows(listOf(reading(id, at(today, 6))), false) })

        val result = recent(host)

        assertEquals(listOf("KNUQ", "KSJC", "KPAO"), host.fetched)
        assertEquals(3, result.rowsFetched)
        assertEquals(3, result.stationsTried)
    }

    @Test
    fun `recent asks for lookbackHours, and skips the daily need check`() = runBlocking<Unit> {
        val host = FakeHost().apply { (0L..2L).forEach { complete(today.minusDays(it)) } }
        val now = at(today, 10)

        recent(host, lookbackHours = 24, nowMs = now)

        val (start, end) = host.requests.first()
        assertEquals(Instant.ofEpochMilli(now).minusSeconds(24 * 3600L), Instant.parse(start))
        assertEquals(Instant.ofEpochMilli(now), Instant.parse(end))
        assertTrue(host.logs.any { it.startsWith("OBS_HOURLY_BACKFILL_START") && "lookbackHours=24" in it })
    }

    @Test
    fun `recent recomputes the whole affected span once, gap days included`() = runBlocking<Unit> {
        val host = FakeHost(windows = { id ->
            NwsGapFillRun.StationRows(listOf(reading(id, at(today.minusDays(2), 9)), reading(id, at(today, 9))), false)
        })

        val result = recent(host)

        assertEquals(listOf(setOf(today.minusDays(2), today.minusDays(1), today)), host.recomputed)
        assertEquals(setOf(today.minusDays(2), today), result.affectedDates)
        assertTrue(host.logs.any { it.startsWith("OBS_HOURLY_BACKFILL_DONE") && "rows=6" in it })
    }

    @Test
    fun `recent with no rows recomputes nothing`() = runBlocking<Unit> {
        val host = FakeHost()

        recent(host)

        assertEquals(emptyList<Set<LocalDate>>(), host.recomputed)
    }

    @Test
    fun `recent - lookup failure is unreachable, an empty list is not`() = runBlocking<Unit> {
        val failed = FakeHost(stationsOutcome = FetchOutcome.Failed("HttpRequestTimeoutException: timeout"))
        assertTrue(recent(failed).unreachable)
        assertTrue(failed.logs.any { it.startsWith("OBS_HOURLY_BACKFILL_FAIL") && "reason=stations_unreachable" in it })

        assertFalse(recent(FakeHost(stationsOutcome = FetchOutcome.NoData)).unreachable)
    }

    @Test
    fun `recent - every station failing is unreachable, one answering is not`() = runBlocking<Unit> {
        assertTrue(recent(FakeHost(windows = { NwsGapFillRun.StationRows(emptyList(), false, apiFailure = "IOException") })).unreachable)

        val oneAnswers = FakeHost(windows = { id ->
            if (id == "KSJC") NwsGapFillRun.StationRows(emptyList(), false) else NwsGapFillRun.StationRows(emptyList(), false, apiFailure = "IOException")
        })
        assertFalse(recent(oneAnswers).unreachable)
    }
}
