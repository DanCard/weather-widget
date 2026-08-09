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
        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.text_container).visibility)
    }

    private suspend fun renderPrecipWidget(
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
        PrecipViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            precipProbability = 10,
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

    private fun graphOptions(): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150)
    }

    private fun textOptions(): Bundle = Bundle().apply {
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
                dateTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
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
