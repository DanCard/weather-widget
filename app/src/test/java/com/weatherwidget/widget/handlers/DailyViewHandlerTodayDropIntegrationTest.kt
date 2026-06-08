package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DailyExtreme
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetConstants
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyViewHandlerTodayDropIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(RainAnalyzer)
        mockkObject(DailyForecastGraphRenderer)
    }

    @After
    fun teardown() {
        unmockkObject(RainAnalyzer)
        unmockkObject(DailyForecastGraphRenderer)
    }

    private fun epoch(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun extreme(date: LocalDate, high: Float, low: Float) = DailyExtreme(
        date = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        source = WeatherSource.NWS.id,
        locationLat = 0.0,
        locationLon = 0.0,
        highTemp = high,
        lowTemp = low,
        condition = "Clear",
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun `updateWidget today bar represents current temp even when below daily peak`() = runBlocking {
        val now = LocalDateTime.of(2026, 3, 24, 16, 0) // 4 PM
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Forecast high is 80
        val weatherList = listOf(
            createWeather(todayStr, highTemp = 80f, lowTemp = 60f)
        )

        // Actual peak so far was 82 at 2 PM, but now it's 75 at 4 PM
        val currentTemps = listOf(
            ObservationEntity(
                stationId = "NWS_BLEND",
                stationName = "Test Station",
                timestamp = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                temperature = 75f,
                condition = "Clear",
                locationLat = 0.0,
                locationLon = 0.0,
                fetchedAt = 1L,
                api = "NWS",
            )
        )
        val dailyActualsBySource = mapOf(
            "NWS" to mapOf(
                today to extreme(today, 82f, 60f)
            )
        )

        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(101)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS))

        val appWidgetManager = mockk<AppWidgetManager>()
        // 200x200 gives 2+ rows -> graph mode
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200)
        }
        every { appWidgetManager.getAppWidgetOptions(101) } returns options
        every { appWidgetManager.updateAppWidget(101, any()) } just runs

        // Capture the DayData passed to the renderer
        val daysSlot = slot<List<DailyForecastGraphRenderer.DayData>>()
        every {
            DailyForecastGraphRenderer.renderGraph(
                any(), capture(daysSlot), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 101,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
                currentTemps = currentTemps,
                dailyActualsBySource = dailyActualsBySource,
            ),
            observationData = ObservationData(
                lastObservedTemp = 75f,
                observedAt = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val todayData = daysSlot.captured.first { it.isToday }
        
        // 1. Mercury level (solidLineHigh) should be the CURRENT temp (75), not the peak (82)
        assertEquals("Thermometer high should be current temp", 75f, todayData.solidLineHigh!!, 0.1f)
        
        // 2. Ghost line high should still be 82 (for the ghost bar)
        assertEquals("True actual high should be preserved", 82f, todayData.ghostLineHigh!!, 0.1f)
        
        // 3. Forecast high should still be 80
        assertEquals("Forecast high should be preserved", 80f, todayData.dashedLineHigh!!, 0.1f)
    }

    @Test
    fun `updateWidget today text label shows peak even when mercury is lower`() = runBlocking {
        val now = LocalDateTime.of(2026, 3, 24, 7, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherList = listOf(
            createWeather(todayStr, highTemp = 80f, lowTemp = 60f)
        )
        val currentTemps = listOf(
            ObservationEntity(
                stationId = "NWS_BLEND",
                stationName = "Test Station",
                timestamp = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                temperature = 75f,
                condition = "Clear",
                locationLat = 0.0,
                locationLon = 0.0,
                fetchedAt = 1L,
                api = "NWS",
            )
        )
        val dailyActualsBySource = mapOf(
            "NWS" to mapOf(
                today to extreme(today, 82f, 60f)
            )
        )

        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(102)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS))

        val appWidgetManager = mockk<AppWidgetManager>()
        // Text mode (low height)
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }
        every { appWidgetManager.getAppWidgetOptions(102) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(102, capture(viewsSlot)) } just runs

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 102,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
                currentTemps = currentTemps,
                dailyActualsBySource = dailyActualsBySource,
            ),
            observationData = ObservationData(
                lastObservedTemp = 75f,
                observedAt = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        
        // Day 2 is usually Today in 3-column text mode (Yesterday, Today, Tomorrow)
        val todayHighText = applied.findViewById<TextView>(R.id.day2_high)?.text?.toString()
        
        // The label should show the maximum of (Current: 75, Forecast: 80, Actual: 82) -> 82
        assertEquals("Today text label should show the daily peak", "82°", todayHighText)
    }

    @Test
    fun `updateWidget handles NWS evening drop by using snapshots`() = runBlocking {
        val now = LocalDateTime.of(2026, 3, 24, 20, 0) // 8 PM
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Latest NWS batch (weatherList) is missing the highTemp (evening drop)
        val weatherList = listOf(
            createWeather(todayStr, highTemp = null, lowTemp = 60f)
        )

        // But we have an older complete snapshot from earlier in the day
        val completeSnapshot = createWeather(todayStr, highTemp = 85f, lowTemp = 60f)
            .copy(fetchedAt = now.minusHours(10).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        
        val forecastSnapshots = mapOf(
            today to listOf(completeSnapshot)
        )

        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(103)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS))

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200)
        }
        every { appWidgetManager.getAppWidgetOptions(103) } returns options
        every { appWidgetManager.updateAppWidget(103, any()) } just runs

        val daysSlot = slot<List<DailyForecastGraphRenderer.DayData>>()
        every {
            DailyForecastGraphRenderer.renderGraph(
                any(), capture(daysSlot), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 103,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = forecastSnapshots,
                hourlyForecasts = emptyList(),
            ),
            observationData = ObservationData(
                lastObservedTemp = 70f,
                observedAt = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val todayData = daysSlot.captured.first { it.isToday }
        
        // Should have picked up 85f from the snapshot instead of falling back to hourly (which would be lower)
        assertEquals("Should fall back to complete snapshot high", 85f, todayData.dashedLineHigh!!, 0.1f)
    }

    private fun createWeather(
        date: String,
        precipProbability: Int? = 0,
        highTemp: Float? = 70f,
        lowTemp: Float? = 55f,
    ): ForecastEntity {
        return ForecastEntity(
            targetDate = dateEpoch(date),
            forecastDate = dateEpoch(date),
            locationLat = 37.7749,
            locationLon = -122.4194,
            locationName = "Test",
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = precipProbability,
            fetchedAt = 1L,
        )
    }
}
