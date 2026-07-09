package com.weatherwidget.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.R
import com.weatherwidget.testutil.IsolatedIntegrationTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The widget-add configuration handshake, end to end: ConfigActivity launched the way a
 * launcher does (APPWIDGET_CONFIGURE with a widget id).
 *
 * Contract under test (2026-07-08):
 * 1. The auto-started location flow must resolve in bounded time (LocationFixFlow timeouts) —
 *    an unbounded `getCurrentLocation` once left the screen dead for 30+ seconds.
 * 2. The screen must NOT save-and-finish on its own — an auto-exiting version yanked the
 *    screen away from users who wanted to search. It waits with a re-enabled GPS button
 *    ("Use this location" when the fix resolved, the original label when it didn't).
 * 3. Tapping the button then completes the handshake: RESULT_OK with the widget id echoed
 *    back, location persisted, and a CONFIG GPS_FIX breadcrumb recording the resolving stage.
 */
@RunWith(AndroidJUnit4::class)
class ConfigActivityAddFlowTest : IsolatedIntegrationTest("config_add_flow") {

    @Before
    override fun setup() {
        super.setup()
        // The auto-started flow must not stall on permission dialogs.
        grantLocationPermissions()
        clearSavedLocation()
    }

    @After
    override fun cleanup() {
        clearSavedLocation()
        super.cleanup()
    }

    @Test
    fun configScreen_autoFillsWithoutExiting_confirmTapCompletesHandshake() {
        val intent = Intent(context, ConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, TEST_WIDGET_ID)

        val startMs = SystemClock.elapsedRealtime()
        val scenario = ActivityScenario.launchActivityForResult<ConfigActivity>(intent)
        try {
            // 1. Auto-fill resolves in bounded time: the GPS button leaves its in-flight
            //    "Getting location…" state and becomes tappable again.
            val deadline = startMs + AUTO_FILL_BOUND_MS
            var buttonEnabled = false
            while (!buttonEnabled && SystemClock.elapsedRealtime() < deadline) {
                if (scenario.state == Lifecycle.State.DESTROYED) break
                scenario.onActivity { activity ->
                    buttonEnabled = activity.findViewById<Button>(R.id.use_gps_button).isEnabled
                }
                if (!buttonEnabled) SystemClock.sleep(POLL_INTERVAL_MS)
            }
            assertNotEquals(
                "Config screen must not exit without a user action",
                Lifecycle.State.DESTROYED,
                scenario.state,
            )
            assertTrue(
                "GPS button still stuck in-flight after ${AUTO_FILL_BOUND_MS}ms — " +
                    "LocationFixFlow did not resolve in bounded time",
                buttonEnabled,
            )

            // 2. Still no auto-exit once resolved: the screen belongs to the user now.
            SystemClock.sleep(2_000)
            assertNotEquals(
                "Config screen auto-exited after the fix resolved — it must wait for a confirm tap",
                Lifecycle.State.DESTROYED,
                scenario.state,
            )

            // 3. The confirm tap completes the add handshake. (With a resolved fix the button
            //    reads "Use this location"; on a fix-less device it falls back to the manual
            //    GPS flow — both must end in RESULT_OK.)
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.use_gps_button).performClick()
            }

            val result = scenario.result // blocks until the activity finishes
            assertEquals(
                "Confirm tap must complete the add handshake with RESULT_OK",
                Activity.RESULT_OK,
                result.resultCode,
            )
            assertEquals(
                "RESULT_OK must echo the widget id back to the launcher",
                TEST_WIDGET_ID,
                result.resultData?.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                ),
            )

            val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
            assertTrue(
                "Widget location must be persisted for widget $TEST_WIDGET_ID",
                prefs.contains("${ConfigActivity.KEY_LAT_PREFIX}$TEST_WIDGET_ID"),
            )

            val gpsFixLogged = runBlocking {
                db.appLogDao().getLogsByTag("CONFIG", 20)
                    .any { it.message.contains("GPS_FIX outcome=") && it.message.contains("widget=$TEST_WIDGET_ID") }
            }
            assertTrue("CONFIG GPS_FIX breadcrumb must record which stage resolved", gpsFixLogged)
        } finally {
            scenario.close()
        }
    }

    private fun grantLocationPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        ).forEach { permission ->
            val pfd = automation.executeShellCommand("pm grant ${context.packageName} $permission")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    private fun clearSavedLocation() {
        com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
            .edit()
            .remove("${ConfigActivity.KEY_LAT_PREFIX}$TEST_WIDGET_ID")
            .remove("${ConfigActivity.KEY_LON_PREFIX}$TEST_WIDGET_ID")
            .commit()
    }

    companion object {
        private const val TEST_WIDGET_ID = 8898
        private const val POLL_INTERVAL_MS = 250L

        // Both location stages at their timeout ceiling, plus generous UI overhead.
        private const val AUTO_FILL_BOUND_MS =
            LocationFixFlow.ACTIVE_FIX_TIMEOUT_MS + LocationFixFlow.CACHED_FIX_TIMEOUT_MS + 10_000L
    }
}
