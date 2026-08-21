package com.weatherwidget.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class OpenMeteoLegacyActualsCleanupTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setup() {
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `cleanup removes model actual rows once and leaves other sources alone`() = runTest {
        val meteo = observation("OPEN_METEO_MAIN", "OPEN_METEO")
        val nws = observation("KNUQ", "NWS")
        db.observationDao().insertAll(listOf(meteo, nws))
        db.dailyHistoryDao().insertAll(listOf(daily("OPEN_METEO"), daily("NWS")))

        OpenMeteoLegacyActualsCleanup.runIfNeeded(
            context,
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
        )

        assertEquals(listOf("KNUQ"), db.observationDao().getRecentObservations(0L).map { it.stationId })
        assertEquals(
            listOf("NWS"),
            db.dailyHistoryDao().getExtremesInRange(1L, 1L, 37.42, -122.08).map { it.source },
        )

        db.observationDao().insertAll(listOf(meteo.copy(timestamp = 2L)))
        OpenMeteoLegacyActualsCleanup.runIfNeeded(
            context,
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
        )
        assertEquals(1, db.observationDao().getRecentObservations(0L).count { it.api == "OPEN_METEO" })
    }

    private fun observation(stationId: String, api: String) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = 1L,
        temperature = 65f,
        condition = "Clear",
        locationLat = 37.42,
        locationLon = -122.08,
        api = api,
    )

    private fun daily(source: String) = DailyHistoryEntity(
        date = 1L,
        source = source,
        locationLat = 37.42,
        locationLon = -122.08,
        computedHighTemp = 70f,
        computedLowTemp = 60f,
        condition = "Clear",
        updatedAt = 1L,
    )
}
