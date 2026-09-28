package com.weatherwidget.data.repository

import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.RetiredProductCleanup
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Android executor of the shared [RetiredProductCleanup] against a real Room database. */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RetiredProductCleanupRunnerTest {
    private lateinit var database: WeatherDatabase

    private val now = 1_800_000_000_000L // on the five-minute grid
    private val lat = 37.417
    private val lon = -122.089
    private val farLat = 37.617

    @Before
    fun setUp() {
        database = TestDatabase.create()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun observation(
        stationId: String,
        temperature: Float,
        locationLat: Double = lat,
        timestamp: Long = now,
        api: String = WeatherSource.TOMORROW_IO.id,
    ) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = timestamp,
        temperature = temperature,
        condition = "Clear",
        locationLat = locationLat,
        locationLon = lon,
        fetchedAt = now,
        api = api,
    )

    private fun computedRow(high: Float, locationLat: Double = lat) =
        DailyHistoryEntity(now, WeatherSource.TOMORROW_IO.id, locationLat, lon, high, high - 10f, "Clear", now)

    private suspend fun runCleanup(api: String = WeatherSource.TOMORROW_IO.id) {
        RetiredProductCleanupRunner.runFor(
            api = api,
            latitude = lat,
            longitude = lon,
            observationDao = database.observationDao(),
            dailyHistoryDao = database.dailyHistoryDao(),
            appLogDao = database.appLogDao(),
        )
    }

    private suspend fun stationsAt(locationLat: Double) =
        database.observationDao().getObservationsInRange(
            startTs = now,
            endTs = now + 60_001L,
            lat = locationLat,
            lon = lon,
            apis = listOf(WeatherSource.TOMORROW_IO.id),
        ).map { it.stationId }.toSet()

    @Test
    fun `retires old products only after replacement coverage, and only at that site`() = runTest {
        database.observationDao().insertAll(
            listOf(
                observation("TOMORROW_IO_REALTIME", 74.6f),
                observation("TOMORROW_IO_RECENT_HISTORY", 77.07f),
                observation("TOMORROW_IO_MAIN", 76f),
                // Off the five-minute grid: a retired shape of the current product.
                observation("TOMORROW_IO_5M_HISTORY", 99f, timestamp = now + 60_000L),
                observation("TOMORROW_IO_REALTIME", 63f, farLat),
            ),
        )
        database.dailyHistoryDao().insertAll(listOf(computedRow(77.07f), computedRow(63f, farLat)))

        // No on-grid five-minute row yet (the off-grid one does not count): nothing is retired.
        runCleanup()
        assertEquals(
            setOf("TOMORROW_IO_REALTIME", "TOMORROW_IO_RECENT_HISTORY", "TOMORROW_IO_MAIN", "TOMORROW_IO_5M_HISTORY"),
            stationsAt(lat),
        )
        assertEquals(1, database.dailyHistoryDao().getExtremesInRange(now, now, lat, lon).size)

        database.observationDao().insertAll(listOf(observation("TOMORROW_IO_5M_HISTORY", 78.05f)))
        runCleanup()

        assertEquals(setOf("TOMORROW_IO_5M_HISTORY"), stationsAt(lat))
        assertEquals(setOf("TOMORROW_IO_REALTIME"), stationsAt(farLat))
        assertTrue(database.dailyHistoryDao().getExtremesInRange(now, now, lat, lon).isEmpty())
        assertEquals(1, database.dailyHistoryDao().getExtremesInRange(now, now, farLat, lon).size)
        val log = database.appLogDao().getLogsByTag(RetiredProductCleanup.LOG_TAG, 10).single()
        assertTrue(log.message, "api=TOMORROW_IO" in log.message && "retiredObservations=4" in log.message)
    }

    /**
     * 2026-09-28 fold, Warsaw: the old Tomorrow.io cleanup ran after every fetch and deleted the row
     * the recompute had built from the five-minute product 2 s earlier (`retiredObservations=0
     * dailyRows=1`); the unchanged-observations skip then left yesterday empty.
     */
    @Test
    fun `a site with only current-product data keeps its computed daily row`() = runTest {
        database.observationDao().insertAll(listOf(observation("TOMORROW_IO_5M_HISTORY", 68.14f)))
        database.dailyHistoryDao().insertAll(listOf(computedRow(68.14f)))

        repeat(3) { runCleanup() }

        assertEquals(68.14f, database.dailyHistoryDao().getExtremesInRange(now, now, lat, lon).single().computedHighTemp!!, 0.001f)
        assertTrue(database.appLogDao().getLogsByTag(RetiredProductCleanup.LOG_TAG, 10).isEmpty())
    }

    @Test
    fun `forecast-only rows survive a retirement`() = runTest {
        database.observationDao().insertAll(
            listOf(observation("TOMORROW_IO_5M_HISTORY", 70f), observation("TOMORROW_IO_REALTIME", 71f)),
        )
        val forecastOnly = DailyHistoryEntity(
            now - 86_400_000L, WeatherSource.TOMORROW_IO.id, lat, lon, null, null, "Clear", now,
        )
        database.dailyHistoryDao().insertAll(listOf(computedRow(71f), forecastOnly))

        runCleanup()

        val survivors = database.dailyHistoryDao().getExtremesInRange(now - 86_400_000L, now, lat, lon)
        assertEquals(1, survivors.size)
        assertNull(survivors.single().computedHighTemp)
    }

    @Test
    fun `a source with no registered migration is never touched`() = runTest {
        database.observationDao().insertAll(
            listOf(observation("OPEN_METEO_MAIN", 70f, api = WeatherSource.OPEN_METEO.id)),
        )

        runCleanup(WeatherSource.OPEN_METEO.id)

        assertEquals(
            1,
            database.observationDao().getObservationsInRange(now, now + 1L, lat, lon, apis = listOf(WeatherSource.OPEN_METEO.id)).size,
        )
    }
}
