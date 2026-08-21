package com.weatherwidget.data.local

import androidx.room.Room
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * touchLatestFetchedAt marks a completed fetch *attempt* on a station whose fetch yielded
 * nothing storable (KNUQ 2026-07-13: feed publishing only null-temperature reports). It must
 * refresh fetchedAt on exactly the station's newest row — older rows and other stations keep
 * their original stamps, and an unknown station is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ObservationDaoTouchTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: ObservationDao

    private fun obs(
        stationId: String,
        timestamp: Long,
        fetchedAt: Long,
        lat: Double = 37.42,
        lon: Double = -122.08,
        api: String = "NWS",
    ) = ObservationEntity(
        stationId = stationId,
        stationName = "$stationId name",
        timestamp = timestamp,
        temperature = 70f,
        condition = "Fair",
        locationLat = lat,
        locationLon = lon,
        fetchedAt = fetchedAt,
        api = api,
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
    fun teardown() {
        db.close()
    }

    @Test
    fun touch_updatesOnlyNewestRowOfTargetStation() = runTest {
        dao.insertAll(
            listOf(
                obs("KNUQ", timestamp = 1_000L, fetchedAt = 1_500L),
                obs("KNUQ", timestamp = 2_000L, fetchedAt = 2_500L), // newest KNUQ row
                obs("KSJC", timestamp = 3_000L, fetchedAt = 3_500L), // different station
            ),
        )

        dao.touchLatestFetchedAt("KNUQ", 37.42, -122.08, nowMs = 9_000L)

        val knuq = dao.getRecentObservations(0L).filter { it.stationId == "KNUQ" }.sortedBy { it.timestamp }
        assertEquals(1_500L, knuq[0].fetchedAt) // older row untouched
        assertEquals(9_000L, knuq[1].fetchedAt) // newest row records the attempt
        assertEquals(3_500L, dao.getLatestForStation("KSJC", 37.42, -122.08)?.fetchedAt) // other station untouched
    }

    @Test
    fun storingOlderReading_thenTouching_refreshesTheNewestRowsAttemptStamp() = runTest {
        // KPAO 2026-07-13: the newest stored reading (a 20:47 web-fallback row) predated a later
        // fetch that could only store an OLDER NWS observation (19:47) — the stations list shows
        // the newest row, whose fetchedAt stayed frozen at the earlier attempt ("Fetched 9:45").
        // The repository success path now touches after every insert; this pins the DAO mechanics
        // it relies on: the touch lands on the newest row even when the insert was an older one.
        dao.insertAll(listOf(obs("KPAO", timestamp = 2_000L, fetchedAt = 2_500L))) // newest reading, old attempt
        dao.insertAll(listOf(obs("KPAO", timestamp = 1_000L, fetchedAt = 9_000L))) // older reading, new attempt

        dao.touchLatestFetchedAt("KPAO", 37.42, -122.08, nowMs = 9_000L)

        val kpao = dao.getRecentObservations(0L).filter { it.stationId == "KPAO" }.sortedBy { it.timestamp }
        assertEquals(9_000L, kpao[0].fetchedAt) // the older reading keeps its own attempt stamp
        assertEquals(9_000L, kpao[1].fetchedAt) // newest row now reflects the latest attempt
    }

    @Test
    fun touch_unknownStationIsNoOp() = runTest {
        dao.insertAll(listOf(obs("KSJC", timestamp = 3_000L, fetchedAt = 3_500L)))

        dao.touchLatestFetchedAt("KNUQ", 37.42, -122.08, nowMs = 9_000L)

        assertEquals(3_500L, dao.getLatestForStation("KSJC", 37.42, -122.08)?.fetchedAt)
        assertEquals(1, dao.getRecentObservations(0L).size) // nothing inserted
    }

    @Test
    fun tomorrowCleanup_keepsRecentHistoryAndRealtime_only() = runTest {
        dao.insertAll(
            listOf(
                obs("TOMORROW_IO_MAIN", 1_000L, 1_000L, api = "TOMORROW_IO"),
                obs("TOMORROW_IO_RECENT_HISTORY", 2_000L, 2_000L, api = "TOMORROW_IO"),
                obs("TOMORROW_IO_REALTIME", 3_000L, 3_000L, api = "TOMORROW_IO"),
                obs("KNUQ", 4_000L, 4_000L),
            ),
        )

        assertEquals(1, dao.deleteLegacyTomorrowIoObservations())

        assertEquals(
            setOf("TOMORROW_IO_RECENT_HISTORY", "TOMORROW_IO_REALTIME", "KNUQ"),
            dao.getRecentObservations(0L).map { it.stationId }.toSet(),
        )
    }

    @Test
    fun sameStationTimestamp_isStoredAndTouchedIndependentlyPerSite() = runTest {
        dao.insertAll(
            listOf(
                obs("KPAO", timestamp = 2_000L, fetchedAt = 2_500L, lat = 37.42, lon = -122.08),
                obs("KPAO", timestamp = 2_000L, fetchedAt = 3_500L, lat = 38.58, lon = -121.49),
            ),
        )

        dao.touchLatestFetchedAt("KPAO", 37.42, -122.08, nowMs = 9_000L)

        val rows = dao.getRecentObservations(0L).filter { it.stationId == "KPAO" }
        assertEquals(2, rows.size)
        assertEquals(9_000L, rows.single { it.locationLat == 37.42 }.fetchedAt)
        assertEquals(3_500L, rows.single { it.locationLat == 38.58 }.fetchedAt)
    }

    @Test
    fun rangeQuery_returnsOnlyTheNearestPhysicalSite() = runTest {
        dao.insertAll(
            listOf(
                obs("KPAO", timestamp = 2_000L, fetchedAt = 2_500L, lat = 37.420, lon = -122.080),
                obs("KPAO", timestamp = 2_000L, fetchedAt = 3_500L, lat = 37.425, lon = -122.075),
            ),
        )

        val currentSite = dao.getObservationsInRange(
            startTs = 0L,
            endTs = 3_000L,
            lat = 37.420,
            lon = -122.080,
        )

        assertEquals(1, currentSite.size)
        assertEquals(37.420, currentSite.single().locationLat, 0.0)
        assertEquals(-122.080, currentSite.single().locationLon, 0.0)
    }
}
