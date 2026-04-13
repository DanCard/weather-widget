package com.weatherwidget.widget.handlers

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.WeatherObservationsActivity
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class WeatherObservationsShortcutTest {
    private lateinit var context: Context
    private lateinit var app: Application
    private val appWidgetId = 315

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        app = RuntimeEnvironment.getApplication()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `TemperatureViewHandler thermometer icon starts WeatherObservationsActivity directly`() = runBlocking {
        val views = renderTemperatureWidget()
        val applied = applyViews(views)
        
        val stationsIcon = applied.findViewById<View>(R.id.current_stations_icon)
        assertNotNull("Thermometer icon should be present", stationsIcon)
        assertEquals("Thermometer icon should be VISIBLE", View.VISIBLE, stationsIcon.visibility)

        stationsIcon.performClick()

        val nextStartedActivity = shadowOf(app).nextStartedActivity
        assertNotNull("Clicking thermometer icon should start an activity", nextStartedActivity)
        assertEquals(
            WeatherObservationsActivity::class.java.name,
            nextStartedActivity.component?.className
        )
        assertEquals(
            appWidgetId,
            nextStartedActivity.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        )
    }

    @Test
    fun `TemperatureViewHandler inline thermometer touch zone starts WeatherObservationsActivity directly`() = runBlocking {
        val views = renderTemperatureWidget()
        val applied = applyViews(views)
        
        val stationsIcon = applied.findViewById<View>(R.id.current_stations_touch_zone_inline)
        assertNotNull("Inline touch zone should be present", stationsIcon)
        // Note: The visibility of this one depends on positionCenterIcons.
        // We will just perform click anyway as it's wired.

        stationsIcon.performClick()

        val nextStartedActivity = shadowOf(app).nextStartedActivity
        assertNotNull("Clicking inline touch zone should start an activity", nextStartedActivity)
        assertEquals(
            WeatherObservationsActivity::class.java.name,
            nextStartedActivity.component?.className
        )
        assertEquals(
            appWidgetId,
            nextStartedActivity.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        )
    }

    private suspend fun renderTemperatureWidget(): RemoteViews {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns graphOptions()

        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit

        val now = LocalDateTime.now()
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            currentTempHourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            displaySource = WeatherSource.NWS,
        )
        return viewsSlot.captured
    }

    private fun applyViews(views: RemoteViews): View {
        val root = FrameLayout(context)
        return views.apply(context, root)
    }

    private fun graphOptions(): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150)
    }

    private fun sampleHourlyForecasts(now: LocalDateTime): List<HourlyForecastEntity> {
        val start = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusHours(8)
        return (0..24).map { index ->
            val time = start.plusHours(index.toLong())
            HourlyForecastEntity(
                dateTime = time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 60f + index,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                fetchedAt = System.currentTimeMillis(),
            )
        }
    }
}