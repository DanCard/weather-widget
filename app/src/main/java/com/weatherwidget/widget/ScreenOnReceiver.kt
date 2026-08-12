package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
        // Re-enqueue the periodic forecast worker with the charging-cadence interval (60 min).
        WidgetWorkScheduler.schedulePeriodicSync(context)
        OpportunisticUpdateJobService.scheduleOpportunisticUpdate(context)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isInteractive) {
            NonPrimaryObservationScheduler.scheduleNextUpdate(context, isScreenInteractive = true)
        }

        // Independent of the current-temp debounce below: putting the phone on the charger is the
        // moment it has most likely just finished moving.
        resampleLocationAsync(context, trigger = "power_connected")

        val now = System.currentTimeMillis()
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val lastRefreshMs = prefs.getLong(KEY_LAST_POWER_CONNECTED_REFRESH_MS, 0L)
        val elapsedMs = now - lastRefreshMs

        if (!PowerConnectedRefreshPolicy.shouldEnqueueRefresh(now, lastRefreshMs)) {
            Log.d(TAG, "Power connected - skipping lazy refresh (debounced, elapsed=${elapsedMs}ms)")
            logPowerConnectedEvent(
                context = context,
                result = "debounced_skip",
                nowMs = now,
                lastRefreshMs = lastRefreshMs,
                elapsedMs = elapsedMs,
            )
            return
        }

        prefs.edit().putLong(KEY_LAST_POWER_CONNECTED_REFRESH_MS, now).apply()
        Log.d(TAG, "Power connected - enqueueing lazy current-temp refresh")
        CurrentTempUpdateScheduler.enqueueImmediateUpdate(
            context = context,
            reason = "power_connected_lazy",
            opportunistic = true,
        )
        logPowerConnectedEvent(
            context = context,
            result = "enqueued",
            nowMs = now,
            lastRefreshMs = lastRefreshMs,
            elapsedMs = elapsedMs,
        )
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
        Log.d(TAG, "Screen unlocked - charging=${battery.isCharging}, battery=${battery.level}%, uiOnly=$uiOnly")

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
                    "charging=${battery.isCharging} battery=${battery.level}% uiOnly=$uiOnly",
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
        result: String,
        nowMs: Long,
        lastRefreshMs: Long,
        elapsedMs: Long,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                WeatherDatabase.getDatabase(context).appLogDao().log(
                    "POWER_CONNECTED_EVENT",
                    "result=$result nowMs=$nowMs lastRefreshMs=$lastRefreshMs elapsedMs=$elapsedMs debounceMs=${PowerConnectedRefreshPolicy.DEBOUNCE_MS}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist POWER_CONNECTED_EVENT log", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun getBatteryState(context: Context): BatteryState {
        val batteryStatus: Intent? =
            IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }

        val level = batteryStatus?.let { intent ->
            val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (rawLevel >= 0 && scale > 0) {
                (rawLevel * 100) / scale
            } else {
                100
            }
        } ?: 100

        return BatteryState(
            isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus),
            level = level,
        )
    }

    companion object {
        private const val TAG = "ScreenOnReceiver"
        private const val PREFS_NAME = "screen_on_receiver_prefs"
        private const val KEY_LAST_POWER_CONNECTED_REFRESH_MS = "last_power_connected_refresh_ms"

        @VisibleForTesting
        internal const val KEY_LAST_RESAMPLE_MS = "last_gps_resample_ms"
    }
}

private data class BatteryState(
    val isCharging: Boolean,
    val level: Int,
)
