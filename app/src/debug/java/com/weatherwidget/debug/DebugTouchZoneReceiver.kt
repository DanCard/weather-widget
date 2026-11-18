package com.weatherwidget.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.weatherwidget.BuildConfig
import com.weatherwidget.widget.DebugTouchZoneOverlay
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetRenderer

/**
 * adb entry point for [DebugTouchZoneOverlay], so header/navigation touch zones can be tinted on
 * a device or emulator without rebuilding.
 *
 * ```
 * adb shell am broadcast -a com.weatherwidget.debug.TOUCH_ZONES \
 *   -n com.weatherwidget/com.weatherwidget.debug.DebugTouchZoneReceiver \
 *   --ez enabled true
 *
 * adb shell am broadcast -a com.weatherwidget.debug.TOUCH_ZONES \
 *   -n com.weatherwidget/com.weatherwidget.debug.DebugTouchZoneReceiver \
 *   --ez enabled false
 * ```
 *
 * Lives in `src/debug` and requires `android.permission.DUMP` so release builds never include it
 * and ordinary apps cannot toggle it on a debug phone.
 */
class DebugTouchZoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_TOUCH_ZONES) {
            Log.w(TAG, "unhandled action ${intent.action}")
            return
        }
        val appContext = context.applicationContext
        val enabled = if (intent.hasExtra(EXTRA_ENABLED)) {
            intent.getBooleanExtra(EXTRA_ENABLED, false)
        } else {
            !DebugTouchZoneOverlay.isEnabled()
        }
        DebugTouchZoneOverlay.set(appContext, enabled)
        // Daily UI-only repaints skip when already painted today; clear that cache so every widget
        // repaints immediately with (or without) the touch-zone tint.
        WidgetRenderer.invalidateDailyPaintCache()
        appContext.sendBroadcast(
            Intent(appContext, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_REFRESH
                putExtra(WidgetActions.EXTRA_UI_ONLY, true)
            },
        )
        Log.i(TAG, "touch zone overlay enabled=$enabled; requested UI-only repaint")
    }

    companion object {
        private const val TAG = "DebugTouchZoneReceiver"
        const val ACTION_TOUCH_ZONES = "com.weatherwidget.debug.TOUCH_ZONES"
        const val EXTRA_ENABLED = "enabled"
    }
}
