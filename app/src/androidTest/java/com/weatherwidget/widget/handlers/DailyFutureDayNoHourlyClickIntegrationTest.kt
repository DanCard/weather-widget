package com.weatherwidget.widget.handlers

import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.testutil.WidgetStateTestUtils
import com.weatherwidget.testutil.dateEpoch
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Reproduces the reported bug: tapping a future day (e.g. "Tuesday of next week") whose active
 * source has no hourly data routed the user to Settings. Correct behavior: never open Settings —
 * show a brief on-widget "no hourly data — refreshing" message, kick a refresh, and stay put.
 *
 * The target day (today + 7) has a daily forecast (so the column renders) but NO hourly forecast
 * rows, which makes WeatherWidgetProvider.hasHourlyDataForDate() return false — the exact condition
 * that previously triggered the Settings launch.
 */
@RunWith(AndroidJUnit4::class)
class DailyFutureDayNoHourlyClickIntegrationTest : IsolatedIntegrationTest("daily_future_no_hourly_click") {

    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 8893

    // today + 7 lands on graph_day5_zone given dateOffset = +4 and numColumns = 5:
    // getDayOffsets(5, skipHistory=false) = [-1, 0, 1, 2, 3] relative to centerDate (= today + 4),
    // so column index 4 (graph_day5_zone) = today + 4 + 3 = today + 7.
    private val targetDay: LocalDate = LocalDate.now().plusDays(7)

    @Before
    override fun setup() {
        super.setup()
        WidgetStateManager.setPrefsNameOverrideForTesting(WidgetStateManager.DEFAULT_TEST_PREFS_NAME)

        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        stateManager.clearTransientMessage(testWidgetId)

        runBlocking {
            // Daily forecast for the future day so its column renders. Intentionally NO hourly
            // forecast rows and NO observations — this is the missing-hourly-data condition.
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = dateEpoch(targetDay.toString()),
                        dateOfPrediction = dateEpoch(LocalDate.now().toString()),
                        locationLat = WeatherWidgetWorker.DEFAULT_LAT,
                        locationLon = WeatherWidgetWorker.DEFAULT_LON,
                        highTemp = 78f,
                        lowTemp = 55f,
                        condition = "Sunny",
                        source = WeatherSource.NWS.id,
                        precipProbability = 0,
                        fetchedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    @After
    override fun cleanup() {
        stateManager.clearTransientMessage(testWidgetId)
        stateManager.clearWidgetState(testWidgetId)
        super.cleanup()
    }

    @Test
    fun clickingFutureDay_withNoHourlyData_showsMessageAndDoesNotOpenSettings() = runBlocking {
        // GIVEN: a daily widget navigated forward so the future day is on screen.
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
        stateManager.setDateOffset(testWidgetId, 4) // centerDate = today + 4
        WidgetStateTestUtils.waitForDateOffset(context, stateManager, testWidgetId, 4)

        val now = LocalDateTime.now()
        val views = renderDailyGraph(now)
        val applied = applyToRoot(views)

        val futureZone = applied.findViewById<View>(R.id.graph_day5_zone)
        assertNotNull("Future-day zone (today+7) should exist", futureZone)
        assertEquals("Future-day zone should be visible", View.VISIBLE, futureZone.visibility)

        // Watch for an (incorrect) Settings launch.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settingsMonitor: Instrumentation.ActivityMonitor =
            instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)

        // WHEN: clicking the future day with no hourly data.
        var clicked = false
        instrumentation.runOnMainSync {
            clicked = futureZone.performClick()
        }
        assertTrue("performClick should return true", clicked)
        instrumentation.waitForIdleSync()

        // THEN: a transient "no hourly forecast" message is set (the missing-data branch ran).
        val message = waitForTransientMessage()
        assertNotNull("A transient message should be displayed for the missing-hourly day", message)
        assertTrue(
            "Message should explain data is missing and a refresh will run: $message",
            message!!.contains("Hourly temperature data missing", ignoreCase = true),
        )
        assertTrue(
            "Phase 1 should be pending, not a refresh result: $message",
            message.contains("refresh will be triggered", ignoreCase = true),
        )
        assertTrue(
            "Phase 1 should not yet show refresh results: $message",
            !message.contains("Result of refresh"),
        )

        // AND: Settings was NOT opened (the core regression).
        assertEquals("Settings must not be launched on a day tap", 0, settingsMonitor.hits)
        instrumentation.removeMonitor(settingsMonitor)

        // AND: the view did not navigate away from DAILY.
        assertEquals(
            "Should remain on DAILY view (no navigation on missing hourly data)",
            ViewMode.DAILY,
            stateManager.getViewMode(testWidgetId),
        )

        // AND: the missing-hourly branch logged its decision.
        val logged = db.appLogDao().getLogsByTag("CLICK_DAILY_NO_HOURLY", 5)
        assertTrue("Expected a CLICK_DAILY_NO_HOURLY log entry", logged.isNotEmpty())

        // AND: a daily render shows the message banner (the bind path the UI repaint runs).
        val bannerViews = RemoteViews(context.packageName, R.layout.widget_weather)
        DailyViewHandler.bindTransientMessage(bannerViews, stateManager, testWidgetId, callerTag = "DAILY")
        val bannerRoot = applyToRoot(bannerViews)
        assertEquals(
            "Message banner should be visible after a missing-hourly tap",
            View.VISIBLE,
            bannerRoot.findViewById<View>(R.id.widget_message_banner).visibility,
        )
    }

    /** Polls for the async day-click handler to publish the transient message (up to ~10s). */
    private fun waitForTransientMessage(): String? {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val msg = stateManager.getActiveTransientMessage(testWidgetId)
            if (msg != null) return msg
            Thread.sleep(100)
        }
        return stateManager.getActiveTransientMessage(testWidgetId)
    }

    private suspend fun renderDailyGraph(now: LocalDateTime): RemoteViews {
        val today = now.toLocalDate()
        val displaySource = WeatherSource.NWS
        val dateOffset = stateManager.getDateOffset(testWidgetId)
        val centerDate = today.plusDays(dateOffset.toLong())

        val forecasts = db.forecastDao().getForecastsInRange(
            dateEpoch(today.minusDays(5).toString()),
            dateEpoch(today.plusDays(10).toString()),
            WeatherWidgetWorker.DEFAULT_LAT,
            WeatherWidgetWorker.DEFAULT_LON,
        )

        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = centerDate,
            today = today,
            weatherByDate = forecasts.associateBy { f -> LocalDate.ofEpochDay(f.targetDate / (24 * 60 * 60 * 1000L)) },
            forecastSnapshots = emptyMap(),
            numColumns = 5,
            displaySource = displaySource,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            stateManager = stateManager,
            appWidgetId = testWidgetId,
        )

        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)

        DailyClickHandlerFactory.setupGraphDayClickHandlers(
            context, views, testWidgetId, now, days,
            WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON,
            displaySource, 5,
        )

        return views
    }

    private fun applyToRoot(views: RemoteViews): View {
        val root = FrameLayout(context)
        val applied = views.apply(context, root as ViewGroup)
        applied.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        applied.layout(0, 0, 1000, 1000)
        return applied
    }
}
