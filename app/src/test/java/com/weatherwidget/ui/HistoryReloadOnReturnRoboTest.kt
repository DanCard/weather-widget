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
 * data ran on top of History of Forecasts, which kept the pre-refresh rows (Age `1h` two minutes after).
 * Back on top it must reload once if stored data was written meanwhile, and not otherwise. A reload logs
 * one `SCREEN_RELOAD` line, which is what the test counts.
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

    @After
    fun tearDown() {
        db.close()
    }

    private fun openHistory(): ActivityController<ForecastHistoryActivity> {
        val intent = Intent(context, ForecastHistoryActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 101)
            putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, "2030-01-01")
            putExtra(ForecastHistoryActivity.EXTRA_LAT, 37.0)
            putExtra(ForecastHistoryActivity.EXTRA_LON, -122.0)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }
        val controller = Robolectric.buildActivity(ForecastHistoryActivity::class.java, intent)
        val activity = controller.get()
        activity.forecastDao = mockk(relaxed = true)
        activity.dailyHistoryDao = mockk(relaxed = true)
        activity.appLogDao = appLogDao
        activity.accuracyCalculator = mockk<AccuracyCalculator>().also { calculator ->
            coEvery { calculator.calculateComparison(any(), any(), any()) } returns ComparisonStatistics(
                bySource = emptyMap(), periodStart = "2025-01-01", periodEnd = "2025-01-30",
            )
        }
        controller.setup()
        return controller
    }

    /** `AppLogDao.log` mirrors every row to logcat, which ShadowLog keeps. */
    private fun reloadLines(): Int = org.robolectric.shadows.ShadowLog.getLogsForTag(StoredDataSeen.LOG_TAG).size

    /** The reloads themselves: `loadData` logs one "Found N snapshots" line per load. */
    private fun loadsSoFar() = org.robolectric.shadows.ShadowLog.getLogsForTag("ForecastHistoryActivity").count { it.msg.startsWith("Found ") }

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
        StoredDataVersion.process.bump("test:settings_refresh")
        controller.pause().resume()
        assertEquals("a return after a write reloads once", 1, settledReloadLines(expected = 1))
        assertEquals("and that is a real load: the open's plus the reload", 2, loadsSoFar())

        // And it is current again: the next return reloads nothing more.
        controller.pause().resume()
        assertEquals("the reload made it current", 1, settledReloadLines(expected = 1))
        assertEquals(2, loadsSoFar())
        assertTrue(
            org.robolectric.shadows.ShadowLog.getLogsForTag(StoredDataSeen.LOG_TAG).single().msg
                .contains("screen=history reason=stored_data_changed"),
        )
    }
}
