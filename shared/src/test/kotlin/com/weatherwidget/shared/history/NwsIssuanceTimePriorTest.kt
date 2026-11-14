package com.weatherwidget.shared.history

import com.weatherwidget.data.local.HistorySnapshotRetention
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.shared.util.DailyForecastWrite
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Desktop NWS, 2026-11-04: asleep 19:33–03:10. The 18:45 fetch said 3 am = 4%; the Fold drew 25%.
 * The desktop's 03:10 fetch carried an issuance made at 02:30 — a real prior for 3 am. Judged by
 * issuance (`issuedAt`, NWS `updateTime`), both devices draw 25%. plans/261114-nws-issuance-time-prior.md
 */
@Category(ShortDuration::class)
class NwsIssuanceTimePriorTest {
    private val source = "NWS"
    private val lat = 37.417
    private val lon = -122.089
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.parse("2026-11-04")

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private inner class Store : ForecastPersistRun.Host<String> {
        override val sourceId = source
        override val latitude = lat
        override val longitude = lon
        val rows = mutableListOf<HourlyForecast>()

        override suspend fun storedHourly(startMs: Long, endMs: Long) =
            rows.filter { it.dateTime in startMs..endMs }.groupBy { it.dateTime }.values.map { hour -> hour.maxBy { it.fetchedAt } }
        override suspend fun writeHourly(rows: List<HourlyForecast>) { this.rows += rows.map { it.copy(locationLat = lat, locationLon = lon) } }
        override fun elapsedStore(label: Long, nowMs: Long) = object : ElapsedForecastBackfill.Store {
            override suspend fun insert(rows: List<HourlyForecast>) { this@Store.rows += rows.map { it.copy(source = source, locationLat = lat, locationLon = lon) } }
        }
        override suspend fun storedDaily(from: LocalDate, to: LocalDate) = emptyList<DailyForecastWrite.Stored<String>>()
        override suspend fun writeDaily(write: List<DailyForecastWrite.Row>, restamp: List<String>, nowMs: Long) = Unit
        override suspend fun log(tag: String, message: String, level: String) = Unit
    }

    private fun hr(at: Long, temp: Float, chance: Int, issuedAt: Long) =
        HourlyForecast(at, temp, "Cloudy", precipProbability = chance, source = source, issuedAt = issuedAt)

    /** Evening fetch 18:45 (issued 18:00) for 1–4 am, then a wake-up fetch of a later issuance. */
    private suspend fun replay(issued: Long, fetched: Long): Store {
        val store = Store()
        val evening = at(18, 45, day.minusDays(1))
        val eveningIssued = at(18, 0, day.minusDays(1))
        ForecastPersistRun(store).persist(
            ForecastPersistRun.Fetch(hourly = (1..4).map { hr(at(it), 50f, 4, eveningIssued) }, daily = null, keep = null),
            evening, zone,
        )
        ForecastPersistRun(store).persist(
            ForecastPersistRun.Fetch(
                hourly = (3..4).map { hr(at(it), 55f, 25, issued) },
                elapsedHourly = (1..2).map { hr(at(it), 55f, 25, issued) },
                daily = null, keep = null,
            ),
            fetched, zone,
        )
        return store
    }

    private fun stitchedAt3(store: Store) =
        HourlyForecastStitcher.stitch(store.rows, lat, lon, nowMs = at(5)).single { it.dateTime == at(3) }

    @Test
    fun `a wake-up fetch of an issuance made before the hour is that hour's prior`() = runBlocking {
        val row = stitchedAt3(replay(issued = at(2, 30), fetched = at(3, 10)))
        println("3am: temp=${row.temperature} rain=${row.precipProbability} madeAt=${row.madeAt} rainMadeAt=${row.rainFetchedAt}")
        assertEquals(25, row.precipProbability)
        assertEquals(55f, row.temperature, 0f)
        assertEquals("rain made at the 02:30 issuance", at(2, 30), row.rainFetchedAt)
    }

    @Test
    fun `an issuance made after the hour stays a hindcast`() = runBlocking {
        val row = stitchedAt3(replay(issued = at(3, 40), fetched = at(3, 50)))
        println("3am: temp=${row.temperature} rain=${row.precipProbability}")
        assertEquals("rain keeps the evening prior", 4, row.precipProbability)
    }

    @Test
    fun `hours before the issuance stay hindcasts even when it is a prior for later ones`() = runBlocking {
        val store = replay(issued = at(2, 30), fetched = at(3, 10))
        val stitched = HourlyForecastStitcher.stitch(store.rows, lat, lon, nowMs = at(5)).associateBy { it.dateTime }
        println("rain 1..4am=${(1..4).map { stitched[at(it)]?.precipProbability }}")
        assertEquals(listOf(4, 4, 25, 25), (1..4).map { stitched[at(it)]?.precipProbability })
    }

    @Test
    fun `an issuance stamped after its fetch is clamped to the fetch`() {
        val row = HourlyForecast(at(3), 55f, "Cloudy", fetchedAt = at(3, 10), issuedAt = at(3, 30))
        assertEquals(at(3, 10), row.madeAt)
        assertEquals(at(3, 10), HourlyForecast(at(3), 55f, "Cloudy", fetchedAt = at(3, 10)).madeAt)
    }

    @Test
    fun `the prune keeps the issuance-made prior`() = runBlocking {
        val store = replay(issued = at(2, 30), fetched = at(3, 10))
        val rows = store.rows.map {
            HistorySnapshotRetention.Row(it.source!!, it.dateTime, lat, lon, it.fetchedAt, it.fetchedAt, precipProbability = it.precipProbability, issuedAt = it.issuedAt)
        }
        val keep = HistorySnapshotRetention.keptKeys(rows, zone, nowMs = at(5))
        val kept = store.rows.filter { r -> rows.first { it.dateTime == r.dateTime && it.fetchedAt == r.fetchedAt }.key in keep }
        assertEquals(
            HourlyForecastStitcher.stitch(store.rows, lat, lon, nowMs = at(5)),
            HourlyForecastStitcher.stitch(kept, lat, lon, nowMs = at(5)),
        )
    }
}
