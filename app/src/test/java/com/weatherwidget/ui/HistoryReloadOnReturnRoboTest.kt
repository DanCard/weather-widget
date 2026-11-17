package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.ComparisonStatistics
import com.weatherwidget.shared.util.StoredDataSeen
import com.weatherwidget.shared.util.StoredDataVersion
import com.weatherwidget.stats.AccuracyCalculator
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * plans/261113-android-data-screens-reload-after-settings-refresh.md. Fold 4 2026-11-13: Settings → Refresh
 * data ran on top of Forecast Comparison, which kept the pre-refresh rows (Age `1h` two minutes after).
 * Back on top it must reload once if stored data was written meanwhile, and not otherwise. A reload logs
 * one `SCREEN_RELOAD` line, which is what the test counts.
 *
 * plans/261116-comparison-screen-reloads-while-open.md: Fold 4 2026-11-16, Google and Open-Meteo's retry were
 * stored 0.5 s and 75 s after the screen's last load while it stayed open, and Age read `3h` until it was left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class HistoryReloadOnReturnRoboTest {
    private lateinit var context: Context
    private lateinit var db: com.weatherwidget.data.local.WeatherDatabase
    private lateinit var appLogDao: AppLogDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager(context).setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        org.robolectric.shadows.ShadowLog.stream = System.out
        db = TestDatabase.create()
        appLogDao = db.appLogDao()
    }

    /** Opened screens, destroyed after each test: a resumed one left over would reload on the next test's bump. */
    private val opened = mutableListOf<ActivityController<ForecastComparisonActivity>>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.pause().stop().destroy() }.onFailure { e -> println("teardown: ${e.message}") } }
        db.close()
    }

    private fun openHistory(
        configure: (ForecastComparisonActivity) -> Unit = {},
    ): ActivityController<ForecastComparisonActivity> {
        val intent = Intent(context, ForecastComparisonActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 101)
            putExtra(ForecastComparisonActivity.EXTRA_TARGET_DATE, "2030-01-01")
            putExtra(ForecastComparisonActivity.EXTRA_LAT, 37.0)
            putExtra(ForecastComparisonActivity.EXTRA_LON, -122.0)
            putExtra(ForecastComparisonActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }
        val controller = Robolectric.buildActivity(ForecastComparisonActivity::class.java, intent)
        val activity = controller.get()
        activity.forecastDao = mockk(relaxed = true)
        activity.dailyHistoryDao = mockk(relaxed = true)
        activity.appLogDao = appLogDao
        activity.accuracyCalculator = mockk<AccuracyCalculator>().also { calculator ->
            coEvery { calculator.calculateComparison(any(), any(), any()) } returns ComparisonStatistics(
                bySource = emptyMap(), periodStart = "2025-01-01", periodEnd = "2025-01-30",
            )
        }
        configure(activity)
        controller.setup()
        opened += controller
        return controller
    }

    /** `AppLogDao.log` mirrors every row to logcat, which ShadowLog keeps. */
    private fun reloadLines(): Int = org.robolectric.shadows.ShadowLog.getLogsForTag(StoredDataSeen.LOG_TAG).size

    /** The reloads themselves: `loadData` logs one "Found N snapshots" line per load. */
    private fun loadsSoFar() = org.robolectric.shadows.ShadowLog.getLogsForTag("ForecastComparisonActivity").count { it.msg.startsWith("Found ") }

    /** The log is written from a launched coroutine: wait for the expected count, then let a stray extra one land. */
    private fun settledReloadLines(expected: Int): Int {
        val end = System.currentTimeMillis() + 5_000
        while (reloadLines() < expected && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper() // the launch runs on Dispatchers.Main
            Thread.sleep(20)
        }
        Thread.sleep(400)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        return reloadLines()
    }

    @Test
    fun `returning with stored data written meanwhile reloads once, returning with none reloads nothing`() {
        val controller = openHistory()
        assertEquals("opening is a load, not a reload on return", 0, settledReloadLines(expected = 0))

        // Settings opened on top and closed again, nothing written: no reload.
        controller.pause().resume()
        assertEquals("a return with no write reloads nothing", 0, settledReloadLines(expected = 0))

        // Settings → Refresh data stored rows while History was underneath: reload on return.
        controller.pause()
        StoredDataVersion.process.bump("test:settings_refresh")
        controller.resume()
        assertEquals("a return after a write reloads once", 1, settledReloadLines(expected = 1))
        assertEquals("and that is a real load: the open's plus the reload", 2, loadsSoFar())

        // And it is current again: the next return reloads nothing more.
        controller.pause().resume()
        assertEquals("the reload made it current", 1, settledReloadLines(expected = 1))
        assertEquals(2, loadsSoFar())
        assertTrue(
            org.robolectric.shadows.ShadowLog.getLogsForTag(StoredDataSeen.LOG_TAG).single().msg
                .contains("screen=history reason=stored_data_changed trigger=resume"),
        )
    }

    @Test
    fun `a write landing while the screen stays open reloads it, each write once`() {
        openHistory()
        assertEquals(0, settledReloadLines(expected = 0))
        assertEquals(1, loadsSoFar())

        StoredDataVersion.process.bump("test:forecast_persist source=GOOGLE_WEATHER") // 15:55:46
        assertEquals("the old screen stayed on its pre-fetch rows", 1, settledReloadLines(expected = 1))
        assertEquals(2, loadsSoFar())

        StoredDataVersion.process.bump("test:forecast_persist source=OPEN_METEO") // 15:56:59 retry
        assertEquals("not debounced: the late source reloads again", 2, settledReloadLines(expected = 2))
        assertEquals(3, loadsSoFar())
        assertTrue(
            org.robolectric.shadows.ShadowLog.getLogsForTag(StoredDataSeen.LOG_TAG)
                .all { it.msg.endsWith("trigger=live") },
        )
    }

    @Test
    fun `writes while paused under another screen reload nothing until the return, then once`() {
        val controller = openHistory()
        controller.pause()
        StoredDataVersion.process.bump("test:a")
        StoredDataVersion.process.bump("test:b")
        assertEquals("paused: no work behind Settings", 0, settledReloadLines(expected = 0))
        controller.resume()
        assertEquals("one catch-up for both writes", 1, settledReloadLines(expected = 1))
        assertEquals(2, loadsSoFar())
    }

    @Test
    fun `a newer load cancels the slower one before it paints`() {
        val controller = openHistory()
        assertEquals(0, settledReloadLines(expected = 0))
        val loadsBefore = loadsSoFar()
        // Set after setup(): Hilt injects in onCreate, replacing a field set before it.
        val slowDay = java.time.LocalDate.parse("2030-01-01").toEpochDay() * 86_400_000L
        controller.get().forecastDao = mockk<com.weatherwidget.data.local.ForecastDao>(relaxed = true).also { dao ->
            coEvery { dao.getForecastEvolution(any(), any(), any()) } coAnswers {
                // The source-switch reload (Jan 1) is slow; the next-day tap (Jan 2) right after it is fast.
                if (firstArg<Long>() == slowDay) kotlinx.coroutines.delay(1_500)
                emptyList()
            }
        }
        // Source button: reloads Jan 1 (slow), then the next-day arrow loads Jan 2 (fast) while it reads.
        controller.get().findViewById<android.view.View>(com.weatherwidget.R.id.api_source_button).performClick()
        controller.get().findViewById<android.view.View>(com.weatherwidget.R.id.next_day_button).performClick()
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(50)
        }
        assertEquals(
            "only the newer (next-day) load got as far as showing rows; the old code showed both",
            listOf("Found 0 snapshots for 2030-01-02"),
            org.robolectric.shadows.ShadowLog.getLogsForTag("ForecastComparisonActivity")
                .filter { it.msg.startsWith("Found ") }.drop(loadsBefore).map { it.msg },
        )
    }
}
