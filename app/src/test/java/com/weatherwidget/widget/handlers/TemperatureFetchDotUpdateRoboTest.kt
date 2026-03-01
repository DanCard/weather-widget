package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemperatureFetchDotUpdateRoboTest {
    private lateinit var context: Context
    private val appWidgetId = 78

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `fetch dot callback updates when observed timestamp changes`() {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        }
        every { appWidgetManager.updateAppWidget(appWidgetId, any()) } returns Unit

        val now = LocalDateTime.now()
        val baseHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")
        val hourly = listOf(
            HourlyForecastEntity(
                dateTime = baseHour.format(format),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis(),
            ),
            HourlyForecastEntity(
                dateTime = baseHour.plusHours(1).format(format),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 71.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis(),
            ),
        )

        val firstObservedAt = now.minusMinutes(5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val secondObservedAt = now.minusMinutes(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val resolved = mutableListOf<TemperatureGraphRenderer.FetchDotDebug>()

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            observedCurrentTemp = 70.5f,
            observedCurrentTempFetchedAt = firstObservedAt,
            onFetchDotResolved = { resolved.add(it) },
        )

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            observedCurrentTemp = 70.6f,
            observedCurrentTempFetchedAt = secondObservedAt,
            onFetchDotResolved = { resolved.add(it) },
        )

        assertEquals("Should resolve fetch dot once per update", 2, resolved.size)
        assertEquals(firstObservedAt, resolved[0].observedTempFetchedAt)
        assertEquals(secondObservedAt, resolved[1].observedTempFetchedAt)
    }
}
