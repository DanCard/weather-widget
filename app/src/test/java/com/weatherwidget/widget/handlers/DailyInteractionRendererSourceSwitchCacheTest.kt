package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.PrecipProbabilityCalculator
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf

/**
 * The 2026-10-31 header 40%: cycling sources inside [WidgetInteractionCache]'s TTL reused the first
 * source's hourly rows, so the header found none for the source on screen and fell back to today's
 * daily rain chance. Tomorrow.io's next 6 h were all 0 %; its daily 40 % came from 04:00 the next day.
 */
@Category(LongDuration::class)
class DailyInteractionRendererSourceSwitchCacheTest : RobolectricTest() {
    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager

    private val widgetId = 63
    private val lat = TestData.LAT
    private val lon = TestData.LON
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 10, 31)
    private val now = LocalDateTime.of(2026, 10, 31, 8, 52)
    private val window = NavigationUtils.DailyLoadWindow(historyDays = 2, forecastDays = 9)

    @Before
    fun setUp() {
        WidgetInteractionCache.clear()
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        stateManager = WidgetStateManager(context)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.OPEN_WEATHER_MAP, WeatherSource.TOMORROW_IO))
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(context, WeatherWidgetProvider::class.java) }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(widgetId, info)
    }

    @After
    fun tearDown() {
        WidgetInteractionCache.clear()
        db.close()
    }

    private suspend fun storeHours(source: WeatherSource, precip: Int) {
        db.hourlyForecastDao().insertAll(
            (8..14).map { hour ->
                TestData.hourly(
                    dateTime = "2026-10-31T%02d:00".format(hour),
                    source = source.id,
                    precipProbability = precip,
                    lat = lat,
                    lon = lon,
                )
            },
        )
    }

    private suspend fun load() = DailyInteractionRenderer.loadData(
        context = context,
        database = db,
        lat = lat,
        lon = lon,
        today = today,
        range = DailyInteractionRenderer.rangeFor(today, window),
        timeBounds = DailyInteractionRenderer.timeBounds(now, zone),
    )

    @Test
    fun `a source switch inside the TTL reads the new source's hours for the header`() = runTest {
        storeHours(WeatherSource.OPEN_WEATHER_MAP, precip = 10)
        storeHours(WeatherSource.TOMORROW_IO, precip = 0)

        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_WEATHER_MAP)
        load()

        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.TOMORROW_IO)
        val hourly = checkNotNull(load().hourlyForecasts)

        assertEquals(
            "the header reads the hours, not the daily fallback",
            0,
            PrecipProbabilityCalculator.getNext6HourPrecipProbability(
                hourly.map { it.toHourlyForecast() },
                WeatherSource.TOMORROW_IO.id,
                WeatherSource.GENERIC_GAP.id,
                fallbackDailyProbability = 40,
                referenceTime = now,
            ),
        )
    }

    @Test
    fun `switching back inside the TTL reuses the first load`() = runTest {
        storeHours(WeatherSource.OPEN_WEATHER_MAP, precip = 10)
        storeHours(WeatherSource.TOMORROW_IO, precip = 0)

        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_WEATHER_MAP)
        val first = load()
        assertTrue("the first load holds its source's hours", first.hourlyForecasts!!.any { it.source == WeatherSource.OPEN_WEATHER_MAP.id })
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.TOMORROW_IO)
        load()
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_WEATHER_MAP)

        assertSame(first.hourlyForecasts, load().hourlyForecasts)
    }
}
