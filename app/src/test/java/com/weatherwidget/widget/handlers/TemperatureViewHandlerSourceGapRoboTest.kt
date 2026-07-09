package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

/**
 * Regression test for the day-tap NPE (2026-07-08): tapping a day silently failed with
 * `handleSetView failed ... java.lang.NullPointerException` when any hour bucket in the loaded
 * window had rows from other sources but none from the displayed source. pickBestForecast
 * legitimately returns null for such buckets; the old computeSmoothedForecasts asserted `!!`.
 *
 * This drives the same path the widget takes on a day tap
 * (TemperatureViewHandler.updateWidget → TemperatureStateResolver.resolve →
 * computeSmoothedForecasts) with the production data shape that crashed on-device: full
 * OPEN_METEO coverage, NWS missing a couple of hours, NWS displayed.
 *
 * The gap buckets are key: a fixture where every hour has an NWS row would pass even with the
 * `!!` bug. See CurrentTemperatureResolverSourceGapTest (:shared) for the pure-function cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureViewHandlerSourceGapRoboTest {

    private val lat = 37.42
    private val lon = -122.08
    private val widgetId = 997

    private lateinit var context: android.content.Context
    private lateinit var db: WeatherDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `day tap renders hourly view when displayed source has hour gaps`() = runBlocking {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val zoneId = ZoneId.systemDefault()

        // Emulator repro shape: OPEN_METEO covers every hour, NWS is missing two mid-window
        // hours — those two buckets resolve to a null pick for displaySource=NWS.
        val nwsGapHours = setOf(-5L, -4L)
        val hours = (-12L..2L).flatMap { h ->
            buildList {
                add(hourly(now.plusHours(h), 61f, WeatherSource.OPEN_METEO))
                if (h !in nwsGapHours) add(hourly(now.plusHours(h), 66f, WeatherSource.NWS))
            }
        }
        db.hourlyForecastDao().insertAll(hours)

        val window = com.weatherwidget.widget.CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)
        val minEpoch = window.start.atZone(zoneId).toInstant().toEpochMilli()
        val maxEpoch = window.end.atZone(zoneId).toInstant().toEpochMilli()
        val loaded = db.hourlyForecastDao().getHourlyForecasts(minEpoch, maxEpoch, lat, lon)

        // Fixture sanity: the loaded window must actually contain NWS-less buckets, or this
        // test would pass even with the `!!` bug.
        val bucketsWithoutNws = loaded.groupBy { it.dateTime }
            .count { (_, rows) -> rows.none { it.source == WeatherSource.NWS.id } }
        assert(bucketsWithoutNws >= 2) { "fixture must contain NWS-less hour buckets; got $bucketsWithoutNws" }

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 260)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }
        every { appWidgetManager.getAppWidgetOptions(widgetId) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(widgetId, capture(viewsSlot)) } just runs

        // Old code: NullPointerException propagates out of updateWidget here.
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = widgetId,
            hourlyForecasts = loaded,
            currentTempHourlyForecasts = loaded,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val currentTempText = applied.findViewById<TextView>(R.id.current_temp).text.toString()
        assert(currentTempText.isNotEmpty()) { "widget must render a current temp despite source gaps" }
    }

    private fun hourly(time: LocalDateTime, temp: Float, source: WeatherSource): HourlyForecastEntity =
        HourlyForecastEntity(
            dateTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            locationLat = lat,
            locationLon = lon,
            temperature = temp,
            condition = "Clear",
            source = source.id,
            precipProbability = 0,
            fetchedAt = System.currentTimeMillis(),
        )
}
