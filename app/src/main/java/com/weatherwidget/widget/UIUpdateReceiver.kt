package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        UiTick.run(context, via = "alarm")
    }

    companion object {
        private const val TAG = "UIUpdateReceiver"
    }
}
