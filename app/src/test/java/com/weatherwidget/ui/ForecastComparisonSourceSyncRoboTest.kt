package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.stats.AccuracyCalculator
import com.weatherwidget.shared.stats.ComparisonStatistics
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



/**
 * Robolectric test verifying that cycling the API in ForecastComparisonActivity
 * correctly synchronizes the choice back to the widget and triggers a UI update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ForecastComparisonSourceSyncRoboTest {

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager

    /** Opened activities, closed after each test: a resumed leftover keeps reloading on later tests' writes. */
    private val opened = mutableListOf<org.robolectric.android.controller.ActivityController<ForecastComparisonActivity>>()

    @org.junit.After
    fun closeOpenedActivities() {
        opened.forEach { runCatching { it.pause().stop().destroy() }.onFailure { e -> println("teardown: ${e.message}") } }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        stateManager = WidgetStateManager(context)
    }

    @Test
    fun `cycling API in activity switches the widget source through the shared runner`() {
        val testWidgetId = 101

        // Initial setup: Ensure visible sources exist
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        stateManager.setVisibleSourcesOrder(visibleSources)
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.NWS)
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))

        // Launch activity with specific widget ID and history extras
        val intent = Intent(context, ForecastComparisonActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
            putExtra(ForecastComparisonActivity.EXTRA_TARGET_DATE, "2030-01-01")
            putExtra(ForecastComparisonActivity.EXTRA_LAT, 37.0)
            putExtra(ForecastComparisonActivity.EXTRA_LON, -122.0)
            putExtra(ForecastComparisonActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }

        // We use buildActivity().get() to get the activity instance
        // ForecastComparisonActivity uses Hilt for @Inject fields, so we must mock them 
        // if we are not running a full Hilt test.
        val controller = Robolectric.buildActivity(ForecastComparisonActivity::class.java, intent)
        opened += controller
        val activity = controller.get()
        
        // Manual injection of required DAOs (mocked) to avoid Hilt @Inject failures during onCreate
        activity.forecastDao = mockk(relaxed = true)
        activity.dailyHistoryDao = mockk(relaxed = true)
        activity.accuracyCalculator = mockk<AccuracyCalculator>().also { calculator ->
            coEvery { calculator.calculateComparison(any(), any(), any()) } returns ComparisonStatistics(
                bySource = emptyMap(),
                periodStart = "2025-01-01",
                periodEnd = "2025-01-30",
            )
        }

        controller.setup()

        // The pill switches the widget the way the API button does (plans/261119-sync-data-screen-sources-with-main.md):
        // through SourceSwitchRunner on the application scope, so wait for it to land.
        activity.findViewById<View>(R.id.api_source_button).performClick()
        val db = com.weatherwidget.data.local.WeatherDatabase.getDatabase(context)
        fun comparisonRows() = runBlocking { db.sourceViewDao().getSince(0L) }.filter { it.triggerKind == "COMPARISON" }
        fun e2eDone() = runBlocking { db.appLogDao().getLogsByTag("SOURCE_SWITCH_E2E", 20) }
            .filter { it.message.contains("trigger=COMPARISON") && it.message.contains("phase=done") }
        val end = System.currentTimeMillis() + 10_000
        while ((stateManager.getCurrentDisplaySource(testWidgetId) != WeatherSource.OPEN_METEO || comparisonRows().isEmpty() || e2eDone().isEmpty()) &&
            System.currentTimeMillis() < end
        ) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        println("HistoryActivitySync: source=${stateManager.getCurrentDisplaySource(testWidgetId)} rows=${comparisonRows()} e2e=${e2eDone().map { it.message }}")

        assertEquals(
            "Manager source should have updated to the next visible source",
            WeatherSource.OPEN_METEO,
            stateManager.getCurrentDisplaySource(testWidgetId),
        )
        assertEquals("counted once as a Forecast Comparison switch", listOf(WeatherSource.OPEN_METEO.id to 1),
            comparisonRows().map { it.sourceId to it.switches })
        assertTrue("the runner logged the switch end to end", e2eDone().isNotEmpty())
        assertEquals("the screen shows the new source", "Meteo",
            (activity.findViewById<View>(R.id.api_source_button) as android.widget.TextView).text.toString())
    }

    @Test
    fun `activity launched with silurian source keeps silur selected`() {
        val testWidgetId = 202
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.SILURIAN, WeatherSource.OPEN_METEO))
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.NWS)

        val intent = Intent(context, ForecastComparisonActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
            putExtra(ForecastComparisonActivity.EXTRA_TARGET_DATE, "2030-01-01")
            putExtra(ForecastComparisonActivity.EXTRA_LAT, 37.0)
            putExtra(ForecastComparisonActivity.EXTRA_LON, -122.0)
            putExtra(ForecastComparisonActivity.EXTRA_SOURCE, WeatherSource.SILURIAN.displayName)
        }

        val controller = Robolectric.buildActivity(ForecastComparisonActivity::class.java, intent)

        opened += controller
        val activity = controller.get()
        activity.forecastDao = mockk(relaxed = true)
        activity.dailyHistoryDao = mockk(relaxed = true)
        activity.accuracyCalculator = mockk<AccuracyCalculator>().also { calculator ->
            coEvery { calculator.calculateComparison(any(), any(), any()) } returns ComparisonStatistics(
                bySource = emptyMap(),
                periodStart = "2025-01-01",
                periodEnd = "2025-01-30",
            )
        }

        controller.setup()

        assertEquals("Silur", activity.findViewById<View>(R.id.api_source_button).let { it as android.widget.TextView }.text.toString())
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }
}
