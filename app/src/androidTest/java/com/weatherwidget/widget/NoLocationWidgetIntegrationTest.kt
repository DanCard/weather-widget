package com.weatherwidget.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.testutil.IsolatedIntegrationTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end cover for the state this change set exists to create: a widget with **no resolvable
 * location at all**.
 *
 * The app used to answer that state with Google HQ — it fetched live weather for Mountain View and
 * labelled it as the user's own. The correct behaviour is an explicit dead end: paint
 * "No location — tap to set", fetch nothing, and leave the GPS auto-heal eligible to rescue it.
 *
 * Deliberately seeds **no** forecasts. `ActiveLocationResolver.resolve` falls back to the latest
 * cached weather, so any seeded row would supply a location and defeat the test.
 */
@RunWith(AndroidJUnit4::class)
class NoLocationWidgetIntegrationTest : IsolatedIntegrationTest("no_location") {

    private lateinit var host: AppWidgetHost
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /**
     * Per-widget coordinates live in ConfigActivity.PREFS_NAME, which [IsolatedIntegrationTest] does
     * not isolate — it only clears WidgetStateManager's own file. Any widget already on the device's
     * home screen therefore supplies a stored location and defeats this test. Snapshot and restore
     * rather than clear, so a real device's widgets are never damaged by running the suite.
     */
    private var savedLocationPrefs: Map<String, Float> = emptyMap()

    @Before
    override fun setup() {
        super.setup()
        grantBindPermission()
        savedLocationPrefs = clearStoredWidgetLocations()
        ActiveLocationResolver.clear(context)
        host = AppWidgetHost(context, TEST_HOST_ID)
    }

    @After
    override fun cleanup() {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            host.deleteAppWidgetId(widgetId)
            widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
        restoreStoredWidgetLocations(savedLocationPrefs)
        super.cleanup()
    }

    private fun locationPrefs() =
        com.weatherwidget.util.SharedPreferencesUtil.getPrefs(
            context,
            com.weatherwidget.ui.ConfigActivity.PREFS_NAME,
        )

    private fun clearStoredWidgetLocations(): Map<String, Float> {
        val prefs = locationPrefs()
        val coordinateKeys = prefs.all.keys.filter {
            it.startsWith(com.weatherwidget.ui.ConfigActivity.KEY_LAT_PREFIX) ||
                it.startsWith(com.weatherwidget.ui.ConfigActivity.KEY_LON_PREFIX)
        }
        val saved = coordinateKeys.associateWith { prefs.getFloat(it, Float.NaN) }
        prefs.edit().apply { coordinateKeys.forEach(::remove) }.commit()
        return saved
    }

    private fun restoreStoredWidgetLocations(saved: Map<String, Float>) {
        if (saved.isEmpty()) return
        locationPrefs().edit().apply {
            saved.forEach { (key, value) -> if (!value.isNaN()) putFloat(key, value) }
        }.commit()
    }

    @Test
    fun noLocation_paintsTheNoLocationStateAndFetchesNothing() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, WeatherWidgetProvider::class.java)

        // Stage 1: nothing anywhere resolves to a location.
        assertNull(
            "test precondition: no canonical active location",
            ActiveLocationResolver.current(context),
        )
        assertNull(
            "resolve() must answer null rather than falling back to a coordinate",
            ActiveLocationResolver.resolve(context, WidgetStateManager(context), db.forecastDao()),
        )

        // Stage 2: add the widget exactly as a launcher does on drop.
        widgetId = host.allocateAppWidgetId()
        assertTrue(
            "bindAppWidgetIdIfAllowed refused widget $widgetId",
            manager.bindAppWidgetIdIfAllowed(widgetId, provider, placementOptions()),
        )

        // Stage 3: the paint that follows the bind must be the no-location state. WIDGET_PAINT is
        // the breadcrumb every render path emits; state=no_location is unique to this one.
        val deadline = System.currentTimeMillis() + RENDER_TIMEOUT_MS
        var paintedNoLocation = false
        while (!paintedNoLocation && System.currentTimeMillis() < deadline) {
            paintedNoLocation = db.appLogDao().getLogsByTag("NO_LOCATION", 50).isNotEmpty()
            if (!paintedNoLocation) delay(POLL_INTERVAL_MS)
        }

        if (!paintedNoLocation) {
            val trace = (
                db.appLogDao().getLogsByTag("WIDGET_LIFECYCLE", 50) +
                    db.appLogDao().getLogsByTag("SYNC_START", 50) +
                    db.appLogDao().getLogsByTag("WIDGET_PUSH", 50)
                )
                .sortedBy { it.timestamp }
                .joinToString("\n") { "${it.getFormattedTime()} ${it.tag}: ${it.message}" }
            org.junit.Assert.fail(
                "Widget $widgetId bound with no location but never reached the no-location state " +
                    "within ${RENDER_TIMEOUT_MS}ms. Trace:\n$trace",
            )
        }

        // Stage 4: and nothing was fetched. A SYNC_SUCCESS row would mean the worker went to the
        // network for a location nobody chose — the exact bug this change removes.
        assertTrue(
            "no weather fetch may be attempted without a location",
            db.appLogDao().getLogsByTag("SYNC_SUCCESS", 50).isEmpty(),
        )
        assertTrue(
            "no observation backfill may be attempted without a location",
            db.appLogDao().getLogsByTag("OBS_HOURLY_BACKFILL_RUN", 50).isEmpty(),
        )

        // Stage 5: the widget stays heal-eligible, so a later GPS fix rescues it automatically.
        assertTrue(
            "an unset widget must remain eligible for the GPS auto-heal",
            com.weatherwidget.ui.LocationUpdater.allWidgetsAtDefault(context),
        )
        assertFalse(
            "no coordinate may have been written as a side effect",
            ActiveLocationResolver.current(context) != null,
        )
    }

    private fun grantBindPermission() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = automation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        )
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /** Roughly a 2x3-cell placement so the graphical layout path is exercised. */
    private fun placementOptions() = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 340)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220)
    }

    companion object {
        private const val TEST_HOST_ID = 0x7E58
        private const val RENDER_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 250L
    }
}
