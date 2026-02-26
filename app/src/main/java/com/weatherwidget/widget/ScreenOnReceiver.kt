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
            Intent.ACTION_USER_PRESENT -> handleUserPresent(context)
            Intent.ACTION_SCREEN_OFF -> handleScreenOff(context)
            else -> return
        }
    }

    private fun handleUserPresent(context: Context) {
        val charging = isCharging(context)
        Log.d(TAG, "Screen unlocked - charging=$charging")

        // Trigger update via provider
        val providerIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_REFRESH
                if (WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(charging)) {
                    putExtra(WeatherWidgetProvider.EXTRA_UI_ONLY, true)
                }
            }
        context.sendBroadcast(providerIntent)

        if (charging) {
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
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                UIUpdateScheduler(context).scheduleNextUpdate()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule next update on screen on", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleScreenOff(context: Context) {
        Log.d(TAG, "Screen turned off - canceling charging current-temp loop")
        CurrentTempUpdateScheduler.cancel(context)
    }

    private fun isCharging(context: Context): Boolean {
        val batteryStatus: Intent? =
            IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        private const val TAG = "ScreenOnReceiver"
    }
}
