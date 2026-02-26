package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log

/**
 * Receiver that triggers widget updates when the user unlocks the screen.
 *
 * Always does UI-only update from cache for instant feedback.
 * If charging and data is stale, also triggers background data fetch.
 */
class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> handlePowerConnected(context)
            Intent.ACTION_USER_PRESENT -> handleUserPresent(context)
            Intent.ACTION_SCREEN_OFF -> handleScreenOff(context)
            else -> return
        }
    }

    private fun handlePowerConnected(context: Context) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    private fun handleUserPresent(context: Context) {
        val battery = getBatteryState(context)
        val uiOnly = WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(
            isCharging = battery.isCharging,
            batteryLevel = battery.level,
        )
        Log.d(TAG, "Screen unlocked - charging=${battery.isCharging}, battery=${battery.level}%, uiOnly=$uiOnly")

        // Trigger update via provider
        val providerIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_REFRESH
                if (uiOnly) {
                    putExtra(WeatherWidgetProvider.EXTRA_UI_ONLY, true)
                }
            }
        context.sendBroadcast(providerIntent)

        if (battery.isCharging) {
            CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                context = context,
                reason = "screen_unlock_charging",
                opportunistic = false,
            )
        } else {
            CurrentTempUpdateScheduler.cancel(context)
        }

        // Resume background UI updates now that screen is on
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
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
        Log.d(TAG, "Screen turned off - canceling charging current-temp loop")
        CurrentTempUpdateScheduler.cancel(context)
    }

    private fun logPowerConnectedEvent(
        context: Context,
        result: String,
        nowMs: Long,
        lastRefreshMs: Long,
        elapsedMs: Long,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
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

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

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
            isCharging = isCharging,
            level = level,
        )
    }

    companion object {
        private const val TAG = "ScreenOnReceiver"
        private const val PREFS_NAME = "screen_on_receiver_prefs"
        private const val KEY_LAST_POWER_CONNECTED_REFRESH_MS = "last_power_connected_refresh_ms"
    }
}

private data class BatteryState(
    val isCharging: Boolean,
    val level: Int,
)
