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
import com.weatherwidget.widget.WidgetActions.*
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

@RunWith(AndroidJUnit4::class)
class PrecipTouchRoutingInstrumentedTest : IsolatedIntegrationTest("precip_touch_routing") {

    private lateinit var stateManager: WidgetStateManager
    private val appWidgetId = 821

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)
        insertHourlyRows()
    }

    @After
    override fun cleanup() {
        stateManager.clearWidgetState(appWidgetId)
        super.cleanup()
    }

    @Test
    fun bodyZoneTap_zoomsPrecipitationGraph_withoutChangingViewMode() = runBlocking {
        stateManager.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
        stateManager.setHourlyOffset(appWidgetId, 0)

        val views = buildBodyZoneViews(ZoomLevel.WIDE)
        val applied = applyViews(views)
        val bodyZone = applied.findViewById<View>(R.id.graph_hour_zone_6)

        assertNotNull("Expected graph_hour_zone_6 to exist", bodyZone)
        assertEquals(View.VISIBLE, bodyZone!!.visibility)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { bodyZone.performClick() }
        instrumentation.waitForIdleSync()

        waitForZoomLevel(ZoomLevel.NARROW)

        assertEquals(
            "Body zone tap should not change view mode",
            ViewMode.PRECIPITATION,
            stateManager.getViewMode(appWidgetId),
        )
        assertEquals(
            "Body zone tap should zoom from WIDE to NARROW",
            ZoomLevel.NARROW,
            stateManager.getZoomLevel(appWidgetId),
        )
    }

    @Test
    fun bodyZoneTap_onNarrowZoom_cyclesBackToWide() = runBlocking {
        stateManager.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.NARROW)
        stateManager.setHourlyOffset(appWidgetId, 0)

        val views = buildBodyZoneViews(ZoomLevel.NARROW)
        val applied = applyViews(views)
        val bodyZone = applied.findViewById<View>(R.id.graph_hour_zone_6)

        assertNotNull("Expected graph_hour_zone_6 to exist", bodyZone)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { bodyZone!!.performClick() }
        instrumentation.waitForIdleSync()

        waitForZoomLevel(ZoomLevel.WIDE)

        assertEquals(
            "Body zone tap should cycle NARROW back to WIDE",
            ZoomLevel.WIDE,
            stateManager.getZoomLevel(appWidgetId),
        )
        assertEquals(
            "View mode should stay PRECIPITATION",
            ViewMode.PRECIPITATION,
            stateManager.getViewMode(appWidgetId),
        )
    }

    private fun buildBodyZoneViews(zoom: ZoomLevel): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        views.setViewVisibility(R.id.graph_hour_zones, View.VISIBLE)

        val zoneCount = 13
        for (i in 0 until zoneCount) {
            val zoneIdResId = context.resources.getIdentifier(
                "graph_hour_zone_$i", "id", context.packageName
            )
            if (zoneIdResId == 0) continue

            val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(i, 0, zoom)
            val zoomIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_CYCLE_ZOOM
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10000 + 500 + i,
                zoomIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(zoneIdResId, pendingIntent)
        }
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

    private fun waitForZoomLevel(expected: ZoomLevel) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (stateManager.getZoomLevel(appWidgetId) == expected) {
                return
            }
            Thread.sleep(50)
        }
        assertEquals(expected, stateManager.getZoomLevel(appWidgetId))
    }

    private fun insertHourlyRows() = runBlocking {
        db.hourlyForecastDao().insertAll(sampleHourlyForecasts(LocalDateTime.now()))
    }

    private fun sampleHourlyForecasts(now: LocalDateTime): List<HourlyForecastEntity> {
        val start = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusHours(8)
        val fetchedAt = System.currentTimeMillis()
        return (0..24).map { index ->
            val time = start.plusHours(index.toLong())
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            HourlyForecastEntity(
                dateTime = time,
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 58f + index,
                condition = if (index % 3 == 0) "Cloudy" else "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = if (index % 4 == 0) 20 else 0,
                cloudCover = (25 + index * 2).coerceAtMost(100),
                fetchedAt = fetchedAt,
            )
        }
    }
}
