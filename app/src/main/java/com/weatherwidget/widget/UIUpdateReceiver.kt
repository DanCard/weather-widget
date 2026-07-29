package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives scheduled UI update alarms and triggers UI-only widget refresh.
 */
class UIUpdateReceiver : BroadcastReceiver() {
    /**
     * Dispatcher used for background operations.
     * Can be overridden in tests to provide synchronous execution.
     */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                handleUiUpdateAlarm(context)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private suspend fun handleUiUpdateAlarm(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!powerManager.isInteractive) {
            Log.d(TAG, "Screen is off, skipping UI update work but preserving schedule")
            UIUpdateScheduler(context).scheduleNextUpdate()
            return
        }

        Log.d(TAG, "UI update alarm triggered")

        val batteryStatus =
            context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
            )
        val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)

        WidgetWorkScheduler.enqueueUiRepaint(context, reason = "ui_update_alarm")
        Log.d(TAG, "UI-only update enqueued")

        // Heartbeat recovery: Ensure the charging loop is running if the device is plugged in.
        // This handles cases where ACTION_POWER_CONNECTED was missed by the OS.
        CurrentTempUpdateScheduler.scheduleNextChargingUpdate(context, powerManager.isInteractive)

        UIUpdateScheduler(context).scheduleNextUpdate()
    }

    companion object {
        private const val TAG = "UIUpdateReceiver"
    }
}
