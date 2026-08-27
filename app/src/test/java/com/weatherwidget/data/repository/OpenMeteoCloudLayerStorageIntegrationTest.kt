package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class OpenMeteoCloudLayerStorageIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var store: HourlyForecastStore

    private val lat = 37.417
    private val lon = -122.089

    @Before
    fun setUp() {
        db = TestDatabase.create()
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = HourlyForecastStore(
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            observationDao = db.observationDao(),
            widgetStateManager = WidgetStateManager(context),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `four Open-Meteo cloud fields survive live history and nullable merge`() = runTest {
        val hour = System.currentTimeMillis() + 3_600_000L
        val complete = HourlyForecast(
            dateTime = hour,
            temperature = 70f,
            condition = "Cloudy",
            cloudCover = 100,
            cloudCoverLow = 4,
            cloudCoverMid = 72,
            cloudCoverHigh = 96,
            source = WeatherSource.OPEN_METEO.id,
        )

        store.saveHourlyEntitiesFromShared(listOf(complete), lat, lon, WeatherSource.OPEN_METEO.id)
        // A partial refresh must not erase layer values already held for this hour.
        store.saveHourlyEntitiesFromShared(
            listOf(complete.copy(cloudCover = 90, cloudCoverLow = null, cloudCoverMid = null, cloudCoverHigh = null)),
            lat,
            lon,
            WeatherSource.OPEN_METEO.id,
        )

        val live = db.hourlyForecastDao().getHourlyForecastsBySource(
            hour,
            hour,
            lat,
            lon,
            WeatherSource.OPEN_METEO.id,
        ).single()
        assertEquals(listOf(90, 4, 72, 96), listOf(live.cloudCover, live.cloudCoverLow, live.cloudCoverMid, live.cloudCoverHigh))

        val history = db.hourlyForecastHistoryDao().getHistoryInRangeAllSnapshots(
            hour,
            hour + 1,
            lat,
            lon,
        ).single()
        assertEquals(
            listOf(90, 4, 72, 96),
            listOf(history.cloudCover, history.cloudCoverLow, history.cloudCoverMid, history.cloudCoverHigh),
        )
    }
}
