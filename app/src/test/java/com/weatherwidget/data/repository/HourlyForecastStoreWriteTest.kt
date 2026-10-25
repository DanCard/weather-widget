package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.LocationMatch
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

/**
 * [HourlyForecastStore] + Room through the `:shared` HourlyForecastWrite rule
 * (`plans/261024-shared-forecast-persist-rules.md`): a field the reply lacks keeps the stored value,
 * and an hour a fetch repeats unchanged is stamped as confirmed by it — it used to keep the first
 * fetch's time, so Google's history refill read a confirmed hour as stale.
 */
@Category(LongDuration::class)
class HourlyForecastStoreWriteTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private val h = 3_600_000L
    private val lat = 37.4168
    private val lon = -122.0890
    private val source = WeatherSource.GOOGLE_WEATHER.id
    private val now = (System.currentTimeMillis() / h) * h + 20 * 60_000L
    private var clock = now

    private fun store() = HourlyForecastStore(
        hourlyForecastDao = db.hourlyForecastDao(),
        hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
        observationDao = db.observationDao(),
        widgetStateManager = WidgetStateManager(ApplicationProvider.getApplicationContext<Context>()),
        clock = { clock },
        appLogDao = db.appLogDao(),
        dailyHistoryDao = db.dailyHistoryDao(),
    )

    @Before
    fun setUp() {
        db = TestDatabase.create()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun hours(cloud: Int?) = (1..3).map {
        HourlyForecast(dateTime = now - now % h + it * h, temperature = 60f, condition = "Clear", cloudCover = cloud, source = source)
    }

    private suspend fun liveRows() = db.hourlyForecastDao().getHourlyForecastsBySource(
        now, now + 4 * h, LocationMatch.quantize(lat), LocationMatch.quantize(lon), source,
    )

    @Test
    fun `an unchanged hour is stamped by the fetch that confirms it`() = runTest {
        store().saveHourlyEntitiesFromShared(hours(cloud = 30), lat, lon, source)
        clock = now + h / 2
        store().saveHourlyEntitiesFromShared(hours(cloud = 30), lat, lon, source)

        assertEquals(List(3) { now + h / 2 }, liveRows().map { it.fetchedAt })
    }

    @Test
    fun `a reply without cloud keeps the stored cloud`() = runTest {
        store().saveHourlyEntitiesFromShared(hours(cloud = 30), lat, lon, source)
        clock = now + h / 2
        store().saveHourlyEntitiesFromShared(hours(cloud = null), lat, lon, source)

        assertEquals(listOf(30, 30, 30), liveRows().map { it.cloudCover })
    }
}
