package com.weatherwidget.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The widget-add configuration handshake: ConfigActivity launched the way a launcher does
 * (APPWIDGET_CONFIGURE with a widget id).
 *
 * Contract under test (2026-07-08):
 * 1. The auto-started location flow resolves in bounded time (LocationFixFlow timeouts) — an
 *    unbounded `getCurrentLocation` once left the screen dead for 30+ seconds.
 * 2. The screen must NOT save-and-finish on its own — an auto-exiting version yanked the screen
 *    away from users who wanted to search. It waits with a re-enabled GPS button.
 * 3. Tapping the button completes the handshake: RESULT_OK with the widget id echoed back and
 *    the location persisted.
 *
 * Replaces an instrumented version of the same contract. That one took 59s: ~15s of test body,
 * then 44s of teardown blocked on the real WorkManager scheduler getting around to the
 * force-refresh worker that [ConfigActivity.triggerWidgetUpdate] enqueues. Robolectric has no
 * such scheduler, and the fix stages are faked rather than waited on, so this runs in
 * milliseconds — including the timeout path, which elapses in virtual time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ConfigActivityAddFlowRoboTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Without all three the auto-started flow stops early — fine/coarse short-circuit
        // getCurrentLocation, and a missing background grant parks it on the disclosure dialog.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        ConfigActivity.setupSourceSelectorForTesting = { current, _, _ ->
            SetupSourceSelection(
                sources = current,
                nwsCoverage = SetupNwsCoverage.SUPPORTED,
            )
        }
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        context.getSharedPreferences("widget_state_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(ConfigActivity.KEY_BACKGROUND_DATA_PROMPTED, true)
            .commit()
        clearSavedLocation()
    }

    @After
    fun tearDown() {
        ConfigActivity.locationStagesForTesting = null
        ConfigActivity.setupSourceSelectorForTesting = null
        context.getSharedPreferences("widget_state_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        clearSavedLocation()
    }

    @Test
    fun `auto-fill resolves the fix, enables the confirm button, and never exits on its own`() {
        ConfigActivity.locationStagesForTesting = fixedStages(activeFix = FIX)

        launchAddFlow().use { scenario ->
            drainUntil("auto-fill resolved") { scenario.confirmButtonIsOffered() }

            assertNotEquals(
                "Config screen must not exit without a user action",
                Lifecycle.State.DESTROYED,
                scenario.state,
            )
            scenario.onActivity { activity ->
                assertFalse("Screen must wait for the confirm tap", activity.isFinishing)
            }
        }
    }

    @Test
    fun `confirm tap completes the add handshake and persists the location`() {
        ConfigActivity.locationStagesForTesting = fixedStages(activeFix = FIX)

        val scenario = launchAddFlow()
        drainUntil("auto-fill resolved") { scenario.confirmButtonIsOffered() }

        // setResult and finish run synchronously inside the click; only the breadcrumb is async.
        scenario.onActivity { activity ->
            activity.findViewById<Button>(R.id.use_gps_button).performClick()

            assertTrue("Confirm tap must finish the screen", activity.isFinishing)
            val shadow = shadowOf(activity)
            assertEquals(
                "Confirm tap must complete the add handshake with RESULT_OK",
                Activity.RESULT_OK,
                shadow.resultCode,
            )
            assertEquals(
                "RESULT_OK must echo the widget id back to the launcher",
                TEST_WIDGET_ID,
                shadow.resultIntent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                ),
            )
        }

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        assertEquals(
            FIX.lat.toFloat(),
            prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$TEST_WIDGET_ID", Float.NaN),
            0.0001f,
        )
        assertEquals(
            FIX.lon.toFloat(),
            prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$TEST_WIDGET_ID", Float.NaN),
            0.0001f,
        )
        scenario.close()
    }

    @Test
    fun `unsupported NWS enables validated WeatherAPI during launcher setup`() {
        ConfigActivity.locationStagesForTesting = fixedStages(activeFix = LONDON_FIX)
        val stateManager = WidgetStateManager(context)
        stateManager.setVisibleSourcesOrder(
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
            ),
        )
        ConfigActivity.setupSourceSelectorForTesting = { _, _, _ ->
            SetupSourceSelection(
                sources =
                    listOf(
                        WeatherSource.OPEN_METEO,
                        WeatherSource.SILURIAN,
                        WeatherSource.WEATHER_API,
                    ),
                nwsCoverage = SetupNwsCoverage.UNSUPPORTED,
                weatherApiAvailability = SetupWeatherApiAvailability.AVAILABLE,
            )
        }

        val scenario = launchAddFlow()
        drainUntil("London auto-fill resolved") { scenario.confirmButtonIsOffered() }
        scenario.onActivity {
            it.findViewById<Button>(R.id.use_gps_button).performClick()
        }

        assertEquals(
            listOf(
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
                WeatherSource.WEATHER_API,
            ),
            stateManager.getVisibleSourcesOrder(),
        )
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        scenario.close()
    }

    @Test
    fun `failed WeatherAPI validation still removes unsupported NWS`() {
        ConfigActivity.locationStagesForTesting = fixedStages(activeFix = LONDON_FIX)
        val stateManager = WidgetStateManager(context)
        stateManager.setVisibleSourcesOrder(
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
            ),
        )
        ConfigActivity.setupSourceSelectorForTesting = { _, _, _ ->
            SetupSourceSelection(
                sources = listOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
                nwsCoverage = SetupNwsCoverage.UNSUPPORTED,
                weatherApiAvailability = SetupWeatherApiAvailability.UNAVAILABLE,
                reason = "http_401",
            )
        }

        val scenario = launchAddFlow()
        drainUntil("London auto-fill resolved") { scenario.confirmButtonIsOffered() }
        scenario.onActivity {
            it.findViewById<Button>(R.id.use_gps_button).performClick()
        }

        assertEquals(
            listOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
            stateManager.getVisibleSourcesOrder(),
        )
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        scenario.close()
    }

    @Test
    fun `second save tap is blocked and Back cancels the pending check without changing sources`() {
        ConfigActivity.locationStagesForTesting = fixedStages(activeFix = LONDON_FIX)
        val stateManager = WidgetStateManager(context)
        val originalSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        stateManager.setVisibleSourcesOrder(originalSources)
        var calls = 0
        ConfigActivity.setupSourceSelectorForTesting = { _, _, _ ->
            calls += 1
            awaitCancellation()
        }

        val scenario = launchAddFlow()
        drainUntil("London auto-fill resolved") { scenario.confirmButtonIsOffered() }
        scenario.onActivity { activity ->
            val button = activity.findViewById<Button>(R.id.use_gps_button)
            button.performClick()
            button.performClick()
            assertFalse("Save controls must stay disabled during the check", button.isEnabled)
            activity.onBackPressedDispatcher.onBackPressed()
        }

        assertEquals(1, calls)
        // The user's list: Back saves London, where NWS is merely unavailable (filtered), not removed.
        assertEquals(originalSources, stateManager.getEnabledSourcesOrder())
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        scenario.close()
    }

    /**
     * The regression that motivated LocationFixFlow: a provider that never returns. Both stages
     * hang, so the flow can only finish by timing out twice. Robolectric runs those timeouts in
     * virtual time, so the assertion is about termination, not wall-clock patience.
     */
    @Test
    fun `hanging location provider still resolves and leaves the screen open`() {
        ConfigActivity.locationStagesForTesting = ConfigActivity.LocationStages(
            activeFix = { awaitCancellation() },
            cachedFix = { awaitCancellation() },
        )

        launchAddFlow().use { scenario ->
            // Pin the in-flight state first: the resolved state below is also the button's
            // initial state, so without this the test would pass even if the flow never ran.
            scenario.onActivity { activity ->
                val button = activity.findViewById<Button>(R.id.use_gps_button)
                assertFalse("GPS flow must have started and disabled the button", button.isEnabled)
                assertEquals(
                    activity.getString(R.string.getting_location),
                    button.text.toString(),
                )
            }

            // Both stage timeouts elapse in virtual time — no wall-clock wait.
            val bound = LocationFixFlow.ACTIVE_FIX_TIMEOUT_MS + LocationFixFlow.CACHED_FIX_TIMEOUT_MS
            shadowOf(context.mainLooper).idleFor(bound + 1_000, TimeUnit.MILLISECONDS)
            drainUntil("stages timed out and the button recovered") {
                var enabled = false
                scenario.onActivity { enabled = it.findViewById<Button>(R.id.use_gps_button).isEnabled }
                enabled
            }

            assertNotEquals(
                "Screen must stay open when no fix is available",
                Lifecycle.State.DESTROYED,
                scenario.state,
            )
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.use_precise_location),
                    activity.findViewById<Button>(R.id.use_gps_button).text.toString(),
                )
            }
        }
    }

    // --- Back during a running fix asks to wait (plans/261022-setup-back-waits-for-running-location-fix.md).
    // Emulator 2026-10-22: back 2 s into the fix saved the widget with no location and cancelled the fix.

    @Test
    fun `a running fix shows the progress row, and it goes when the fix lands`() {
        val fix = CompletableDeferred<LocationFixFlow.Coordinates?>()
        ConfigActivity.locationStagesForTesting = deferredStages(fix)

        launchAddFlow().use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.location_progress_row).visibility)
            }
            fix.complete(FIX)
            drainUntil("auto-fill resolved") { scenario.confirmButtonIsOffered() }
            scenario.onActivity { activity ->
                assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.location_progress_row).visibility)
            }
        }
    }

    @Test
    fun `back during a running fix asks to wait instead of leaving`() {
        ConfigActivity.locationStagesForTesting = deferredStages(CompletableDeferred())

        launchAddFlow().use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse("Back during a fix must not leave yet", activity.isFinishing)
                assertNotNull("Back during a fix must ask Wait / Skip", waitDialog())
            }
        }
    }

    @Test
    fun `Wait saves the fix when it lands and finishes`() {
        val fix = CompletableDeferred<LocationFixFlow.Coordinates?>()
        ConfigActivity.locationStagesForTesting = deferredStages(fix)

        val scenario = launchAddFlow()
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
            waitDialog()!!.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(context.mainLooper).idle() // AppCompat posts the click
            assertFalse("Wait keeps the screen until the fix lands", activity.isFinishing)
        }
        fix.complete(FIX)
        drainUntil("fix saved") { savedLat() != null }

        assertEquals(FIX.lat, savedLat()!!, 1e-4)
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        scenario.close()
    }

    @Test
    fun `Skip leaves at once with no location`() {
        ConfigActivity.locationStagesForTesting = deferredStages(CompletableDeferred())

        val scenario = launchAddFlow()
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
            waitDialog()!!.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(context.mainLooper).idle() // AppCompat posts the click
        }
        drainUntil("screen finished") { scenario.isFinishing() }

        assertEquals("Skip keeps the widget (RESULT_OK)", Activity.RESULT_OK, scenario.result.resultCode)
        assertEquals("Skip saves no location", null, savedLat())
        scenario.close()
    }

    @Test
    fun `a second back while waiting leaves at once with no location`() {
        ConfigActivity.locationStagesForTesting = deferredStages(CompletableDeferred())

        val scenario = launchAddFlow()
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
            waitDialog()!!.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(context.mainLooper).idle() // AppCompat posts the click
            activity.onBackPressedDispatcher.onBackPressed()
        }
        drainUntil("screen finished") { scenario.isFinishing() }

        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        assertEquals(null, savedLat())
        scenario.close()
    }

    @Test
    fun `Wait and no fix leaves the screen open with its options`() {
        val fix = CompletableDeferred<LocationFixFlow.Coordinates?>()
        ConfigActivity.locationStagesForTesting = deferredStages(fix)

        launchAddFlow().use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                waitDialog()!!.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(context.mainLooper).idle() // AppCompat posts the click
            }
            fix.complete(null)
            drainUntil("fix flow gave up and the button recovered") {
                var enabled = false
                scenario.onActivity { enabled = it.findViewById<Button>(R.id.use_gps_button).isEnabled }
                enabled
            }
            scenario.onActivity { activity ->
                assertFalse("No fix after Wait: stay, so search is right there", activity.isFinishing)
            }
            assertEquals(null, savedLat())
        }
    }

    @Test
    fun `a fix landing while the question is up is saved and the screen finishes`() {
        val fix = CompletableDeferred<LocationFixFlow.Coordinates?>()
        ConfigActivity.locationStagesForTesting = deferredStages(fix)

        val scenario = launchAddFlow()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        fix.complete(FIX)
        drainUntil("fix saved") { savedLat() != null }

        assertEquals(FIX.lat, savedLat()!!, 1e-4)
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        scenario.close()
    }

    /** Finishing, or already gone (onActivity throws once the activity is destroyed). */
    private fun ActivityScenario<ConfigActivity>.isFinishing(): Boolean {
        var finishing = true
        runCatching { onActivity { finishing = it.isFinishing } }
        return finishing
    }

    private fun waitDialog(): androidx.appcompat.app.AlertDialog? =
        (ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog)?.takeIf { it.isShowing }

    private fun savedLat(): Double? {
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        val key = "${ConfigActivity.KEY_LAT_PREFIX}$TEST_WIDGET_ID"
        return if (prefs.contains(key)) prefs.getFloat(key, Float.NaN).toDouble() else null
    }

    private fun deferredStages(fix: CompletableDeferred<LocationFixFlow.Coordinates?>) =
        ConfigActivity.LocationStages(
            activeFix = { fix.await() },
            cachedFix = { null },
        )

    /** True once the auto-fill fix resolved and the button became a one-tap confirm. */
    // "Use precise device location" within LocationMatch.WEATHER_SITE_RADIUS_KM of the active site
    // keeps that site (same weather) instead of writing a new one a few hundred metres away.
    private fun confirmLondonFixWithActiveSiteAt(siteLat: Double, siteLon: Double): Pair<Double, Double> {
        com.weatherwidget.widget.ActiveLocationResolver.persist(context, siteLat, siteLon)
        ConfigActivity.locationStagesForTesting = fixedStages(activeFix = LONDON_FIX)
        try {
            val scenario = launchAddFlow()
            drainUntil("London auto-fill resolved") { scenario.confirmButtonIsOffered() }
            scenario.onActivity { it.findViewById<Button>(R.id.use_gps_button).performClick() }
            drainUntil("location saved") {
                SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
                    .contains("${ConfigActivity.KEY_LAT_PREFIX}$TEST_WIDGET_ID")
            }
            scenario.close()
            val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
            assertEquals(com.weatherwidget.util.LocationMode.FOLLOW_DEVICE, com.weatherwidget.util.LocationMode.get(context))
            return prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$TEST_WIDGET_ID", Float.NaN).toDouble() to
                prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$TEST_WIDGET_ID", Float.NaN).toDouble()
        } finally {
            com.weatherwidget.widget.ActiveLocationResolver.clearForTesting(context)
        }
    }

    @Test
    fun `precise fix 500 m from the active site keeps the site`() {
        // ~0.0045 deg of latitude = 500 m north of the London fix.
        val saved = confirmLondonFixWithActiveSiteAt(51.5119, -0.1278)
        assertEquals(51.5119, saved.first, 1e-4)
        assertEquals(-0.1278, saved.second, 1e-4)
    }

    @Test
    fun `precise fix 3 km from the active site moves to the fix`() {
        val saved = confirmLondonFixWithActiveSiteAt(51.5344, -0.1278)
        assertEquals(LONDON_FIX.lat, saved.first, 1e-4)
        assertEquals(LONDON_FIX.lon, saved.second, 1e-4)
    }

    private fun ActivityScenario<ConfigActivity>.confirmButtonIsOffered(): Boolean {
        var offered = false
        onActivity { activity ->
            val button = activity.findViewById<Button>(R.id.use_gps_button)
            offered = button.isEnabled &&
                button.text.toString() == activity.getString(R.string.use_this_location)
        }
        return offered
    }

    private fun launchAddFlow(): ActivityScenario<ConfigActivity> {
        val intent = Intent(context, ConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, TEST_WIDGET_ID)
        return ActivityScenario.launchActivityForResult(intent)
    }

    /**
     * Drains the main looper until [condition] holds. A single `idle()` is not enough: the fix
     * flow suspends on `appLogDao.log` to write the GPS_FIX breadcrumb, which runs on Room's own
     * executor, so the continuation is not posted back to main until that real thread finishes.
     */
    private fun drainUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(context.mainLooper).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("$what did not happen within ${DRAIN_TIMEOUT_MS}ms")
    }

    private fun fixedStages(activeFix: LocationFixFlow.Coordinates?) =
        ConfigActivity.LocationStages(
            activeFix = { activeFix },
            cachedFix = { null },
        )

    private fun clearSavedLocation() {
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
            .edit()
            .remove("${ConfigActivity.KEY_LAT_PREFIX}$TEST_WIDGET_ID")
            .remove("${ConfigActivity.KEY_LON_PREFIX}$TEST_WIDGET_ID")
            .commit()
    }

    private companion object {
        const val TEST_WIDGET_ID = 8898
        const val DRAIN_TIMEOUT_MS = 10_000L
        val FIX = LocationFixFlow.Coordinates(lat = 37.4168, lon = -122.0890)
        val LONDON_FIX = LocationFixFlow.Coordinates(lat = 51.5074, lon = -0.1278)
    }
}
