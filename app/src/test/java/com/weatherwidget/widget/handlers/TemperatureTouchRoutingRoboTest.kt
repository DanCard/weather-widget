package com.weatherwidget.widget.handlers

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemperatureTouchRoutingRoboTest {
    private lateinit var context: Context
    private lateinit var app: Application
    private val appWidgetId = 314

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        app = RuntimeEnvironment.getApplication()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `wide hourly graph routes body taps to zoom and bottom row taps to cloud cover`() = runBlocking {
        val views = renderTemperatureWidget(
            options = graphOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
                it.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
                it.setHourlyOffset(appWidgetId, 0)
            },
        )

        val applied = applyViews(views)
        val bodyZone = applied.findViewById<View>(R.id.graph_hour_zone_0)
        val hourZones = applied.findViewById<View>(R.id.graph_hour_zones)
        val bottomZone = applied.findViewById<View>(R.id.graph_bottom_zone)
        val graphBodyTapZone = applied.findViewById<View>(R.id.graph_body_tap_zone)

        assertEquals(View.VISIBLE, bodyZone.visibility)
        assertEquals(View.VISIBLE, bottomZone.visibility)
        assertEquals(View.GONE, graphBodyTapZone.visibility)
        assertTrue("Wide hour zones should end above the bottom row", hourZones.bottom <= bottomZone.top)

        val shadowApp = shadowOf(app)
        val beforeBodyTap = shadowApp.broadcastIntents.size
        bodyZone.performClick()

        val zoomIntent = shadowApp.broadcastIntents.drop(beforeBodyTap).lastOrNull()
        assertNotNull("Expected zoom tap to send a broadcast", zoomIntent)
        assertEquals(WidgetIntentRouter.ACTION_CYCLE_ZOOM, zoomIntent!!.action)
        assertEquals(appWidgetId, zoomIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

        val beforeBottomTap = shadowApp.broadcastIntents.size
        bottomZone.performClick()

        val cloudIntent = shadowApp.broadcastIntents.drop(beforeBottomTap).lastOrNull()
        assertNotNull("Expected bottom row tap to send a broadcast", cloudIntent)
        assertEquals(WidgetIntentRouter.ACTION_SET_VIEW, cloudIntent!!.action)
        assertEquals(
            ViewMode.CLOUD_COVER.name,
            cloudIntent.getStringExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW),
        )
        assertEquals(appWidgetId, cloudIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `narrow hourly graph routes hour zone tap to zoom out to specific center while bottom row still switches to cloud cover`() = runBlocking {
        val views = renderTemperatureWidget(
            options = graphOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
                it.setZoomLevel(appWidgetId, ZoomLevel.NARROW)
            },
        )

        val applied = applyViews(views)
        val graphBodyTapZone = applied.findViewById<View>(R.id.graph_body_tap_zone)
        val hourZones = applied.findViewById<View>(R.id.graph_hour_zones)
        val bottomZone = applied.findViewById<View>(R.id.graph_bottom_zone)
        val hourZone0 = applied.findViewById<View>(R.id.graph_hour_zone_0)

        assertEquals(View.GONE, graphBodyTapZone.visibility)
        assertEquals(View.VISIBLE, hourZones.visibility)
        assertEquals(View.VISIBLE, bottomZone.visibility)
        assertTrue("Narrow hour zones should end above the bottom row", hourZones.bottom <= bottomZone.top)

        val shadowApp = shadowOf(app)
        val beforeBodyTap = shadowApp.broadcastIntents.size
        hourZone0.performClick()

        val zoomIntent = shadowApp.broadcastIntents.drop(beforeBodyTap).lastOrNull()
        assertNotNull("Expected narrow hour zone tap to send a zoom-out broadcast", zoomIntent)
        assertEquals(WidgetIntentRouter.ACTION_CYCLE_ZOOM, zoomIntent!!.action)
        assertTrue(zoomIntent.hasExtra(com.weatherwidget.widget.WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET))

        val beforeBottomTap = shadowApp.broadcastIntents.size
        bottomZone.performClick()

        val cloudIntent = shadowApp.broadcastIntents.drop(beforeBottomTap).lastOrNull()
        assertNotNull("Expected bottom row tap to switch to cloud cover", cloudIntent)
        assertEquals(WidgetIntentRouter.ACTION_SET_VIEW, cloudIntent!!.action)
        assertEquals(
            ViewMode.CLOUD_COVER.name,
            cloudIntent.getStringExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW),
        )
    }

    @Test
    fun `text mode hides graph touch overlays`() = runBlocking {
        val views = renderTemperatureWidget(
            options = textOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
                it.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
            },
        )

        val applied = applyViews(views)

        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_hour_zones).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_body_tap_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_bottom_zone).visibility)
    }

    private suspend fun renderTemperatureWidget(
        options: Bundle,
        configureState: (WidgetStateManager) -> Unit,
    ): RemoteViews {
        val stateManager = WidgetStateManager(context)
        configureState(stateManager)

        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns options

        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit

        val now = LocalDateTime.now()
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 20,
        )

        return viewsSlot.captured
    }

    private fun applyViews(views: RemoteViews): View {
        val root = FrameLayout(context)
        val applied = views.apply(context, root as ViewGroup)
        val widthSpec = MeasureSpec.makeMeasureSpec(600, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY)
        applied.measure(widthSpec, heightSpec)
        applied.layout(0, 0, applied.measuredWidth, applied.measuredHeight)
        return applied
    }

    private fun graphOptions(): Bundle =
        Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150)
        }

    private fun textOptions(): Bundle =
        Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
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
                condition = if (index % 3 == 0) "Cloudy" else "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = if (index % 4 == 0) 20 else 0,
                fetchedAt = System.currentTimeMillis(),
            )
        }
    }
}
