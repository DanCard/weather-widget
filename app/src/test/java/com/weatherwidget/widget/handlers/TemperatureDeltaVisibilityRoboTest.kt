package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemperatureDeltaVisibilityRoboTest {
    private lateinit var context: Context
    private val appWidgetId = 77

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
    }

    @Test
    fun `delta badge is visible and orange for positive delta`() = runBlocking {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        }
        
        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit

        val now = LocalDateTime.now()
        val todayStr = now.toLocalDate().toString()
        
        // 1. Setup hourly forecast at 70 degrees
        val hourly = listOf(
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = com.weatherwidget.testutil.TestData.toEpoch(String.format("%sT%02d:00", todayStr, now.hour)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            )
        )

        // 2. Resolve with observed temp at 71.2 (+1.2 delta)
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
            observedCurrentTemp = 71.2f,
            observedAt = System.currentTimeMillis()
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root)
        
        val deltaBadge = applied.findViewById<TextView>(R.id.current_temp_delta)
        
        assertEquals("Delta badge should be VISIBLE", View.VISIBLE, deltaBadge.visibility)
        assertEquals("+1.2", deltaBadge.text.toString())
        assertEquals("Should be orange for positive", Color.parseColor("#FF6B35"), deltaBadge.currentTextColor)
    }

    @Test
    fun `delta badge is blue for negative delta`() = runBlocking {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        }
        
        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit

        val now = LocalDateTime.now()
        val todayStr = now.toLocalDate().toString()
        
        val hourly = listOf(
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = String.format("%sT%02d:00", todayStr, now.hour),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            )
        )

        // Resolve with observed temp at 69.1 (-0.9 delta)
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
            observedCurrentTemp = 69.1f,
            observedAt = System.currentTimeMillis()
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root)
        
        val deltaBadge = applied.findViewById<TextView>(R.id.current_temp_delta)
        
        assertEquals("Delta badge should be VISIBLE", View.VISIBLE, deltaBadge.visibility)
        assertEquals("-0.9", deltaBadge.text.toString())
        assertEquals("Should be blue for negative", Color.parseColor("#5AC8FA"), deltaBadge.currentTextColor)
    }

    @Test
    fun `delta badge is hidden when delta is below threshold`() = runBlocking {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        }
        
        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit

        val now = LocalDateTime.now()
        val todayStr = now.toLocalDate().toString()
        
        val hourly = listOf(
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = String.format("%sT%02d:00", todayStr, now.hour),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            )
        )

        // Resolve with observed temp at 70.05 (+0.05 delta, below 0.1 threshold)
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
            observedCurrentTemp = 70.05f,
            observedAt = System.currentTimeMillis()
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root)
        
        val deltaBadge = applied.findViewById<TextView>(R.id.current_temp_delta)
        assertEquals("Delta badge should be GONE for negligible delta", View.GONE, deltaBadge.visibility)
    }

    @Test
    fun `delta badge is hidden when now line is not visible`() = runBlocking {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        }

        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit

        val now = LocalDateTime.now()
        val todayStr = now.toLocalDate().toString()
        val currentHourKey = String.format("%sT%02d:00", todayStr, now.hour)

        val hourly = listOf(
            com.weatherwidget.data.local.HourlyForecastEntity(
                dateTime = currentHourKey,
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis(),
            ),
        )

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            centerTime = now.plusHours(24), // Graph window excludes current hour -> no NOW line
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
            observedCurrentTemp = 72.0f,
            observedAt = System.currentTimeMillis(),
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root)
        val deltaBadge = applied.findViewById<TextView>(R.id.current_temp_delta)
        assertEquals("Delta badge should be GONE when NOW line is not visible", View.GONE, deltaBadge.visibility)
    }
}
