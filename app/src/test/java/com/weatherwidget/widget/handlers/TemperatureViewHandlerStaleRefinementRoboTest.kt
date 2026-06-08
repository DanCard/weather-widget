package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureViewHandlerStaleRefinementRoboTest {
    private lateinit var context: Context
    private val appWidgetId = 4201
    private val zoneId = ZoneId.systemDefault()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
        TemperatureViewHandler.cancelCurrentTempRefinement(appWidgetId)
    }

    @Test
    fun `stale deferred refinement does not overwrite daily render after mode switch`() = runBlocking {
        val now = LocalDateTime.of(2026, 6, 8, 11, 30)
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 260)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
        }
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns options
        every { appWidgetManager.updateAppWidget(appWidgetId, any<android.widget.RemoteViews>()) } answers { }
        var partialUpdated = false
        every { appWidgetManager.partiallyUpdateAppWidget(appWidgetId, any<android.widget.RemoteViews>()) } answers {
            partialUpdated = true
        }

        val hourlyForecasts = buildHourlyForecasts(now)

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourlyForecasts,
            currentTempHourlyForecasts = hourlyForecasts,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            lastObservedTemp = 69.3f,
            observedAt = now.minusMinutes(10).atZone(zoneId).toInstant().toEpochMilli(),
            deferCurrentTempResolution = true,
        )

        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        TemperatureViewHandler.cancelCurrentTempRefinement(appWidgetId)

        Thread.sleep(300L)

        assertFalse(partialUpdated)
    }

    private fun buildHourlyForecasts(now: LocalDateTime): List<HourlyForecastEntity> {
        return (-12..12).map { offset ->
            val time = now.plusHours(offset.toLong())
            HourlyForecastEntity(
                dateTime = time.atZone(zoneId).toInstant().toEpochMilli(),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 68f + offset * 0.1f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = null,
                fetchedAt = now.minusMinutes(20).atZone(zoneId).toInstant().toEpochMilli(),
            )
        }
    }
}
