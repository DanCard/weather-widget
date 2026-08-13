package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.ui.ForecastHistoryActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Device-side regression for the day-tap NPE (2026-07-08): real framework rendering (actual
 * Canvas/Bitmap/RemoteViews, real display metrics — everything Robolectric fakes) of the day-tap
 * chain against the production crash shape: full OPEN_METEO hourly coverage, NWS missing two
 * hours, NWS displayed.
 *
 * The command receiver runs with its real IO scope, so completion is detected by awaiting the
 * DAY_CLICK_RENDER_OK / DAY_CLICK_FAIL app_logs breadcrumbs that WidgetIntentRouter.handleDayClick
 * persists (the old failure mode was a swallowed logcat-only exception — invisible to app_logs
 * sweeps).
 *
 * Runs via ./scripts/emulator-tests.sh ONLY (IsolatedIntegrationTest clears the app database).
 * See plans/260708-daytap-npe-automated-testplan.md; JVM layers:
 * CurrentTemperatureResolverSourceGapTest, TemperatureViewHandlerSourceGapRoboTest,
 * WeatherWidgetProviderDayTapSourceGapRoboTest.
 */
@RunWith(AndroidJUnit4::class)
class DayTapSourceGapInstrumentedTest : IsolatedIntegrationTest("day_tap_source_gap") {

    private lateinit var stateManager: WidgetStateManager
    private val widgetId = 9114
    private val lat = 37.42
    private val lon = -122.08
    private val source = WeatherSource.NWS

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.clearTransientMessage(widgetId)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(widgetId, source)
        seedSourceGapToday()
    }

    @After
    override fun cleanup() {
        stateManager.clearWidgetState(widgetId)
        super.cleanup()
    }

    @Test
    fun dayTapRendersHourlyViewDespiteSourceGaps() {
        context.sendBroadcast(dayClickIntent(LocalDate.now()))

        val (renderOk, fails) = awaitDayClickOutcome(timeoutMs = 15_000)

        assertTrue("no DAY_CLICK_FAIL row expected; got ${fails.map { it.message }}", fails.isEmpty())
        assertTrue(
            "DAY_CLICK_RENDER_OK must be persisted within timeout; got ${renderOk.map { it.message }}",
            renderOk.any { it.message.contains("widget=$widgetId") && it.message.contains("mode=TEMPERATURE") },
        )
        assertEquals(
            "day tap must flip stored view mode to TEMPERATURE",
            ViewMode.TEMPERATURE,
            stateManager.getViewMode(widgetId),
        )
    }

    private fun awaitDayClickOutcome(timeoutMs: Long) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = db.appLogDao().getLogsByTag("DAY_CLICK_RENDER_OK", 10)
            val fail = db.appLogDao().getLogsByTag("DAY_CLICK_FAIL", 10)
            if (ok.isNotEmpty() || fail.isNotEmpty()) return@runBlocking ok to fail
            delay(200)
        }
        db.appLogDao().getLogsByTag("DAY_CLICK_RENDER_OK", 10) to
            db.appLogDao().getLogsByTag("DAY_CLICK_FAIL", 10)
    }

    /**
     * Emulator repro shape: OPEN_METEO covers every hour of the resolution window, NWS is
     * missing two mid-window hours — those buckets pick null for displaySource=NWS. A
     * full-coverage fixture would pass even with the old `!!` bug.
     */
    private fun seedSourceGapToday() {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val nwsGapHours = setOf(-5L, -4L)
        runBlocking {
            db.hourlyForecastDao().insertAll(
                (-12L..3L).flatMap { h ->
                    buildList {
                        add(hourly(now.plusHours(h), 61f, WeatherSource.OPEN_METEO))
                        if (h !in nwsGapHours) add(hourly(now.plusHours(h), 66f, WeatherSource.NWS))
                    }
                },
            )
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = LocalDate.now().toEpochDay() * 24 * 60 * 60 * 1000L,
                        dateOfPrediction = LocalDate.now().toEpochDay() * 24 * 60 * 60 * 1000L,
                        locationLat = lat,
                        locationLon = lon,
                        highTemp = 78f,
                        lowTemp = 55f,
                        condition = "Sunny",
                        source = source.id,
                        precipProbability = 0,
                        fetchedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    private fun hourly(time: LocalDateTime, temp: Float, src: WeatherSource): HourlyForecastEntity =
        HourlyForecastEntity(
            dateTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            locationLat = lat,
            locationLon = lon,
            temperature = temp,
            condition = "Clear",
            source = src.id,
            precipProbability = 0,
            fetchedAt = System.currentTimeMillis(),
        )

    private fun dayClickIntent(targetDay: LocalDate): Intent =
        Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("date", targetDay.toString())
            putExtra("isHistory", false)
            putExtra("showHistory", false)
            putExtra("index", 2)
            putExtra(WidgetActions.EXTRA_TARGET_VIEW, ViewMode.TEMPERATURE.name)
            putExtra(WidgetActions.EXTRA_HOURLY_OFFSET, 0)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, source.displayName)
        }
}
