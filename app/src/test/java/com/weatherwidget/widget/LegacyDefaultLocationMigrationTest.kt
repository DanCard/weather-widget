package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.SharedPreferencesUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The upgrade path is the regression that fresh-install testing cannot see: an install that already
 * carries the Google-HQ sentinel would, without this migration, read it as a deliberately-chosen
 * location — auto-heal disabled, no-location gate never fires, permanently pinned to Mountain View.
 */
@Category(LongDuration::class)
class LegacyDefaultLocationMigrationTest : RobolectricTest() {

    private lateinit var context: Context

    // The retired sentinel. Duplicated from the migration on purpose: if someone changes the
    // constant there, this test must fail rather than silently follow it.
    private val legacyLat = 37.4220
    private val legacyLon = -122.0841

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ActiveLocationResolver.clearForTesting(context)
        widgetPrefs().edit().clear().commit()
        weatherPrefs().edit().clear().commit()
    }

    private fun widgetPrefs() = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
    private fun weatherPrefs() = SharedPreferencesUtil.getPrefs(context, "weather_prefs")

    private fun seedWidgetLocation(widgetId: Int, lat: Double, lon: Double) {
        widgetPrefs().edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", lon.toFloat())
            .commit()
    }

    private fun storedWidgetLocation(widgetId: Int): Pair<Float, Float>? {
        val prefs = widgetPrefs()
        val latKey = "${ConfigActivity.KEY_LAT_PREFIX}$widgetId"
        val lonKey = "${ConfigActivity.KEY_LON_PREFIX}$widgetId"
        if (!prefs.contains(latKey) || !prefs.contains(lonKey)) return null
        return prefs.getFloat(latKey, Float.NaN) to prefs.getFloat(lonKey, Float.NaN)
    }

    @Test
    fun `clears the sentinel from both the active location and per-widget prefs`() {
        ActiveLocationResolver.persist(context, legacyLat, legacyLon)
        seedWidgetLocation(11, legacyLat, legacyLon)
        seedWidgetLocation(22, legacyLat, legacyLon)

        val outcome = LegacyDefaultLocationMigration.runIfNeeded(context)

        assertFalse(outcome.alreadyRun)
        assertTrue(outcome.clearedActiveLocation)
        assertEquals(listOf(11, 22), outcome.clearedWidgetIds.sorted())
        assertEquals(3, outcome.clearedCount)
        assertNull("active location must be unset", ActiveLocationResolver.current(context))
        assertNull(storedWidgetLocation(11))
        assertNull(storedWidgetLocation(22))
    }

    /**
     * The reason the comparison is [com.weatherwidget.data.local.LocationMatch.sameSite] and not `==`:
     * coordinates that round-tripped through 3-dp quantization or a Float pref are near, not equal.
     * HourlyObservationBackfill shipped that exact bug once already.
     */
    @Test
    fun `clears a quantized copy of the sentinel that would not compare equal`() {
        seedWidgetLocation(7, 37.422, -122.084)

        val outcome = LegacyDefaultLocationMigration.runIfNeeded(context)

        assertEquals(listOf(7), outcome.clearedWidgetIds)
        assertNull(storedWidgetLocation(7))
    }

    @Test
    fun `leaves a real user location untouched`() {
        val bostonLat = 42.3601
        val bostonLon = -71.0589
        ActiveLocationResolver.persist(context, bostonLat, bostonLon)
        seedWidgetLocation(5, bostonLat, bostonLon)

        val outcome = LegacyDefaultLocationMigration.runIfNeeded(context)

        assertFalse(outcome.clearedActiveLocation)
        assertTrue(outcome.clearedWidgetIds.isEmpty())
        val active = ActiveLocationResolver.current(context)
        assertNotNull(active)
        assertEquals(bostonLat, active!!.first, 0.001)
        assertEquals(bostonLon, active.second, 0.001)
        assertNotNull(storedWidgetLocation(5))
    }

    /**
     * A user who genuinely lives near Google HQ loses their setting once — an accepted, bounded cost
     * of the migration, and FOLLOW_DEVICE re-heals them. What must never happen is the reverse:
     * treating proximity as "unset" in the *steady-state* heal check. That criterion is absent/NaN
     * only; see LocationUpdater.allWidgetsAtDefault.
     */
    @Test
    fun `runs exactly once so a later real location near HQ survives`() {
        seedWidgetLocation(3, legacyLat, legacyLon)
        LegacyDefaultLocationMigration.runIfNeeded(context)
        assertNull(storedWidgetLocation(3))

        seedWidgetLocation(3, legacyLat, legacyLon)
        val second = LegacyDefaultLocationMigration.runIfNeeded(context)

        assertTrue("second run must short-circuit", second.alreadyRun)
        assertEquals(0, second.clearedCount)
        assertNotNull("a location chosen after the migration must survive", storedWidgetLocation(3))
    }

    @Test
    fun `reports through prefs so the worker can persist it without a startup database open`() {
        seedWidgetLocation(9, legacyLat, legacyLon)
        LegacyDefaultLocationMigration.runIfNeeded(context)

        val report = LegacyDefaultLocationMigration.consumePendingReport(context)

        assertNotNull(report)
        assertTrue(report!!, report.contains("cleared=1"))
        assertTrue(report, report.contains("widgets=9"))
        assertNull("report must be consumed once", LegacyDefaultLocationMigration.consumePendingReport(context))
    }

    @Test
    fun `leaves no report when there was nothing to clear`() {
        LegacyDefaultLocationMigration.runIfNeeded(context)

        assertNull(LegacyDefaultLocationMigration.consumePendingReport(context))
    }

    @Test
    fun `clears orphaned coordinates left behind by a removed widget`() {
        // Not currently bound to any widget, but a new widget can be assigned id 42 and inherit it.
        seedWidgetLocation(42, legacyLat, legacyLon)

        val outcome = LegacyDefaultLocationMigration.runIfNeeded(context)

        assertEquals(listOf(42), outcome.clearedWidgetIds)
    }
}
