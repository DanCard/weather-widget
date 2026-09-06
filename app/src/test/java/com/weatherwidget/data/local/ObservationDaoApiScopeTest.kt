package com.weatherwidget.data.local

import androidx.room.Room
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The api-scoped observation read must be a **pure filter** on the unscoped one.
 *
 * The scoped query exists so the rows the blend would discard anyway never cross the CursorWindow
 * (measured 2026-09-06: 34,726 SYNOPTIC rows against 7,862 NWS in one window, all of the former
 * dropped by `ObservationSourceMatcher.matchesActualSource`). The hazard is that a second `@Query`
 * drifts from the first's ORDER BY. That order is load-bearing: it leaks into the blend's
 * `groupBy { stationId }`, which decides `dominantStationByDay`'s tie-break and `anchorStation`, and
 * the same rows in a different order rendered two alternating series with blinking high/low labels.
 * See [ObservationDao.getObservationCandidatesInRange]'s comment and ActualsRowOrderDeterminismTest.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ObservationDaoApiScopeTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: ObservationDao

    private val lat = 37.4168
    private val lon = -122.0890

    private fun obs(
        stationId: String,
        timestamp: Long,
        api: String,
        lat: Double = this.lat,
        lon: Double = this.lon,
    ) = ObservationEntity(
        stationId = stationId,
        stationName = "$stationId name",
        timestamp = timestamp,
        temperature = 70f,
        condition = "Fair",
        locationLat = lat,
        locationLon = lon,
        fetchedAt = timestamp,
        api = api,
    )

    /**
     * Deliberately interleaved in time and sharing timestamps across apis and stations — a scoped
     * query that reordered rows, or that sorted only by timestamp, would pass a tidier fixture.
     */
    private val rows = listOf(
        obs("KNUQ", 1_000L, "NWS"),
        obs("KSJC", 1_000L, "SYNOPTIC"),
        obs("AW020", 1_000L, "NWS"),
        obs("G4110", 2_000L, "SYNOPTIC"),
        obs("KNUQ", 2_000L, "NWS"),
        obs("OPEN_METEO_MAIN", 2_000L, "OPEN_METEO"),
        obs("KSJC", 3_000L, "SYNOPTIC"),
        obs("KNUQ", 3_000L, "NWS"),
        // Same station and timestamp under two apis — the pair the dedup key keeps apart.
        obs("KPAO", 4_000L, "NWS"),
        obs("KPAO", 4_000L, "SYNOPTIC"),
    )

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.observationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed() = dao.insertAll(rows)

    private fun key(e: ObservationEntity) = "${e.timestamp}|${e.stationId}|${e.api}"

    @Test
    fun `scoping to every present api returns the unscoped rows in the same order`() = runTest {
        seed()
        val unscoped = dao.getObservationCandidatesInRange(0L, 10_000L, lat, lon)
        val scopedToAll = dao.getObservationCandidatesInRangeForApis(
            0L, 10_000L, lat, lon,
            listOf("NWS", "SYNOPTIC", "OPEN_METEO"),
        )

        assertEquals(rows.size, unscoped.size)
        // Order equality, not set equality — that is the whole point of the test.
        assertEquals(unscoped.map(::key), scopedToAll.map(::key))
    }

    @Test
    fun `scoping to one api keeps that api's rows in their unscoped relative order`() = runTest {
        seed()
        val unscoped = dao.getObservationCandidatesInRange(0L, 10_000L, lat, lon)
        val scoped = dao.getObservationCandidatesInRangeForApis(0L, 10_000L, lat, lon, listOf("NWS"))

        assertEquals(unscoped.filter { it.api == "NWS" }.map(::key), scoped.map(::key))
        assertTrue(scoped.isNotEmpty())
        assertTrue(scoped.none { it.api != "NWS" })
    }

    @Test
    fun `readObservationsInRange honours the scope and defaults to every api`() = runTest {
        seed()
        val all = dao.readObservationsInRange(0L, 10_000L, lat, lon).rows
        val nwsOnly = dao.readObservationsInRange(0L, 10_000L, lat, lon, listOf("NWS")).rows

        assertTrue(all.any { it.api == "SYNOPTIC" })
        assertTrue(nwsOnly.none { it.api != "NWS" })
        // The merge runs identically either side of the filter, so the scoped result is exactly the
        // unscoped one with the other apis removed — never a different survivor per (station, ts).
        assertEquals(all.filter { it.api == "NWS" }.map(::key), nwsOnly.map(::key))
    }

    @Test
    fun `an api with no rows yields an empty read rather than falling back to every api`() = runTest {
        seed()
        val none = dao.readObservationsInRange(0L, 10_000L, lat, lon, listOf("TOMORROW_IO")).rows
        assertEquals(emptyList<String>(), none.map(::key))
    }
}
