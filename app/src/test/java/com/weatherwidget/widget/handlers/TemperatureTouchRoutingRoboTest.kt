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
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.HourlyTouchZoneMapper
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomStage
import com.weatherwidget.widget.ZoomWindow
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
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



import android.view.LayoutInflater
import android.widget.LinearLayout

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureTouchRoutingRoboTest {
    private lateinit var context: Context
    private lateinit var app: Application
    private val appWidgetId = 314
    private val fixedNow = LocalDateTime.of(2026, 1, 15, 12, 0)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        app = RuntimeEnvironment.getApplication()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `verify no dead zone between graph body and bottom zones`() {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.widget_weather, null)

        val hourZones = root.findViewById<LinearLayout>(R.id.graph_hour_zones)
        val hourZonesParams = hourZones.layoutParams as ViewGroup.MarginLayoutParams

        // The margin should be 0 because the parent container already
        // excludes the bottom row.
        assertEquals(
            "Redundant margin detected on graph_hour_zones, causing a touch dead zone",
            0,
            hourZonesParams.bottomMargin,
        )
    }

    @Test
    fun `wide hourly graph routes body taps to zoom and bottom row taps by icon type`() = runBlocking {
        val views = renderTemperatureWidget(
            options = graphOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
                it.setZoomLevel(appWidgetId, ZoomStage.WIDE)
                it.setHourlyOffset(appWidgetId, 0)
            },
        )

        val applied = applyViews(views)
        val bodyZone = applied.findViewById<View>(R.id.graph_hour_zone_0)
        val hourZones = applied.findViewById<View>(R.id.graph_hour_zones)
        val bottomHourZones = applied.findViewById<View>(R.id.graph_bottom_hour_zones)
        val bottomZone = applied.findViewById<View>(R.id.graph_bottom_zone)
        val graphBodyTapZone = applied.findViewById<View>(R.id.graph_body_tap_zone)

        assertEquals(View.VISIBLE, bodyZone.visibility)
        assertEquals(View.VISIBLE, bottomHourZones.visibility)
        assertEquals(View.GONE, bottomZone.visibility)
        assertEquals(View.GONE, graphBodyTapZone.visibility)

        val shadowApp = shadowOf(app)

        // Body taps in temperature view always zoom, even when the aligned hour is cloudy.
        val beforeBodyTap = shadowApp.broadcastIntents.size
        bodyZone.performClick()

        val cloudyBodyIntent = shadowApp.broadcastIntents.drop(beforeBodyTap).lastOrNull()
        assertNotNull("Expected cloudy body zone tap to zoom", cloudyBodyIntent)
        assertEquals(WidgetActions.ACTION_CYCLE_ZOOM, cloudyBodyIntent!!.action)
        assertEquals(appWidgetId, cloudyBodyIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

        // Body zone 1 also zooms.
        val clearBodyZone = applied.findViewById<View>(R.id.graph_hour_zone_1)
        val beforeClearTap = shadowApp.broadcastIntents.size
        clearBodyZone.performClick()

        val clearZoomIntent = shadowApp.broadcastIntents.drop(beforeClearTap).lastOrNull()
        assertNotNull("Expected clear body zone tap to zoom", clearZoomIntent)
        assertEquals(WidgetActions.ACTION_CYCLE_ZOOM, clearZoomIntent!!.action)

        // Test bottom hour overlay zones (the icons themselves)
        val overlayZone0 = applied.findViewById<View>(R.id.graph_bottom_hour_zone_0)
        val beforeOverlayTap = shadowApp.broadcastIntents.size
        overlayZone0.performClick()
        val overlayIntent = shadowApp.broadcastIntents.drop(beforeOverlayTap).lastOrNull()
        assertNotNull("Expected bottom hour overlay zone tap to trigger an intent", overlayIntent)
        assertEquals(WidgetActions.ACTION_SET_VIEW, overlayIntent!!.action)
        assertEquals(ViewMode.CLOUD_COVER.name, overlayIntent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
    }

    @Test
    fun `home icon broadcasts ACTION_SET_VIEW with target DAILY`() = runBlocking {
        val views = renderTemperatureWidget(
            options = graphOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
                it.setZoomLevel(appWidgetId, ZoomStage.WIDE)
            },
        )
        val applied = applyViews(views)

        val shadowApp = shadowOf(app)
        for (viewId in listOf(R.id.home_icon, R.id.home_touch_zone)) {
            val before = shadowApp.broadcastIntents.size
            applied.findViewById<View>(viewId).performClick()
            val intent = shadowApp.broadcastIntents.drop(before).lastOrNull()
            assertNotNull("Expected tap on $viewId to broadcast an intent", intent)
            assertEquals(
                "Tap on $viewId must route to ACTION_SET_VIEW so the widget returns to daily mode",
                WidgetActions.ACTION_SET_VIEW,
                intent!!.action,
            )
            assertEquals(
                "Tap on $viewId must request the DAILY view as the target",
                ViewMode.DAILY.name,
                intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW),
            )
            assertEquals(
                appWidgetId,
                intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
            )
        }
    }

    @Test
    fun `narrow hourly graph routes body taps to zoom and bottom row taps by icon type`() = runBlocking {
        val views = renderTemperatureWidget(
            options = graphOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
                it.setZoomLevel(appWidgetId, ZoomStage.NARROW)
            },
        )

        val applied = applyViews(views)
        val graphBodyTapZone = applied.findViewById<View>(R.id.graph_body_tap_zone)
        val hourZones = applied.findViewById<View>(R.id.graph_hour_zones)
        val bottomHourZones = applied.findViewById<View>(R.id.graph_bottom_hour_zones)
        val bottomZone = applied.findViewById<View>(R.id.graph_bottom_zone)
        val hourZone0 = applied.findViewById<View>(R.id.graph_hour_zone_0)

        assertEquals(View.GONE, graphBodyTapZone.visibility)
        assertEquals(View.VISIBLE, hourZones.visibility)
        assertEquals(View.VISIBLE, bottomHourZones.visibility)
        assertEquals(View.GONE, bottomZone.visibility)

        val shadowApp = shadowOf(app)
        val beforeBodyTap = shadowApp.broadcastIntents.size
        hourZone0.performClick()

        val cloudyBodyIntent = shadowApp.broadcastIntents.drop(beforeBodyTap).lastOrNull()
        assertNotNull("Expected cloudy narrow hour zone tap to zoom", cloudyBodyIntent)
        assertEquals(WidgetActions.ACTION_CYCLE_ZOOM, cloudyBodyIntent!!.action)

        val candidateZoneIds =
            listOf(
                R.id.graph_hour_zone_1,
                R.id.graph_hour_zone_2,
                R.id.graph_hour_zone_3,
                R.id.graph_hour_zone_4,
                R.id.graph_hour_zone_5,
                R.id.graph_hour_zone_6,
            )
        var zoomIntentFound = false
        for (zoneId in candidateZoneIds) {
            val zone = applied.findViewById<View>(zoneId)
            val beforeTap = shadowApp.broadcastIntents.size
            zone.performClick()

            val intent = shadowApp.broadcastIntents.drop(beforeTap).lastOrNull()
            if (intent?.action == WidgetActions.ACTION_CYCLE_ZOOM) {
                assertTrue(intent.hasExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET))
                // The offset carried by the real PendingIntent must be the one the mapper computes
                // for this zone at the *configured* narrow span — not a hardcoded 4h-window hour.
                val zoneIndex = candidateZoneIds.indexOf(zoneId) + 1
                assertEquals(
                    "zone $zoneIndex offset at the default narrow span",
                    HourlyTouchZoneMapper.zoneIndexToOffset(
                        zoneIndex,
                        0,
                        ZoomStage.NARROW.window(WidgetStateManager(context).getNarrowZoomSpanHours()),
                    ),
                    intent.getIntExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET, Int.MIN_VALUE),
                )
                zoomIntentFound = true
                break
            }
        }
        assertTrue("Expected at least one non-cloud narrow body zone to zoom", zoomIntentFound)
    }

    @Test
    fun `zoom center offsets widen when the narrow span setting widens`() = runBlocking {
        // End-to-end guard for the setting: the hour a tap resolves to comes from the rendered
        // PendingIntent, so widening the span must widen the offsets baked into those intents.
        fun edgeOffsetAtSpan(span: Int): Int = runBlocking {
            val views = renderTemperatureWidget(
                options = graphOptions(),
                configureState = {
                    it.setNarrowZoomSpanHours(span)
                    it.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
                    it.setZoomLevel(appWidgetId, ZoomStage.NARROW)
                    it.setHourlyOffset(appWidgetId, 0)
                },
            )
            val applied = applyViews(views)
            val shadowApp = shadowOf(app)
            val before = shadowApp.broadcastIntents.size
            applied.findViewById<View>(R.id.graph_hour_zone_0).performClick()
            val intent = shadowApp.broadcastIntents.drop(before).last()
            assertEquals(WidgetActions.ACTION_CYCLE_ZOOM, intent.action)
            intent.getIntExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET, Int.MIN_VALUE)
        }

        // Zone 0 is the left edge, so it lands on -backHours: 2h back at a 4h span, 4h at 8h.
        assertEquals(-2, edgeOffsetAtSpan(4))
        assertEquals(-3, edgeOffsetAtSpan(5))
        assertEquals(-4, edgeOffsetAtSpan(8))
    }

    @Test
    fun `text mode hides graph touch overlays`() = runBlocking {
        val views = renderTemperatureWidget(
            options = textOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
                it.setZoomLevel(appWidgetId, ZoomStage.WIDE)
            },
        )

        val applied = applyViews(views)

        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_hour_zones).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_body_tap_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_bottom_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_bottom_hour_zones).visibility)
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

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(fixedNow),
            currentTempHourlyForecasts = sampleHourlyForecasts(fixedNow),
            centerTime = fixedNow,
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
        val start = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusHours(12)
        val fetchedAt = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return (0..48).map { index ->
            val time = start.plusHours(index.toLong())
            HourlyForecastEntity(
                dateTime = time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 60f + index,
                condition = if (index % 3 == 0) "Cloudy" else "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = if (index % 4 == 0) 20 else 0,
                fetchedAt = fetchedAt,
            )
        }
    }
}
