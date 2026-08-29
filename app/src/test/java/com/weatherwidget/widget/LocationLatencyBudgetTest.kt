package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.ui.LocationUpdater
import com.weatherwidget.util.SharedPreferencesUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * How long after moving before the widget can be right.
 *
 * These are **budget** assertions, not timing measurements. A wall-clock test of "arrive somewhere
 * and see fresh data" would depend on WorkManager dispatch and the network and would flake; what is
 * worth pinning is the handful of constants and structural facts that *determine* the latency, each
 * tied to the promise it keeps. A change that quietly turns the cooldown into ten minutes, or adds
 * an initial delay to the post-move refresh, reviews as harmless and would silently undo the work in
 * plans/260828-detect-the-move-when-the-user-is-looking.md.
 *
 * The budget, as of 2026-08-28:
 *
 * | Situation | Latency | Set by |
 * |---|---|---|
 * | You wake the phone / look at the widget | ≤60 s | [GpsResampler.RESAMPLE_COOLDOWN_MS] |
 * | You do neither, on battery | ≤45 min | [CurrentTempFetchPolicy.OPPORTUNISTIC_INTERVAL_MINUTES] |
 * | You arrive home and plug in | seconds | plug-in job latency + no delay on the refresh |
 *
 * The bottom row of that table used to be 4 hours (240 min above 70% battery), because nothing but
 * the periodic tick resampled off-charger.
 */
@Category(ShortDuration::class)
class LocationLatencyBudgetTest : RobolectricTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
    }

    /**
     * The interactive promise: "if the user is looking at the phone they should have accurate info."
     * Screen-on and paint both resample, and this bounds how stale their answer can be.
     */
    @Test
    fun `a resample requested while the user is present is at most a minute old`() {
        assertTrue(
            "The cooldown is the ceiling on how long screen-on or a paint can be ignored. " +
                "Raising it directly weakens 'if the user is looking, the info is accurate'.",
            GpsResampler.RESAMPLE_COOLDOWN_MS <= TimeUnit.MINUTES.toMillis(1),
        )
    }

    /**
     * The unattended floor. This is the number that was 240-1440 minutes before the opportunistic
     * job started resampling.
     */
    @Test
    fun `a move is noticed within the opportunistic cycle even if the phone is never touched`() {
        assertTrue(
            "Off-charger, the opportunistic job is the only thing that notices a move without user " +
                "interaction. Above ~an hour this stops being a floor and becomes the old 4-hour gap.",
            CurrentTempFetchPolicy.OPPORTUNISTIC_INTERVAL_MINUTES <= 60L,
        )
    }

    /**
     * Arriving home and plugging in should be seconds, not a scheduling cycle. Two things have to
     * hold: the plug-in job carries no latency of its own, and the refresh a detected move enqueues
     * carries no initial delay.
     */
    @Test
    fun `the plug-in trigger carries no latency of its own`() {
        val info = PowerConnectedJobService.buildJobInfo(context, minimumLatencyMs = 0L)

        assertEquals(
            "A delay here is added directly to 'plugged in at home, how long until it is right'",
            0L,
            info.minLatencyMillis,
        )
    }

    @Test
    fun `the refresh a detected move enqueues runs immediately`() {
        bindWidget(id = 640, lat = 37.4168, lon = -122.0890)

        LocationUpdater.applyFollowDeviceLocation(
            context = context,
            lat = 37.7749,
            lon = -122.4194,
            label = "Elsewhere",
            enqueueRefresh = true,
            ids = intArrayOf(640),
        )

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WeatherWidgetWorker.WORK_NAME_LOCATION_CANDIDATE)
            .get()
        assertEquals("a detected move must enqueue exactly one refresh", 1, work.size)
        assertEquals(
            "the refresh must be ENQUEUED, not BLOCKED or delayed — any wait here is added to " +
                "every arrival",
            androidx.work.WorkInfo.State.ENQUEUED,
            work.first().state,
        )
    }

    private fun bindWidget(id: Int, lat: Double, lon: Double) {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, info)
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$id", lon.toFloat())
            .commit()
    }
}
