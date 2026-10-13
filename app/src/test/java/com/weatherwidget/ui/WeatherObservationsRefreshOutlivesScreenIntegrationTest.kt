package com.weatherwidget.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.UserRefreshRunner
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Observations Refresh runs through the Hilt-provided [UserRefreshRunner] on the app scope, so
 * closing the screen mid-fetch no longer discards it. Fold 4, 2026-10-13: Refresh at 14:27:49, the
 * screen closed at 14:27:54, `CURR_FETCH_ERROR … Job was cancelled`, nothing stored
 * (plans/261013-observations-refresh-survives-closing-the-screen.md).
 *
 * Real components: the Hilt-injected activity and runner, in-memory Room for the log rows, the
 * widget-repaint broadcast. Only the network call is stubbed (`refreshFetchOverride`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherObservationsRefreshOutlivesScreenIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: WeatherDatabase
    private val widgetId = 4302
    private val lat = 37.416885
    private val lon = -122.088776

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)
        WidgetStateManager(context).setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", lon.toFloat())
            .commit()
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    @Test
    fun `closing the screen mid-fetch keeps the fetch - it lands, logs completed and repaints the widget`() {
        val release = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Pair<Double, Double>>()
        val scenario = launchWithFetch { la, lo, _ ->
            release.await()
            landed.complete(la to lo)
        }
        scenario.onActivity { it.findViewById<View>(R.id.refresh_button).performClick() }
        shadowOf(Looper.getMainLooper()).idle()

        scenario.close() // the user closes Observations while the fetch is in flight
        release.complete(Unit)

        val (la, lo) = runBlocking { kotlinx.coroutines.withTimeout(5_000) { landed.await() } }
        assertEquals(lat, la, 1e-4)
        assertEquals(lon, lo, 1e-4)
        val rows = waitForRows { it.any { r -> r.contains("outcome=completed") } }
        assertTrue(rows.toString(), rows.any { it == "origin=user_observations_screen outcome=waiting" })
        assertTrue(rows.toString(), rows.any { it == "origin=user_observations_screen outcome=completed" })

        val repaint = shadowOf(context as Application).broadcastIntents.lastOrNull {
            it.action == WidgetActions.ACTION_REFRESH
        }
        assertTrue("the detached fetch must repaint the widget that opened the screen", repaint != null)
        assertEquals(widgetId, repaint!!.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertTrue(repaint.getBooleanExtra(WidgetActions.EXTRA_UI_ONLY, false))
    }

    @Test
    fun `with the screen open the refresh finishes attached - button back, no detach row`() {
        var calls = 0
        val scenario = launchWithFetch { _, _, _ -> calls++ }
        scenario.onActivity { it.findViewById<View>(R.id.refresh_button).performClick() }
        waitUntil { calls == 1 && scenario.buttonEnabled() }

        assertEquals(1, calls)
        assertTrue(runBlocking { database.appLogDao().getLogsByTag(UserRefreshRunner.TAG, 10) }.isEmpty())
        scenario.close()
    }

    private fun launchWithFetch(
        fetch: suspend (Double, Double, WeatherSource) -> Unit,
    ): ActivityScenario<WeatherObservationsActivity> {
        val intent = Intent(context, WeatherObservationsActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        return ActivityScenario.launch<WeatherObservationsActivity>(intent).also { scenario ->
            scenario.onActivity { activity ->
                activity.ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
                activity.forceRefreshDisplayedSource = { _, _ -> } // keep the stale repair off the network
                activity.refreshFetchOverride = fetch
            }
        }
    }

    private fun ActivityScenario<WeatherObservationsActivity>.buttonEnabled(): Boolean {
        var enabled = false
        onActivity { enabled = it.findViewById<View>(R.id.refresh_button).isEnabled }
        return enabled
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun waitForRows(done: (List<String>) -> Boolean): List<String> {
        var rows = emptyList<String>()
        waitUntil {
            rows = runBlocking { database.appLogDao().getLogsByTag(UserRefreshRunner.TAG, 10) }.map { it.message }
            done(rows)
        }
        return rows
    }
}
