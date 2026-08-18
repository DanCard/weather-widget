package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.di.RepositoryEntryPoint

/**
 * Receiver that triggers widget updates when the user unlocks the screen.
 *
 * Always does UI-only update from cache for instant feedback.
 * If charging and data is stale, also triggers background data fetch.
 *
 * Also the event-driven half of device following. The periodic worker resamples location only on
 * a *full* sync ([FullSyncPipeline.run]), and the refreshes these events enqueue
 * are all `currentTempOnly`/`uiOnly` kinds — precisely the ones that gate excludes. So without the
 * calls below, plugging in and unlocking never notice that the device has moved, and a stale saved
 * location persists until the next full sync (60 min plugged, up to 480 min on low battery).
 *
 * **This receiver does not fire.** All three actions it is registered for in the manifest are
 * *implicit* broadcasts, and an app targeting API 26+ is not delivered those through a
 * manifest-declared receiver unless the action is on the framework exemption list — none of these
 * are. Verified 2026-08-18: three days of `app_logs` across a Pixel 7 Pro and a Samsung fold hold
 * zero `POWER_CONNECTED_EVENT` and zero `UNLOCK_REFRESH_POLICY` rows, and a genuine system-
 * dispatched `ACTION_POWER_CONNECTED` reached neither device's receiver with the process alive.
 * `exported="true"` (tried in 260413) was never the gate. The class is kept because it costs
 * nothing and still works wherever the broadcast *is* delivered.
 *
 * The plug-in path is now carried by [PowerConnectedJobService], a JobScheduler charging
 * constraint. **`ACTION_USER_PRESENT` has no replacement yet** — the screen-unlock refresh below
 * is dead code in practice. See `plans/260818-power-connected-broadcast-never-delivered.md`.
 */
class ScreenOnReceiver : BroadcastReceiver() {
    /**
     * Dispatcher used for background operations.
     * Can be overridden in tests to provide synchronous execution.
     */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    /**
     * Resolved through Hilt at call time rather than field-injected, so this stays a plain
     * BroadcastReceiver that tests can construct directly. Overridable as a test seam.
     */
    @VisibleForTesting
    internal var resampleLocation: suspend (Context, String) -> Unit = { ctx, trigger ->
        EntryPointAccessors
            .fromApplication(ctx.applicationContext, RepositoryEntryPoint::class.java)
            .gpsResampler()
            .resample(ctx, trigger)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> handlePowerConnected(context)
            Intent.ACTION_POWER_DISCONNECTED -> handlePowerDisconnected(context)
            Intent.ACTION_USER_PRESENT -> handleUserPresent(context)
            Intent.ACTION_SCREEN_OFF -> handleScreenOff(context)
            else -> return
        }
    }

    private fun handlePowerConnected(context: Context) {
        // Shared with PowerConnectedJobService, which is the entry point that actually fires on
        // this device family; see PowerConnectedRefresh for why this receiver does not.
        val outcome = PowerConnectedRefresh.run(context)
        Log.d(TAG, "Power connected - plug-in refresh result=${outcome.result} elapsed=${outcome.elapsedMs}ms")

        // Independent of the current-temp debounce inside run(): putting the phone on the charger
        // is the moment it has most likely just finished moving.
        resampleLocationAsync(context, trigger = "power_connected")

        logPowerConnectedEvent(context, outcome)
    }

    private fun handlePowerDisconnected(context: Context) {
        // Re-enqueue with the off-charger interval (BatteryFetchStrategy tiers).
        Log.d(TAG, "Power disconnected - rescheduling periodic forecast worker")
        WidgetWorkScheduler.schedulePeriodicSync(context)
        OpportunisticUpdateJobService.scheduleOpportunisticUpdate(context)
        NonPrimaryObservationScheduler.cancel(context)
    }

    private fun handleUserPresent(context: Context) {
        val battery = getBatteryState(context)
        val uiOnly = WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(
            isCharging = battery.isCharging,
        )
        Log.d(TAG, "Screen unlocked - charging=${battery.isCharging}, battery=${battery.batteryLevel}%, uiOnly=$uiOnly")

        resampleLocationAsync(context, trigger = "user_present")

        val providerIntent =
            Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_REFRESH
                if (uiOnly) {
                    putExtra(WidgetActions.EXTRA_UI_ONLY, true)
                }
            }
        context.sendBroadcast(providerIntent)

        if (battery.isCharging) {
            CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                context = context,
                reason = "screen_unlock_charging",
                opportunistic = false,
            )
            NonPrimaryObservationScheduler.scheduleNextUpdate(
                context = context,
                isScreenInteractive = true,
            )
        } else {
            CurrentTempUpdateScheduler.cancel(context)
            NonPrimaryObservationScheduler.cancel(context)
        }

        val pendingResult = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                WeatherDatabase.getDatabase(context).appLogDao().log(
                    "UNLOCK_REFRESH_POLICY",
                    "charging=${battery.isCharging} battery=${battery.batteryLevel}% uiOnly=$uiOnly",
                )
                UIUpdateScheduler(context).scheduleNextUpdate()


            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule next update on screen on", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun handleScreenOff(context: Context) {
        val battery = getBatteryState(context)
        if (battery.isCharging) {
            Log.d(TAG, "Screen turned off while charging - keeping current-temp loop running, but canceling non-primary loop")
            NonPrimaryObservationScheduler.cancel(context)
        } else {
            Log.d(TAG, "Screen turned off on battery - canceling current-temp loop")
            CurrentTempUpdateScheduler.cancel(context)
            NonPrimaryObservationScheduler.cancel(context)
        }
    }

    /**
     * Passive `lastLocation` read + same-site comparison — no GPS power-up, no network, and no
     * Samsung "app got your precise location" notice (see [GpsResampler]). Debounced anyway because
     * unlock fires dozens of times a day and a detected candidate costs a reverse-geocode.
     *
     * Best-effort: a resample failure must never take down the refresh the event came for.
     */
    private fun resampleLocationAsync(context: Context, trigger: String) {
        val now = System.currentTimeMillis()
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val lastResampleMs = prefs.getLong(KEY_LAST_RESAMPLE_MS, 0L)
        if (!PowerConnectedRefreshPolicy.shouldEnqueueRefresh(now, lastResampleMs)) {
            Log.d(TAG, "Skipping GPS resample (debounced, trigger=$trigger)")
            return
        }
        prefs.edit().putLong(KEY_LAST_RESAMPLE_MS, now).apply()

        val pendingResult = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                resampleLocation(context, trigger)
            } catch (e: Exception) {
                Log.w(TAG, "GPS resample failed (trigger=$trigger)", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun logPowerConnectedEvent(
        context: Context,
        outcome: PowerConnectedRefresh.Outcome,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                PowerConnectedRefresh.writeLog(context, outcome, source = "broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist POWER_CONNECTED_EVENT log", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun getBatteryState(context: Context): BatterySnapshot =
        BatterySnapshotProvider.snapshot(context)

    companion object {
        private const val TAG = "ScreenOnReceiver"
        private const val PREFS_NAME = PowerConnectedRefresh.PREFS_NAME

        @VisibleForTesting
        internal const val KEY_LAST_RESAMPLE_MS = "last_gps_resample_ms"
    }
}
