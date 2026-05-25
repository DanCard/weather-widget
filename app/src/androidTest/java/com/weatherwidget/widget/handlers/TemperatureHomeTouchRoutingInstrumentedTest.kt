package com.weatherwidget.widget.handlers

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
import com.weatherwidget.testutil.WidgetStateTestUtils
import com.weatherwidget.widget.ViewMode
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

/**
 * Device-side regression for the hourly temperature header home shortcut.
 *
 * This renders the production temperature hourly widget wiring, taps the active home target,
 * and verifies that the widget returns to DAILY mode.
 */
@RunWith(AndroidJUnit4::class)
class TemperatureHomeTouchRoutingInstrumentedTest :
    IsolatedIntegrationTest("temperature_home_touch_routing") {

    private lateinit var stateManager: WidgetStateManager
    private val appWidgetId = 915

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
    fun homeHeaderTap_switchesFromTemperatureToDaily() = runBlocking {
        stateManager.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
        stateManager.setHourlyOffset(appWidgetId, 6)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.SILURIAN)

        val views = buildRenderedTemperatureViews()
        val applied = applyViews(views)
        val activeHomeTarget = findActiveHomeTarget(applied)

        assertNotNull("Expected an active home target in hourly temperature view", activeHomeTarget)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            activeHomeTarget!!.performClick()
        }
        instrumentation.waitForIdleSync()

        waitForViewMode(ViewMode.DAILY)

        assertEquals(
            "Home header tap should switch back to daily mode",
            ViewMode.DAILY,
            stateManager.getViewMode(appWidgetId),
        )
    }

    private suspend fun buildRenderedTemperatureViews(): RemoteViews {
        val centerTime = LocalDateTime.now().plusHours(stateManager.getHourlyOffset(appWidgetId).toLong())
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val hourlyForecasts = db.hourlyForecastDao().getHourlyForecasts(
            startDateTime = centerTime.minusHours(12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            endDateTime = centerTime.plusHours(18).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            lat = 37.0,
            lon = -122.0,
        )
        val currentTempHourlyForecasts = db.hourlyForecastDao().getHourlyForecasts(
            startDateTime = LocalDateTime.now().minusHours(8).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            endDateTime = LocalDateTime.now().plusHours(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            lat = 37.0,
            lon = -122.0,
        )

        val dimensions = WidgetDimensions(
            cols = 4,
            rows = 2,
            widthDp = 280,
            heightDp = 180,
            isIconWidth = false,
        )
        val resolution = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourlyForecasts,
            currentTempHourlyForecasts = currentTempHourlyForecasts,
            centerTime = centerTime,
            displaySource = displaySource,
            precipProbability = null,
            lastObservedTemp = null,
            observedAt = null,
            dimensions = dimensions,
            stateManager = stateManager,
            repository = null,
            deferCurrentTempResolution = false,
        )

        return RemoteViews(context.packageName, R.layout.widget_weather).also { views ->
            TemperatureViewBinder.bind(
                context = context,
                views = views,
                state = resolution.state,
                stateManager = stateManager,
                centerTime = centerTime,
                hourlyForecasts = hourlyForecasts,
            )
        }
    }

    private fun findActiveHomeTarget(root: View): View? {
        val floating = root.findViewById<View>(R.id.home_touch_zone)
        if (floating.visibility == View.VISIBLE) return floating

        val inline = root.findViewById<View>(R.id.home_touch_zone_inline)
        if (inline.visibility == View.VISIBLE) return inline

        return null
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
        WidgetStateTestUtils.waitForViewMode(context, stateManager, appWidgetId, expected)
    }

    private fun insertHourlyRows() = runBlocking {
        db.hourlyForecastDao().insertAll(sampleHourlyForecasts(LocalDateTime.now()))
    }

    private fun sampleHourlyForecasts(now: LocalDateTime): List<HourlyForecastEntity> {
        val start = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusHours(12)
        val fetchedAt = System.currentTimeMillis()
        val zoneId = ZoneId.systemDefault()
        return (0..30).flatMap { index ->
            val dt = start.plusHours(index.toLong())
            val timeMs = dt.atZone(zoneId).toInstant().toEpochMilli()
            listOf(
                HourlyForecastEntity(
                    dateTime = timeMs,
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
                    dateTime = timeMs,
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
