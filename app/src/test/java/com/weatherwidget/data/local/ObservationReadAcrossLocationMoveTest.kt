package com.weatherwidget.data.local

import androidx.room.Room
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Bisects the 2026-09-03 "dominant station jumps backwards on a GPS move" report.
 *
 * On that day the phone moved twice (basketball court → trail), and each time the FIRST temperature
 * render at the new blend centre named an older KNUQ reading than the render before the move, then
 * recovered as soon as the post-move fetch stored rows under the new coordinate:
 *
 *   18:06:55  centre 37.41683,-122.08904   KNUQ 17:55 (11 min)   healthy
 *   18:28:25  GPS_RESAMPLE location_moved → 37.41656,-122.08669
 *   18:28:32  centre 37.41656,-122.08669   KNUQ 17:35 (53 min)   REGRESSED
 *   18:38:46  GPS_RESAMPLE location_moved → 37.42356,-122.08657
 *   18:38:51  centre 37.42356,-122.08657   KNUQ 17:55 (43 min)   REGRESSED
 *   18:38:56  same centre                  KNUQ 18:35 (3 min)    recovered
 *
 * This reconstructs the *database state* at the 18:38:51 render — the same rows, sites and reading
 * times — and asks the read path for the pool at the post-move centre. It deliberately does NOT
 * model timing, so it separates two candidate causes that the field logs cannot:
 *
 *  - **It fails** → the defect is in the read itself (box or [ObservationSiteMerge]) and this is the
 *    regression test for it.
 *  - **It passes** → the read is correct for this state, so the rows were lost upstream of it: in
 *    what the resolver hands the DAO at the instant the location flips, or in render timing. That
 *    narrows the hunt to the render pipeline and is why the test is worth keeping either way.
 *
 * The tolerance arithmetic already argues for "passes": the pre-move sites sit 0-2 thousandths of a
 * degree from the post-move centre against [ObservationSiteMerge.MERGE_TOLERANCE_DEG]'s 10. This
 * test is what turns that argument into a fact that stays true.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ObservationReadAcrossLocationMoveTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: ObservationDao

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    /** The court, where every reading up to 18:15 was fetched. */
    private val courtLat = 37.417
    private val courtLon = -122.089

    /** The second court-side fragment the same afternoon produced. */
    private val courtLat2 = 37.417
    private val courtLon2 = -122.087

    /** Where the 18:38:46 move landed, and the centre the failing render used. */
    private val movedCentreLat = 37.42356491088867
    private val movedCentreLon = -122.0865707397461

    /** The thin fragment the post-move METAR fetch created (13 rows in the field). */
    private val movedSiteLat = 37.424
    private val movedSiteLon = -122.087

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 9, 3, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun knuq(
        timestamp: Long,
        temp: Float,
        lat: Double,
        lon: Double,
        api: String = "NWS",
    ) = ObservationEntity(
        stationId = "KNUQ",
        stationName = "Moffett Federal Airfield",
        timestamp = timestamp,
        temperature = temp,
        condition = "Fair",
        locationLat = lat,
        locationLon = lon,
        distanceKm = 4.1f,
        fetchedAt = timestamp,
        api = api,
    )

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.observationDao()
    }

    @After
    fun teardown() = db.close()

    /** KNUQ reported 73.4 three times running, then 71.6 — the real sequence, which is why the value looked frozen. */
    private suspend fun seedIncidentState() {
        dao.insertAll(
            listOf(
                knuq(at(17, 15), 73.4f, courtLat, courtLon),
                knuq(at(17, 35), 73.4f, courtLat, courtLon),
                knuq(at(17, 55), 73.4f, courtLat, courtLon),
                knuq(at(18, 15), 71.6f, courtLat, courtLon),
                // The same readings also landed under the second court fragment.
                knuq(at(17, 15), 73.4f, courtLat2, courtLon2),
                knuq(at(17, 35), 73.4f, courtLat2, courtLon2),
                knuq(at(17, 55), 73.4f, courtLat2, courtLon2),
                // The post-move fetch's lone fresh row.
                knuq(at(18, 35), 71.6f, movedSiteLat, movedSiteLon),
            ),
        )
    }

    /** The render's own window: alignedCenter 19:00, minus 72 h, plus 60 h. */
    private val windowStart = LocalDateTime.of(2026, 9, 3, 19, 0).minusHours(72).atZone(zone).toInstant().toEpochMilli()
    private val windowEnd = LocalDateTime.of(2026, 9, 3, 19, 0).plusHours(60).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun postMoveReadStillSeesTheReadingsFetchedAtTheOldSite() = runTest {
        seedIncidentState()

        val pool = dao.getObservationsInRange(windowStart, windowEnd, movedCentreLat, movedCentreLon)
        val knuqTimes = pool.filter { it.stationId == "KNUQ" }.map { it.timestamp }.toSet()

        assertTrue(
            "18:15/71.6 was fetched at the court and must survive the move — this is the row the " +
                "18:38:51 render failed to show (it drew 17:55/73.4 instead)",
            at(18, 15) in knuqTimes,
        )
        assertEquals("newest KNUQ reachable after the move", at(18, 35), knuqTimes.max())
    }

    /**
     * The pre-move render is the control: the same rows, read from the centre the app was using
     * eight minutes earlier. Both centres must reach 18:15, or "the move lost it" is not the story.
     */
    @Test
    fun preMoveReadSeesTheSameReadings() = runTest {
        seedIncidentState()

        val pool = dao.getObservationsInRange(windowStart, windowEnd, 37.41655731201172, -122.0866928100586)
        val knuqTimes = pool.filter { it.stationId == "KNUQ" }.map { it.timestamp }.toSet()

        assertTrue("18:15 reachable from the pre-move centre too", at(18, 15) in knuqTimes)
    }

    /** The diagnostic must agree with the read, or it will mislead the next investigation. */
    @Test
    fun diagnosticsReportTheMergeAsInnocentForThisState() = runTest {
        seedIncidentState()

        val read = dao.readObservationsInRange(windowStart, windowEnd, movedCentreLat, movedCentreLon)

        assertFalse(
            "every site here is 0-2 thousandths from the centre, well inside the 0.01 tolerance",
            read.diagnostics.mergeDroppedFresher,
        )
        assertEquals(at(18, 35), read.diagnostics.mergedNewestMs)
        assertEquals(at(18, 35), read.diagnostics.candidateNewestMs)
    }

    /**
     * Teeth. A genuinely different town (0.075 deg away — inside the coarse +/-0.1 box, far outside
     * the merge tolerance) must NOT be merged in, and when it holds the newest rows the diagnostic
     * must say so. Without this, the assertions above would pass on a read that merged everything.
     */
    @Test
    fun aDifferentTownIsExcludedAndReportedAsDropped() = runTest {
        seedIncidentState()
        dao.insertAll(listOf(knuq(at(18, 50), 88.0f, 37.406, -122.021)))

        val read = dao.readObservationsInRange(windowStart, windowEnd, movedCentreLat, movedCentreLon)
        val knuqTimes = read.rows.filter { it.stationId == "KNUQ" }.map { it.timestamp }.toSet()

        assertFalse("the other town must not reach this blend", at(18, 50) in knuqTimes)
        assertTrue("and the diagnostic must name it", read.diagnostics.mergeDroppedFresher)
        assertTrue(
            read.diagnostics.droppedFresherSites.toString(),
            read.diagnostics.droppedFresherSites.any { it.startsWith("37.406,-122.021@") },
        )
    }
}
