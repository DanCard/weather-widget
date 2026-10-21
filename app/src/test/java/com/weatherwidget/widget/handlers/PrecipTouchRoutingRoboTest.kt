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
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetActions
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class PrecipTouchRoutingRoboTest {
    private lateinit var context: Context
    private lateinit var app: Application
    private val appWidgetId = 731
    private val SIM_W = 400
    private val SIM_H = 240

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        app = RuntimeEnvironment.getApplication()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `wide precipitation graph routes all body zone taps to zoom`() = runBlocking {
        val views = renderPrecipWidget(
            options = graphOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
                it.setZoomLevel(appWidgetId, ZoomStage.WIDE)
                it.setHourlyOffset(appWidgetId, 0)
            },
        )

        val applied = applyViews(views)
        val hourZones = applied.findViewById<View>(R.id.graph_hour_zones)
        assertEquals(View.VISIBLE, hourZones.visibility)

        val shadowApp = shadowOf(app)
        val zoneIds = (0..12).map { i ->
            context.resources.getIdentifier("graph_hour_zone_$i", "id", context.packageName)
        }

        for ((i, zoneId) in zoneIds.withIndex()) {
            val zone = applied.findViewById<View>(zoneId)
            assertNotNull("Zone $i should exist", zone)

            val beforeTap = shadowApp.broadcastIntents.size
            zone.performClick()

            val intent = shadowApp.broadcastIntents.drop(beforeTap).lastOrNull()
            assertNotNull("Body zone $i should fire an intent", intent)
            assertEquals(
                "Body zone $i should zoom, not navigate",
                WidgetActions.ACTION_CYCLE_ZOOM,
                intent!!.action,
            )
            assertEquals(
                "Body zone $i should include widget ID",
                appWidgetId,
                intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
            )
            assertTrue(
                "Body zone $i should include zoom center offset",
                intent.hasExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET),
            )
        }
    }

    @Test
    fun `narrow precipitation graph routes all body zone taps to zoom`() = runBlocking {
        val views = renderPrecipWidget(
            options = graphOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
                it.setZoomLevel(appWidgetId, ZoomStage.NARROW)
                it.setHourlyOffset(appWidgetId, 0)
            },
        )

        val applied = applyViews(views)
        val hourZones = applied.findViewById<View>(R.id.graph_hour_zones)
        assertEquals(View.VISIBLE, hourZones.visibility)

        val shadowApp = shadowOf(app)
        val zoneIds = (0..12).map { i ->
            context.resources.getIdentifier("graph_hour_zone_$i", "id", context.packageName)
        }

        for ((i, zoneId) in zoneIds.withIndex()) {
            val zone = applied.findViewById<View>(zoneId)
            assertNotNull("Zone $i should exist", zone)

            val beforeTap = shadowApp.broadcastIntents.size
            zone.performClick()

            val intent = shadowApp.broadcastIntents.drop(beforeTap).lastOrNull()
            assertNotNull("Narrow body zone $i should fire an intent", intent)
            assertEquals(
                "Narrow body zone $i should zoom",
                WidgetActions.ACTION_CYCLE_ZOOM,
                intent!!.action,
            )
        }
    }

    @Test
    fun `precipitation graph bottom row zones still route by icon type`() = runBlocking {
        val views = renderPrecipWidget(
            options = graphOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
                it.setZoomLevel(appWidgetId, ZoomStage.WIDE)
                it.setHourlyOffset(appWidgetId, 0)
            },
        )

        val applied = applyViews(views)
        val shadowApp = shadowOf(app)

        val zoneIds = (0..12).map { i ->
            context.resources.getIdentifier("graph_bottom_hour_zone_$i", "id", context.packageName)
        }

        var foundSetView = false
        for ((i, zoneId) in zoneIds.withIndex()) {
            val zone = applied.findViewById<View>(zoneId)
            if (zone == null || zone.visibility != View.VISIBLE) continue

            val beforeTap = shadowApp.broadcastIntents.size
            zone.performClick()

            val intent = shadowApp.broadcastIntents.drop(beforeTap).lastOrNull()
            if (intent?.action == WidgetActions.ACTION_SET_VIEW) {
                val target = intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW)
                assertNotNull("SET_VIEW should include target view", target)
                foundSetView = true
                break
            }
        }
        assertTrue(
            "At least one bottom row zone should navigate to another view (icon-dependent routing)",
            foundSetView,
        )
    }

    @Test
    fun `text mode hides graph touch overlays`() = runBlocking {
        val views = renderPrecipWidget(
            options = textOptions(),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
                it.setZoomLevel(appWidgetId, ZoomStage.WIDE)
            },
        )

        val applied = applyViews(views)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_view).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_bottom_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_bottom_hour_zones).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.sim_loop_touch_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.radar_touch_zone).visibility)
        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.text_container).visibility)
    }

    @Test
    fun `sim loop zone sits in the plot, is a real tap target, and launches RainForecastActivity`() = runBlocking {
        val views = renderPrecipWidget(
            options = sizedOptions(SIM_W, SIM_H),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
                it.setWidgetLocations(intArrayOf(appWidgetId), 37.42, -122.09)
            },
        )

        val applied = applyViews(views, SIM_W, SIM_H)
        val simLoopZone = applied.findViewById<View>(R.id.sim_loop_touch_zone)
        assertEquals(View.VISIBLE, simLoopZone.visibility)

        // Robolectric runs at density 1, so px == dp here.
        val zone = rectIn(applied, simLoopZone)
        assertTrue("zone is at least 48dp tall: $zone", zone.height() >= 47)
        for (id in listOf(R.id.api_touch_zone, R.id.settings_touch_zone)) {
            val header = rectIn(applied, applied.findViewById(id))
            assertFalse("sim loop zone $zone must not overlap header button $header", android.graphics.Rect.intersects(zone, header))
        }
        assertTrue("zone starts below the header row: $zone", zone.top >= 8 + SimLoopTouchTarget.HEADER_TOUCH_BOTTOM_DP.toInt())

        val shadowApp = shadowOf(app)
        simLoopZone.performClick()
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull("Sim loop zone should launch an activity", startedIntent)
        assertEquals(
            com.weatherwidget.ui.RainForecastActivity::class.java.name,
            startedIntent.component?.className,
        )
        assertEquals(37.42, startedIntent.getDoubleExtra(com.weatherwidget.ui.RainForecastActivity.EXTRA_LAT, 0.0), 0.001)
        assertEquals(-122.09, startedIntent.getDoubleExtra(com.weatherwidget.ui.RainForecastActivity.EXTRA_LON, 0.0), 0.001)
    }

    @Test
    fun `radar zone sits in the plot apart from the sim loop zone and launches RadarActivity`() = runBlocking {
        val views = renderPrecipWidget(
            options = sizedOptions(SIM_W, SIM_H),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
                it.setWidgetLocations(intArrayOf(appWidgetId), 37.42, -122.09)
            },
        )

        val applied = applyViews(views, SIM_W, SIM_H)
        val radarZone = applied.findViewById<View>(R.id.radar_touch_zone)
        assertEquals(View.VISIBLE, radarZone.visibility)
        val zone = rectIn(applied, radarZone)
        assertTrue("zone is at least 48dp tall: $zone", zone.height() >= 47)
        for (id in listOf(R.id.api_touch_zone, R.id.settings_touch_zone)) {
            val header = rectIn(applied, applied.findViewById(id))
            assertFalse("radar zone $zone must not overlap header button $header", android.graphics.Rect.intersects(zone, header))
        }
        val simLoopZone = applied.findViewById<View>(R.id.sim_loop_touch_zone)
        if (simLoopZone.visibility == View.VISIBLE) {
            val sim = rectIn(applied, simLoopZone)
            assertFalse("radar zone $zone and sim loop zone $sim share taps", android.graphics.Rect.intersects(zone, sim))
        }

        radarZone.performClick()
        val startedIntent = shadowOf(app).nextStartedActivity
        assertEquals(com.weatherwidget.ui.RadarActivity::class.java.name, startedIntent.component?.className)
        assertEquals(37.42, startedIntent.getDoubleExtra(com.weatherwidget.ui.RadarActivity.EXTRA_LAT, 0.0), 0.001)
        assertEquals(-122.09, startedIntent.getDoubleExtra(com.weatherwidget.ui.RadarActivity.EXTRA_LON, 0.0), 0.001)
    }

    @Test
    fun `radar is offered for every source, Kyiv included`() = runBlocking {
        val views = renderPrecipWidget(
            options = sizedOptions(SIM_W, SIM_H),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
                it.setWidgetLocations(intArrayOf(appWidgetId), 50.45, 30.52)
            },
            sources = listOf(WeatherSource.SILURIAN, WeatherSource.NWS, WeatherSource.OPEN_METEO),
        )
        assertEquals(View.VISIBLE, applyViews(views, SIM_W, SIM_H).findViewById<View>(R.id.radar_touch_zone).visibility)
    }

    private suspend fun simLoopTarget(source: WeatherSource, lat: Double, lon: Double): String? {
        val views = renderPrecipWidget(
            options = sizedOptions(SIM_W, SIM_H),
            configureState = {
                it.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
                it.setWidgetLocations(intArrayOf(appWidgetId), lat, lon)
            },
            sources = listOf(source) + listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO).filter { it != source },
        )
        val zone = applyViews(views, SIM_W, SIM_H).findViewById<View>(R.id.sim_loop_touch_zone)
        if (zone.visibility != View.VISIBLE) return null
        zone.performClick()
        return shadowOf(app).nextStartedActivity?.component?.className
    }

    @Test
    fun `Open-Meteo shown - pill opens the Open-Meteo rain map, even where WeatherNext has no map`() = runBlocking {
        assertEquals(com.weatherwidget.ui.OpenMeteoRainLoopActivity::class.java.name, simLoopTarget(WeatherSource.OPEN_METEO, 37.42, -122.09))
        assertEquals(com.weatherwidget.ui.OpenMeteoRainLoopActivity::class.java.name, simLoopTarget(WeatherSource.OPEN_METEO, 50.45, 30.52)) // Kyiv
    }

    @Test
    fun `other sources - pill opens WeatherNext as before, and stays hidden off its map`() = runBlocking {
        assertEquals(com.weatherwidget.ui.RainForecastActivity::class.java.name, simLoopTarget(WeatherSource.NWS, 37.42, -122.09))
        assertNull(simLoopTarget(WeatherSource.SILURIAN, 50.45, 30.52)) // Kyiv: off WeatherNext's map
    }

    private fun rectIn(root: View, view: View): android.graphics.Rect {
        var x = 0
        var y = 0
        var v: View? = view
        while (v != null && v !== root) {
            x += v.left
            y += v.top
            v = v.parent as? View
        }
        return android.graphics.Rect(x, y, x + view.width, y + view.height)
    }

    private suspend fun renderPrecipWidget(
        options: Bundle,
        configureState: (WidgetStateManager) -> Unit,
        sources: List<WeatherSource> = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
    ): RemoteViews {
        val stateManager = WidgetStateManager(context)
        // Debug builds default to Google Weather as primary (2026-10-06); routing here is about NWS icons.
        stateManager.setVisibleSourcesOrder(sources)
        configureState(stateManager)

        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns options

        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit

        val now = LocalDateTime.now()
        PrecipViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now, sources.first()),
            centerTime = now,
            precipProbability = 10,
        )

        return viewsSlot.captured
    }

    private fun applyViews(views: RemoteViews, widthPx: Int = 600, heightPx: Int = 400): View {
        val root = FrameLayout(context)
        val applied = views.apply(context, root as ViewGroup)
        val widthSpec = MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY)
        applied.measure(widthSpec, heightSpec)
        applied.layout(0, 0, applied.measuredWidth, applied.measuredHeight)
        return applied
    }

    private fun graphOptions(): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150)
    }

    /** A Pixel-like 5x2 precipitation graph: room for the pill above a low curve. */
    private fun sizedOptions(widthDp: Int, heightDp: Int): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
    }

    private fun textOptions(): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
    }

    private fun sampleHourlyForecasts(now: LocalDateTime, source: WeatherSource = WeatherSource.NWS): List<HourlyForecastEntity> {
        val start = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusHours(8)
        return (0..24).map { index ->
            val time = start.plusHours(index.toLong())
            HourlyForecastEntity(
                dateTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 60f + index,
                condition = if (index % 3 == 0) "Cloudy" else "Clear",
                source = source.id,
                precipProbability = if (index % 4 == 0) 20 else 0,
                fetchedAt = System.currentTimeMillis(),
            )
        }
    }
}
