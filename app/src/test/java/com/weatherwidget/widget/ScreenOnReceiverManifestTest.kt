package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * `ACTION_SCREEN_ON` must never be declared in the manifest, and must be registered at runtime.
 *
 * This is worth a test rather than a comment because **the failure is silent**. The system sends
 * `SCREEN_ON`/`SCREEN_OFF` with `FLAG_RECEIVER_REGISTERED_ONLY`, so a manifest component is skipped
 * by the dispatcher — no install warning, no runtime error, nothing in logcat. A `<receiver>` entry
 * for it reviews as correct, ships, and does nothing. From `android/content/Intent.java`:
 *
 * > You *cannot* receive this through components declared in manifests, only by explicitly
 * > registering for it with `Context.registerReceiver()`.
 *
 * The trap is specific: `ScreenOnReceiver` *is* manifest-declared, for other actions, and its dead
 * `USER_PRESENT` path invites exactly the wrong fix — adding `SCREEN_ON` beside it.
 *
 * Precedent for an architecture test of this shape: `HourlyProximityQueryAllowlistTest`.
 */
@Category(ShortDuration::class)
class ScreenOnReceiverManifestTest : RobolectricTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `no manifest receiver declares SCREEN_ON, because it would silently never fire`() {
        val resolved = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_SCREEN_ON).setPackage(context.packageName),
            0,
        )

        assertTrue(
            "A manifest receiver for ACTION_SCREEN_ON never fires (FLAG_RECEIVER_REGISTERED_ONLY). " +
                "Register at runtime via ScreenOnReceiver.registerScreenReceiver instead. Found: " +
                resolved.map { it.activityInfo?.name },
            resolved.isEmpty(),
        )
    }

    @Test
    fun `the runtime registration accepts a screen-on broadcast`() {
        val received = mutableListOf<String>()
        val receiver = ScreenOnReceiver()
        // Same seam the sibling tests use: the resample tail runs on this dispatcher via goAsync.
        receiver.ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        receiver.resampleLocation = { _, trigger -> received.add(trigger) }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))

        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))

        assertEquals(
            "screen-on is the earliest signal that the user is present; it must resample",
            listOf("screen_on"),
            received,
        )
        context.unregisterReceiver(receiver)
    }

    /**
     * `SCREEN_OFF` must not be manifest-declared either, and for the same silent reason.
     *
     * This one had a cost beyond dead code: [ScreenOnReceiver.handleScreenOff] cancels the
     * current-temp loop, and 114 `CURR_FETCH_WORK_STATE` rows on a Samsung fold showed that loop
     * scheduling itself with `interactive=false` — polling for a screen nobody was looking at,
     * because the handler that would have stopped it could never fire.
     */
    @Test
    fun `no manifest receiver declares SCREEN_OFF either`() {
        val resolved = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_SCREEN_OFF).setPackage(context.packageName),
            0,
        )

        assertTrue(
            "SCREEN_OFF is runtime-only, same as SCREEN_ON. Found: " + resolved.map { it.activityInfo?.name },
            resolved.isEmpty(),
        )
    }

    /**
     * The saving itself: screen off, on battery, stop the current-temp loop.
     *
     * Asserted here rather than on an emulator because an emulator cannot reach this branch — its
     * battery sits at 100% and never falls, so `BatteryChargeTrend` ("a high level that is not
     * falling means something is holding it up") reports charging however the virtual charger is
     * set, and the receiver takes the charging branch every time.
     */
    @Test
    fun `screen off on battery cancels the current-temp loop`() {
        setStickyBattery(discharging = true, level = 40)
        mockkObject(CurrentTempUpdateScheduler)
        every { CurrentTempUpdateScheduler.cancel(any()) } just Runs
        mockkObject(NonPrimaryObservationScheduler)
        every { NonPrimaryObservationScheduler.cancel(any()) } just Runs
        try {
            val receiver = ScreenOnReceiver()
            receiver.ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

            receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))

            verify(exactly = 1) { CurrentTempUpdateScheduler.cancel(any()) }
        } finally {
            unmockkAll()
        }
    }

    /** Charging: keep the loop (the screen-off interval already slows it), drop only non-primary. */
    @Test
    fun `screen off while charging keeps the current-temp loop`() {
        setStickyBattery(discharging = false, level = 90)
        mockkObject(CurrentTempUpdateScheduler)
        every { CurrentTempUpdateScheduler.cancel(any()) } just Runs
        mockkObject(NonPrimaryObservationScheduler)
        every { NonPrimaryObservationScheduler.cancel(any()) } just Runs
        try {
            val receiver = ScreenOnReceiver()
            receiver.ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

            receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))

            verify(exactly = 0) { CurrentTempUpdateScheduler.cancel(any()) }
            verify(exactly = 1) { NonPrimaryObservationScheduler.cancel(any()) }
        } finally {
            unmockkAll()
        }
    }

    private fun setStickyBattery(discharging: Boolean, level: Int) {
        com.weatherwidget.util.SharedPreferencesUtil
            .getPrefs(context, "battery_charge_trend_prefs").edit().clear().commit()
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(
                    android.os.BatteryManager.EXTRA_STATUS,
                    if (discharging) android.os.BatteryManager.BATTERY_STATUS_DISCHARGING
                    else android.os.BatteryManager.BATTERY_STATUS_CHARGING,
                )
                putExtra(
                    android.os.BatteryManager.EXTRA_PLUGGED,
                    if (discharging) 0 else android.os.BatteryManager.BATTERY_PLUGGED_AC,
                )
                putExtra(android.os.BatteryManager.EXTRA_LEVEL, level)
                putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            },
        )
    }
}
