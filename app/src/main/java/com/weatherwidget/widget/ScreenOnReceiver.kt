package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return

        val charging = isCharging(context)
        Log.d(TAG, "Screen unlocked - charging=$charging")

        // Trigger update via provider
        val providerIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WeatherWidgetProvider.ACTION_REFRESH
            // If not charging, only do UI update (no full fetch)
            if (!charging) {
                putExtra(WeatherWidgetProvider.EXTRA_UI_ONLY, true)
            }
        }
        context.sendBroadcast(providerIntent)
    }

    private fun isCharging(context: Context): Boolean {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
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
