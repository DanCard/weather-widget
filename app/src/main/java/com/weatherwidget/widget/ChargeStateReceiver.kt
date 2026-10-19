package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The exact plug/unplug time for [ChargeSessionTracker], while the process lives. Registered at
 * runtime only ([register]): a manifest entry for these actions is never delivered at targetSdk 26+
 * (see [PowerConnectedRefresh]), but a runtime-registered receiver is, as `SCREEN_ON` is for
 * [ScreenOnReceiver]. Does nothing else — the plug-in refresh stays with [PowerConnectedJobService].
 */
class ChargeStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val charging = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> true
            Intent.ACTION_POWER_DISCONNECTED -> false
            else -> return
        }
        val app = context.applicationContext
        val line = ChargeSessionTracker.observe(app, charging, exact = true) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WeatherDatabase.getDatabase(app).appLogDao().log(ChargeSessionTracker.LOG_TAG, line, "INFO")
            } catch (e: Exception) {
                Log.e(TAG, "Unplug log failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ChargeStateReceiver"

        fun register(context: Context) {
            val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED).apply { addAction(Intent.ACTION_POWER_DISCONNECTED) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(ChargeStateReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(ChargeStateReceiver(), filter)
            }
        }
    }
}
