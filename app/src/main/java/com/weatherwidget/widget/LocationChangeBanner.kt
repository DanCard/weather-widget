package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R

/**
 * "Getting weather for {place}…" floated over whatever the widget already shows, from the
 * setup-screen save until that change's forced sync ends (`LocationChangePaintPolicy.Feedback.BANNER`).
 *
 * It rides the widget's existing transient-message banner, so every view handler's full render
 * re-binds it until it is cleared or expires. [show] also pushes a banner-only partial update so
 * it appears at once over the previous site's render — the full repaint may be a startup-cooldown
 * deferral (up to 30 s) away. A partial the launcher drops (no cached views after a reboot or
 * reinstall) costs only immediacy: the next full render binds the message from state.
 */
object LocationChangeBanner {
    private const val TAG = "LocationChangeBanner"

    /** Safety cap: a sync that never reports back (process killed, work cancelled) can't pin the banner. */
    const val MAX_SHOW_MS = 120_000L

    fun message(context: Context, place: String): String =
        context.getString(R.string.widget_fetching_location, place)

    fun show(context: Context, place: String, ids: IntArray, nowMs: Long = System.currentTimeMillis()) {
        val text = message(context, place)
        val stateManager = WidgetStateManager(context)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        ids.forEach { id ->
            stateManager.setTransientMessage(id, text, nowMs + MAX_SHOW_MS)
            push(context, appWidgetManager, id, text)
            WidgetWorkScheduler.enqueueDelayedUiRepaint(
                context = context,
                appWidgetId = id,
                reason = "clear_location_change_banner",
                initialDelayMs = MAX_SHOW_MS + WidgetTransientMessagePolicy.CLEAR_BUFFER_MS,
            )
        }
        Log.d(TAG, "show place=$place widgets=${ids.size}")
    }

    /**
     * Drops this change's banner (only if it is still the active message — a day-tap notice raised
     * meanwhile is not ours to clear) and hides it on screen. Called before the sync's final paint,
     * so that render binds the banner GONE too. Returns how many widgets it cleared.
     */
    fun clear(context: Context, place: String, ids: IntArray): Int {
        val text = message(context, place)
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
