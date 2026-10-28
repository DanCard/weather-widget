package com.weatherwidget.debug

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import com.weatherwidget.BuildConfig
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetInteractionReceiver
import com.weatherwidget.widget.WidgetWorkScheduler

/**
 * adb entry points for a sync, so one can be driven without tapping the widget:
 *
 * ```
 * # Exactly the widget's Refresh tap: fetches only what is stale ("up to date" banner otherwise).
 * adb shell am broadcast -a com.weatherwidget.debug.REFRESH \
 *   -n com.weatherwidget/com.weatherwidget.debug.DebugSyncReceiver
 *
 * # A forced full sync of every source, fresh or not (SYNC_START force=true reason=debug_full_sync).
 * adb shell am broadcast -a com.weatherwidget.debug.FULL_SYNC \
 *   -n com.weatherwidget/com.weatherwidget.debug.DebugSyncReceiver
 * ```
 *
 * Neither re-implements a sync. REFRESH forwards to the real, non-exported [WidgetActionReceiver] as
 * the Refresh button's [WidgetActions.ACTION_REFRESH]; FULL_SYNC uses the app's own
 * [WidgetWorkScheduler.enqueueForcedSync] with KEEP, which joins a pending sync and never cancels a
 * running worker (AGENTS.md rule 6). FULL_SYNC makes billed requests for every enabled source.
 *
 * Two locks, as [DebugLocationReceiver]: it lives in `src/debug` (absent from a release manifest and
 * dex), and the manifest gates it on `android.permission.DUMP`, which the adb shell holds and
 * ordinary apps cannot, so even on a debug build no other app can trigger billed fetches.
 */
class DebugSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_REFRESH -> {
                appContext.sendBroadcast(
                    Intent(appContext, WidgetActionReceiver::class.java).apply {
                        action = WidgetActions.ACTION_REFRESH
                        putExtra(WidgetActions.EXTRA_UI_ONLY, false)
                    },
                )
                Log.i(TAG, "forwarded REFRESH as the widget's Refresh")
            }
            ACTION_FULL_SYNC -> {
                val enqueued = WidgetWorkScheduler.enqueueForcedSync(appContext, REASON, ExistingWorkPolicy.KEEP)
                Log.i(TAG, "enqueued forced full sync id=${enqueued.request.id} followUp=${enqueued.followUp}")
            }
            ACTION_TOGGLE_API -> {
                val widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
                appContext.sendBroadcast(
                    Intent(appContext, WidgetInteractionReceiver::class.java).apply {
                        action = WidgetActions.ACTION_TOGGLE_API
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    },
                )
                Log.i(TAG, "forwarded TOGGLE_API to WidgetInteractionReceiver widgetId=$widgetId")
            }
            else -> Log.w(TAG, "unhandled action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "DebugSyncReceiver"
        private const val REASON = "debug_full_sync"
        const val ACTION_REFRESH = "com.weatherwidget.debug.REFRESH"
        const val ACTION_FULL_SYNC = "com.weatherwidget.debug.FULL_SYNC"
        const val ACTION_TOGGLE_API = "com.weatherwidget.debug.TOGGLE_API"
    }
}
