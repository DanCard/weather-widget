package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.R

/**
 * "Getting weather for {place}…" floated over whatever the widget already shows, from the
 * setup-screen save until that change's forced sync ends (`LocationChangePaintPolicy.Feedback.BANNER`).
 * The mechanics are [FetchBanner]'s; this only owns the text.
 */
object LocationChangeBanner {
    const val MAX_SHOW_MS = FetchBanner.MAX_SHOW_MS

    fun message(context: Context, place: String): String =
        context.getString(R.string.widget_fetching_location, place)

    fun show(context: Context, place: String, ids: IntArray, nowMs: Long = System.currentTimeMillis()) =
        FetchBanner.show(context, message(context, place), ids, reason = "clear_location_change_banner", nowMs = nowMs)

    /** Drops this change's banner; called before the sync's final paint. Returns widgets cleared. */
    fun clear(context: Context, place: String, ids: IntArray): Int =
        FetchBanner.clear(context, message(context, place), ids)
}
