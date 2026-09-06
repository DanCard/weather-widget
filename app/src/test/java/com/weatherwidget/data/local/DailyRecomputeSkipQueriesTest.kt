package com.weatherwidget.data.local

import androidx.room.Room
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The observation signature behind the settled-day recompute skip.
 *
 * A day's extremes cannot move while its observations are unchanged, and 91% of per-day recomputes
 * were measured producing a byte-identical result. The signature is what lets that be detected
 * without doing the work.
 *
 * The first attempt compared `MAX(fetchedAt)` against `daily_history.updatedAt` and **never fired
 * once on device**: observations are written `INSERT OR REPLACE`, so every deep fetch re-stamps rows
 * it already has. On a fully settled day, 7,315 of 7,633 rows carried a `fetchedAt` more than an
 * hour after their own `timestamp`. These tests pin the three things the replacement must notice,
 * and the one thing it must ignore.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class DailyRecomputeSkipQueriesTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: ObservationDao

    private val lat = 37.4168
    private val lon = -122.0890
    private val dayStart = 1_788_566_400_000L
    private val dayEnd = dayStart + 86_400_000L

    private fun obs(
        timestamp: Long,
        fetchedAt: Long,
        stationId: String = "KNUQ",
        temperature: Float = 70f,
    ) = ObservationEntity(
        stationId = stationId,
        stationName = "$stationId name",
        timestamp = timestamp,
        temperature = temperature,
        condition = "Fair",
        locationLat = lat,
        locationLon = lon,
        fetchedAt = fetchedAt,
        api = "NWS",
    )

    private suspend fun signature() = dao.observationSignatureInRange(dayStart, dayEnd, lat, lon)

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WeatherDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.observationDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `re-fetching the same rows with new fetch stamps does not change the signature`() = runTest {
        // The defect that killed the first attempt: a deep fetch REPLACEs rows it already has and
        // bumps fetchedAt, so any fetchedAt-based test reports change on every sync forever.
        dao.insertAll(listOf(obs(dayStart + 60_000, fetchedAt = 1_000L)))
        val before = signature()

        dao.insertAll(listOf(obs(dayStart + 60_000, fetchedAt = 999_000L)))
        assertEquals(before, signature())
    }

    @Test
    fun `an inserted row changes the signature`() = runTest {
        dao.insertAll(listOf(obs(dayStart + 60_000, fetchedAt = 1_000L)))
        val before = signature()

        dao.insertAll(listOf(obs(dayStart + 120_000, fetchedAt = 2_000L, stationId = "KSJC")))
        assertNotEquals(before, signature())
    }

    @Test
    fun `an interior backfill row changes the signature even though it is not the newest`() =
        runTest {
            dao.insertAll(
                listOf(
                    obs(dayStart + 60_000, fetchedAt = 1_000L),
                    obs(dayStart + 600_000, fetchedAt = 1_000L, stationId = "KSJC"),
                ),
            )
            val before = signature()

            // Lands in the middle, so MAX(timestamp) is unmoved — COUNT is what catches it.
            dao.insertAll(listOf(obs(dayStart + 300_000, fetchedAt = 3_000L, stationId = "KPAO")))
            assertNotEquals(before, signature())
        }

    @Test
    fun `a station revising a value in place changes the signature`() = runTest {
        // Same primary key, different temperature: COUNT and MAX(timestamp) both hold, so the
        // temperature sum is the only thing that can notice.
        dao.insertAll(listOf(obs(dayStart + 60_000, fetchedAt = 1_000L, temperature = 70f)))
        val before = signature()

        dao.insertAll(listOf(obs(dayStart + 60_000, fetchedAt = 2_000L, temperature = 72f)))
        assertNotEquals(before, signature())
    }

    @Test
    fun `measured precip arriving on an existing row changes the signature`() = runTest {
        // The regression ObservationRepositoryDailyMergeTest caught: precip arrives by REPLACE on
        // the same primary key, so count, newest timestamp AND temperature all hold. A signature
        // that watched only temperature declared the day settled and dropped the rainfall.
        dao.insertAll(listOf(obs(dayStart + 60_000, fetchedAt = 1_000L).copy(precipAmountMm = null)))
        val before = signature()

        dao.insertAll(listOf(obs(dayStart + 60_000, fetchedAt = 2_000L).copy(precipAmountMm = 2.0f)))
        assertNotEquals(before, signature())
    }

    @Test
    fun `observations outside the day do not affect the signature`() = runTest {
        dao.insertAll(listOf(obs(dayStart + 60_000, fetchedAt = 1_000L)))
        val before = signature()

        dao.insertAll(
            listOf(
                obs(dayStart - 1, fetchedAt = 5_000L, stationId = "KSJC"),
                obs(dayEnd, fetchedAt = 5_000L, stationId = "KPAO"),
            ),
        )
        assertEquals(before, signature())
    }

    @Test
    fun `an empty day still yields a stable signature rather than null`() = runTest {
        // COUNT(*) always returns a row, so the guard sees "0|0|0" and compares it like any other.
        assertEquals("0|0|0|0|0|0|0", signature())
    }
}
