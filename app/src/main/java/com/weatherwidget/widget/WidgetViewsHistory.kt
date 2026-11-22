package com.weatherwidget.widget

import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * What the launcher was last given for each widget in this process: the last full
 * `updateAppWidget` tree and the `partiallyUpdateAppWidget` pushes since. Replaying the partials on
 * the full tree, in order, is how the launcher merges them, so a bug report can draw the widget as
 * it currently stands without triggering a repaint ([com.weatherwidget.ui.WidgetImageCapture]).
 *
 * Process lifetime only. Memory is bounded to about two graph bitmaps per widget (each up to
 * ~0.9 MB, `MAX_BITMAP_PIXELS`): a full push drops every partial, and a complete-body partial
 * (`bodyComplete`, which redraws the body including the graph) drops the partials before it, since
 * it overwrites everything they set. Only header-only partials accumulate after it; past
 * [MAX_PARTIALS] the oldest is dropped and [Snapshot.partialOverflow] is set.
 */
object WidgetViewsHistory {
    @VisibleForTesting
    internal const val MAX_PARTIALS = 16

    data class Snapshot(
        val full: RemoteViews,
        val partials: List<RemoteViews>,
        val fullCaller: String,
        val fullAtMs: Long,
        val partialOverflow: Boolean,
    )

    private class Entry(val full: RemoteViews, val caller: String, val atMs: Long) {
        val partials = ArrayDeque<RemoteViews>()
        var overflow = false
    }

    private val entries = ConcurrentHashMap<Int, Entry>()

    fun recordFull(appWidgetId: Int, views: RemoteViews, caller: String, atMs: Long = System.currentTimeMillis()) {
        entries[appWidgetId] = Entry(views, caller, atMs)
    }

    /** Ignored when no full tree is held: an unbacked partial can't be replayed onto anything. */
    fun recordPartial(appWidgetId: Int, views: RemoteViews, bodyComplete: Boolean) {
        val entry = entries[appWidgetId] ?: return
        synchronized(entry) {
            if (bodyComplete) {
                entry.partials.clear()
                entry.overflow = false
            }
            entry.partials.addLast(views)
            if (entry.partials.size > MAX_PARTIALS) {
                entry.partials.removeFirst()
                entry.overflow = true
            }
        }
    }

    fun snapshot(appWidgetId: Int): Snapshot? {
        val entry = entries[appWidgetId] ?: return null
        return synchronized(entry) {
            Snapshot(entry.full, entry.partials.toList(), entry.caller, entry.atMs, entry.overflow)
        }
    }

    fun forget(appWidgetId: Int) {
        entries.remove(appWidgetId)
    }

    @VisibleForTesting
    internal fun resetForTest() = entries.clear()
}
