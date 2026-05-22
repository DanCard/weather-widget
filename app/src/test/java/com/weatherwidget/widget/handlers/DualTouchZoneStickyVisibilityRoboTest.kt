package com.weatherwidget.widget.handlers

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Regression coverage for the sticky-visibility leak that made the home button appear
 * broken in temperature mode: DailyViewHandler sets dual_touch_zone VISIBLE when the
 * "show two bars" option is enabled, but the non-daily binders previously never reset
 * it. Because dual_touch_zone overlaps home_touch_zone in the layout (and is declared
 * later, so it draws on top), a tap on the home icon was routed to ACTION_TOGGLE_DUAL_BARS
 * instead of ACTION_SET_VIEW(DAILY).
 *
 * RemoteViews are deltas, so the bug only reproduces under reapply onto a view that
 * already has the sticky state. We simulate that here by mutating the view between
 * apply() and reapply().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(LongDuration::class)
class DualTouchZoneStickyVisibilityRoboTest {

    private lateinit var context: Context
    private val appWidgetId = 314

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        try { WorkManager.initialize(context, Configuration.Builder().build()) } catch (_: IllegalStateException) { }
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `temperature view resets sticky dual_touch_zone to GONE`() = runBlocking {
        val views = renderTemperatureWidget()
        assertReapplyResetsDualTouchZone(views)
    }

    @Test
    fun `precipitation view resets sticky dual_touch_zone to GONE`() = runBlocking {
        val views = renderPrecipWidget()
        assertReapplyResetsDualTouchZone(views)
    }

    @Test
    fun `cloud cover view resets sticky dual_touch_zone to GONE`() = runBlocking {
        val views = renderCloudCoverWidget()
        assertReapplyResetsDualTouchZone(views)
    }

    private fun assertReapplyResetsDualTouchZone(views: RemoteViews) {
        val root = FrameLayout(context)
        val applied = views.apply(context, root as ViewGroup)
        val zone = applied.findViewById<View>(R.id.dual_touch_zone)
        zone.visibility = View.VISIBLE
        views.reapply(context, applied)
        assertEquals(
            "non-daily binder must explicitly reset dual_touch_zone to GONE — otherwise " +
                "sticky VISIBLE state from a prior DAILY render leaks into this view and " +
                "intercepts taps on the home icon",
            View.GONE,
            zone.visibility,
        )
    }

    private suspend fun renderTemperatureWidget(): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)
        val (manager, viewsSlot) = mockWidgetManager(graphOptions())
        val now = LocalDateTime.of(2026, 5, 15, 12, 0)
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = manager,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            currentTempHourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
        )
        return viewsSlot.captured
    }

    private suspend fun renderPrecipWidget(): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)
        val (manager, viewsSlot) = mockWidgetManager(graphOptions())
        val now = LocalDateTime.of(2026, 5, 15, 12, 0)
        PrecipViewHandler.updateWidget(
            context = context,
            appWidgetManager = manager,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            precipProbability = 20,
        )
        return viewsSlot.captured
    }

    private suspend fun renderCloudCoverWidget(): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)
        val (manager, viewsSlot) = mockWidgetManager(graphOptions())
        val now = LocalDateTime.of(2026, 5, 15, 12, 0)
        CloudCoverViewHandler.updateWidget(
            context = context,
            appWidgetManager = manager,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
        )
        return viewsSlot.captured
    }

    private fun mockWidgetManager(
        options: Bundle,
    ): Pair<AppWidgetManager, io.mockk.CapturingSlot<RemoteViews>> {
        val manager = mockk<AppWidgetManager>()
        every { manager.getAppWidgetOptions(appWidgetId) } returns options
        val viewsSlot = slot<RemoteViews>()
        every { manager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit
        return manager to viewsSlot
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
