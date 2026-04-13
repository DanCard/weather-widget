package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class TemperatureDeltaVisibilityRoboTest {
    private lateinit var context: Context
    private val appWidgetId = 77
    private lateinit var stateManager: WidgetStateManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
    }

    private suspend fun resolveState(
        hourly: List<com.weatherwidget.data.local.HourlyForecastEntity>,
        now: LocalDateTime,
        lastObservedTemp: Float?,
        centerTime: LocalDateTime = now
    ): TemperatureWidgetState {
        val dimensions = WidgetDimensions(cols = 4, rows = 2, widthDp = 300, heightDp = 180)
        
        val result = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            currentTempHourlyForecasts = hourly,
            centerTime = centerTime,
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
            lastObservedTemp = lastObservedTemp,
            observedAt = System.currentTimeMillis(),
            dimensions = dimensions,
            stateManager = stateManager,
            repository = null,
            deferCurrentTempResolution = false
        )
        return result.state
    }

    private fun epochFor(dateTime: LocalDateTime): Long =
        com.weatherwidget.testutil.TestData.toEpoch(dateTime.toString().substring(0, 13) + ":00")

    @Test
    fun `delta badge is visible and red for positive delta`() = runBlocking {
        val now = LocalDateTime.now()
        
        val hourly = listOf(
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = epochFor(now.withMinute(0).withSecond(0).withNano(0)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            ),
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = epochFor(now.withMinute(0).withSecond(0).withNano(0).plusHours(1)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            )
        )

        val state = resolveState(hourly, now, 71.2f)
        
        assertTrue("Delta badge should be VISIBLE", state.header.isDeltaVisible)
        assertEquals("+1.2", state.header.deltaText)
        assertEquals("Should be red for positive", Color.parseColor("#FF6B35"), state.header.deltaColor)
    }

    @Test
    fun `delta badge is red for negative delta`() = runBlocking {
        val now = LocalDateTime.now()
        
        val hourly = listOf(
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = epochFor(now.withMinute(0).withSecond(0).withNano(0)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            ),
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = epochFor(now.withMinute(0).withSecond(0).withNano(0).plusHours(1)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            )
        )

        val state = resolveState(hourly, now, 69.1f)
        
        assertTrue("Delta badge should be VISIBLE", state.header.isDeltaVisible)
        assertEquals("-0.9", state.header.deltaText)
        assertEquals("Should be red for negative", Color.parseColor("#FF6B35"), state.header.deltaColor)
    }

    @Test
    fun `delta badge is hidden when delta is below threshold`() = runBlocking {
        val now = LocalDateTime.now()
        
        val hourly = listOf(
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = epochFor(now.withMinute(0).withSecond(0).withNano(0)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            ),
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = epochFor(now.withMinute(0).withSecond(0).withNano(0).plusHours(1)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            )
        )

        val state = resolveState(hourly, now, 70.05f)
        
        assertFalse("Delta badge should be hidden for negligible delta", state.header.isDeltaVisible)
    }

    @Test
    fun `delta badge is hidden when now line is not visible`() = runBlocking {
        val now = LocalDateTime.now()

        val hourly = listOf(
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = epochFor(now.withMinute(0).withSecond(0).withNano(0)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis(),
            ),
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = epochFor(now.withMinute(0).withSecond(0).withNano(0).plusHours(1)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis(),
            ),
        )

        val state = resolveState(hourly, now, 72.0f, centerTime = now.plusHours(24))

        assertFalse("Delta badge should be hidden when NOW line is not visible", state.header.isDeltaVisible)
    }
}
