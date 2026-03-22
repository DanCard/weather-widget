package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Device-side regression for cloud-cover touch routing.
 *
 * This exercises real RemoteViews click wiring on Android and verifies that tapping the bottom
 * footer switches the widget into TEMPERATURE mode instead of cycling zoom.
 */
@RunWith(AndroidJUnit4::class)
class CloudCoverTouchRoutingInstrumentedTest : IsolatedIntegrationTest("cloud_cover_touch_routing") {

    private lateinit var stateManager: WidgetStateManager
    private val appWidgetId = 914

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
        insertHourlyRows()
    }

    @After
    override fun cleanup() {
        stateManager.clearWidgetState(appWidgetId)
        super.cleanup()
    }

    @Test
    fun bottomFooterTap_switchesFromCloudCoverToTemperature_withoutZooming() = runBlocking {
        stateManager.setViewMode(appWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
        stateManager.setHourlyOffset(appWidgetId, 0)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.SILURIAN)

        val views = buildBottomTapViews()
        val applied = applyViews(views)
        val bottomZone = applied.findViewById<View>(R.id.graph_bottom_zone)

        assertNotNull("Expected graph bottom touch zone to exist", bottomZone)
        assertEquals(View.VISIBLE, bottomZone.visibility)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            bottomZone.performClick()
        }
        instrumentation.waitForIdleSync()

        waitForViewMode(ViewMode.TEMPERATURE)

        assertEquals(
            "Bottom footer tap should switch to temperature mode",
            ViewMode.TEMPERATURE,
            stateManager.getViewMode(appWidgetId),
        )
        assertEquals(
            "Bottom footer tap should not change zoom level",
            ZoomLevel.WIDE,
            stateManager.getZoomLevel(appWidgetId),
        )
        assertEquals(
            "Bottom footer tap should preserve the current hourly offset",
            0,
            stateManager.getHourlyOffset(appWidgetId),
        )
    }

    private fun buildBottomTapViews(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val goTempIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetIntentRouter.ACTION_SET_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW, ViewMode.TEMPERATURE.name)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * 100 + 900,
            goTempIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        views.setViewVisibility(R.id.graph_bottom_zone, View.VISIBLE)
        views.setOnClickPendingIntent(R.id.graph_bottom_zone, pendingIntent)
        return views
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

    private fun waitForViewMode(expected: ViewMode) {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (stateManager.getViewMode(appWidgetId) == expected) {
                return
            }
            Thread.sleep(50)
        }
        assertEquals(expected, stateManager.getViewMode(appWidgetId))
    }

    private fun insertHourlyRows() = runBlocking {
        db.hourlyForecastDao().insertAll(sampleHourlyForecasts(LocalDateTime.now()))
    }

    private fun sampleHourlyForecasts(now: LocalDateTime): List<HourlyForecastEntity> {
        val start = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusHours(8)
        val fetchedAt = System.currentTimeMillis()
        return (0..24).flatMap { index ->
            val time = start.plusHours(index.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            listOf(
                HourlyForecastEntity(
                    dateTime = time,
                    locationLat = 37.0,
                    locationLon = -122.0,
                    temperature = 58f + index,
                    condition = if (index % 3 == 0) "Cloudy" else "Clear",
                    source = WeatherSource.SILURIAN.id,
                    precipProbability = if (index % 4 == 0) 20 else 0,
                    cloudCover = (25 + index * 2).coerceAtMost(100),
                    fetchedAt = fetchedAt,
                ),
                HourlyForecastEntity(
                    dateTime = time,
                    locationLat = 37.0,
                    locationLon = -122.0,
                    temperature = 57f + index,
                    condition = if (index % 2 == 0) "Partly Cloudy" else "Clear",
                    source = WeatherSource.NWS.id,
                    precipProbability = if (index % 5 == 0) 15 else 0,
                    cloudCover = (35 + index).coerceAtMost(100),
                    fetchedAt = fetchedAt,
                ),
            )
        }
    }
}
