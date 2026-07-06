package com.weatherwidget.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WeatherWidgetWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check of the heal propagation path: [LocationUpdater.applyToAllWidgets] must write
 * every widget's configured location, record the POI, and enqueue a force refresh. Synthetic
 * widget ids are passed explicitly so the test never touches a real widget's configuration, and
 * test mode routes all pref files to their `_test_default` variants (see SharedPreferencesUtil).
 */
@RunWith(AndroidJUnit4::class)
class LocationUpdaterIntegrationTest : IsolatedIntegrationTest("location_updater") {

    private val testIds = intArrayOf(9001, 9002)

    @Before
    fun clearPrefs() {
        widgetPrefs().edit().clear().commit()
        weatherPrefs().edit().clear().commit()
    }

    @After
    fun cleanupPrefsAndWork() {
        widgetPrefs().edit().clear().commit()
        weatherPrefs().edit().clear().commit()
        WorkManager.getInstance(context).cancelAllWorkByTag(WeatherWidgetWorker::class.java.name)
    }

    private fun widgetPrefs() = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)

    private fun weatherPrefs() = SharedPreferencesUtil.getPrefs(context, "weather_prefs")

    @Test
    fun applyToAllWidgets_writesConfiguredLocationForEveryWidget() {
        LocationUpdater.applyToAllWidgets(context, 30.2672, -97.7431, "Austin", testIds)

        for (id in testIds) {
            assertEquals(30.2672f, widgetPrefs().getFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", Float.NaN), 0.0001f)
            assertEquals(-97.7431f, widgetPrefs().getFloat("${ConfigActivity.KEY_LON_PREFIX}$id", Float.NaN), 0.0001f)
        }
        assertEquals(
            "Austin|30.2672|-97.7431",
            weatherPrefs().getString("historical_pois", null),
        )
    }

    @Test
    fun applyToAllWidgets_dedupesPoiAndCapsHistoryAtFive() {
        weatherPrefs().edit()
            .putString(
                "historical_pois",
                "Old|9.0|9.0;A|1.0|1.0;B|2.0|2.0;C|3.0|3.0;D|4.0|4.0",
            )
            .commit()

        // 5 seeded + 1 new = 6, so the cap must drop the oldest entry.
        LocationUpdater.applyToAllWidgets(context, 30.2672, -97.7431, "Austin", testIds)
        var pois = weatherPrefs().getString("historical_pois", null)!!.split(";")
        assertEquals(5, pois.size)
        assertEquals("Austin|30.2672|-97.7431", pois.last())
        assertFalse(pois.any { it.startsWith("Old|") })

        // Re-applying the same POI must dedupe, not grow the list or duplicate the label.
        LocationUpdater.applyToAllWidgets(context, 30.2672, -97.7431, "Austin", testIds)
        pois = weatherPrefs().getString("historical_pois", null)!!.split(";")
        assertEquals(5, pois.size)
        assertEquals("Austin|30.2672|-97.7431", pois.last())
        assertEquals(1, pois.count { it.startsWith("Austin|") })
    }

    @Test
    fun applyToAllWidgets_enqueuesForceRefreshWork() {
        LocationUpdater.applyToAllWidgets(context, 30.2672, -97.7431, "Austin", testIds)

        // OneTimeWorkRequestBuilder auto-tags requests with the worker's FQCN. The enqueued worker
        // no-ops on the device because the test runner enables WeatherDatabase testing mode.
        val infos = WorkManager.getInstance(context)
            .getWorkInfosByTag(WeatherWidgetWorker::class.java.name)
            .get()
        assertFalse("expected a force-refresh work request to be enqueued", infos.isEmpty())
        assertTrue(infos.any { it.state != WorkInfo.State.CANCELLED })
    }
}
