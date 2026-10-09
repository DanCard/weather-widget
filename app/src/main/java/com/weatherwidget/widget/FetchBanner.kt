package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R

/**
 * A "getting weather…" message floated over whatever the widget already shows, until the forced sync
 * that owes it ends. Used by a setup-screen location change ([LocationChangeBanner]) and by a source
 * becoming primary ([SourceSwitchFetch]); each clears only its own text.
 *
 * It rides the widget's existing transient-message banner, so every view handler's full render
 * re-binds it until it is cleared or expires. [show] also pushes a banner-only partial update so it
 * appears at once — the full repaint may be a startup-cooldown deferral away. A partial the launcher
 * drops (no cached views after a reboot or reinstall) costs only immediacy: the next full render
 * binds the message from state.
 */
object FetchBanner {
    private const val TAG = "FetchBanner"

    /** Safety cap: a sync that never reports back (process killed, work cancelled) can't pin the banner. */
    const val MAX_SHOW_MS = 120_000L

    fun show(
        context: Context,
        text: String,
        ids: IntArray,
        reason: String,
        nowMs: Long = System.currentTimeMillis(),
        /** How long it may show; the default is the safety cap for a sync that owes a clear. */
        showMs: Long = MAX_SHOW_MS,
    ) {
        val stateManager = WidgetStateManager(context)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        ids.forEach { id ->
            stateManager.setTransientMessage(id, text, nowMs + showMs)
            push(context, appWidgetManager, id, text)
            WidgetWorkScheduler.enqueueDelayedUiRepaint(
                context = context,
                appWidgetId = id,
                reason = reason,
                initialDelayMs = showMs + WidgetTransientMessagePolicy.CLEAR_BUFFER_MS,
            )
        }
        Log.d(TAG, "show text=$text widgets=${ids.size}")
    }

    /**
     * Drops [text] (only if it is still the active message — a notice raised meanwhile is not ours to
     * clear) and hides it on screen. Returns how many widgets it cleared.
     */
    fun clear(context: Context, text: String, ids: IntArray): Int {
        val stateManager = WidgetStateManager(context)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        var cleared = 0
        ids.forEach { id ->
            if (stateManager.getActiveTransientMessage(id) == text) {
                stateManager.clearTransientMessage(id)
                push(context, appWidgetManager, id, null)
                cleared++
            }
        }
        return cleared
    }

    private fun push(context: Context, appWidgetManager: AppWidgetManager, id: Int, text: String?) {
        runCatching {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            if (text != null) {
                views.setTextViewText(R.id.widget_message_banner, text)
                views.setViewVisibility(R.id.widget_message_banner, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_message_banner, View.GONE)
            }
            appWidgetManager.partiallyUpdateAppWidget(id, views)
        }.onFailure { Log.w(TAG, "banner push failed widget=$id: ${it.message}") }
    }
}
