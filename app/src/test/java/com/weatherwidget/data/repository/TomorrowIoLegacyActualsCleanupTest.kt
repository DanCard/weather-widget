package com.weatherwidget.data.repository

import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class TomorrowIoLegacyActualsCleanupTest {
    private lateinit var database: WeatherDatabase

    @Before
    fun setUp() {
        database = TestDatabase.create()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `cleanup waits for five minute coverage then retires only the covered site`() = runTest {
        val now = 1_800_000_000_000L
        val lat = 37.417
        val lon = -122.089
        val farLat = 37.617
        fun observation(
            stationId: String,
            temperature: Float,
            locationLat: Double = lat,
            timestamp: Long = now,
        ) = ObservationEntity(
            stationId = stationId,
            stationName = stationId,
            timestamp = timestamp,
            temperature = temperature,
            condition = "Clear",
            locationLat = locationLat,
            locationLon = lon,
            fetchedAt = now,
            api = WeatherSource.TOMORROW_IO.id,
        )
        database.observationDao().insertAll(
            listOf(
                observation("TOMORROW_IO_REALTIME", 74.6f),
                observation("TOMORROW_IO_RECENT_HISTORY", 77.07f),
                observation("TOMORROW_IO_5M_HISTORY", 99f, timestamp = now + 60_000L),
                observation("TOMORROW_IO_REALTIME", 63f, farLat),
            ),
        )
        database.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(now, WeatherSource.TOMORROW_IO.id, lat, lon, 77.07f, 74.6f, "Clear", now),
                DailyHistoryEntity(now, WeatherSource.TOMORROW_IO.id, farLat, lon, 63f, 60f, "Clear", now),
            ),
        )

        runCleanup(lat, lon)
        assertEquals(
            3,
            database.observationDao().getObservationsInRange(
                startTs = now,
                endTs = now + 60_001L,
                lat = lat,
                lon = lon,
                apis = listOf(WeatherSource.TOMORROW_IO.id),
            ).size,
        )

        database.observationDao().insertAll(
            listOf(observation("TOMORROW_IO_5M_HISTORY", 78.16f)),
        )
        database.observationDao().insertAll(
            listOf(observation("TOMORROW_IO_5M_HISTORY", 78.05f)),
        )
        runCleanup(lat, lon)

        val siteRows = observationsAt(now, lat, lon)
        assertEquals(listOf("TOMORROW_IO_5M_HISTORY"), siteRows.map { it.stationId })
        assertEquals(78.05f, siteRows.single().temperature, 0.001f)
        assertEquals(
            1,
            database.observationDao().countTomorrowIoFiveMinuteObservationsAtSite(lat, lon),
        )
        assertEquals(
            listOf("TOMORROW_IO_REALTIME"),
            observationsAt(now, farLat, lon).map { it.stationId },
        )
        assertTrue(database.dailyHistoryDao().getExtremesInRange(now, now, lat, lon).isEmpty())
        assertEquals(1, database.dailyHistoryDao().getExtremesInRange(now, now, farLat, lon).size)
        assertEquals(1, database.appLogDao().getLogsByTag("TMRW_5M_CLEANUP", 10).size)
    }

    private suspend fun runCleanup(lat: Double, lon: Double) {
        TomorrowIoLegacyActualsCleanup.retireConflictingProductsIfCovered(
            latitude = lat,
            longitude = lon,
            observationDao = database.observationDao(),
            dailyHistoryDao = database.dailyHistoryDao(),
            appLogDao = database.appLogDao(),
        )
    }

    private suspend fun observationsAt(now: Long, lat: Double, lon: Double) =
        database.observationDao().getObservationsInRange(
            startTs = now,
            endTs = now + 1L,
            lat = lat,
            lon = lon,
            apis = listOf(WeatherSource.TOMORROW_IO.id),
        )
}
