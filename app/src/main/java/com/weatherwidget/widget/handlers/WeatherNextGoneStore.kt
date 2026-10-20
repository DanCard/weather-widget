package com.weatherwidget.widget.handlers

import android.content.Context
import com.weatherwidget.data.remote.weathernext.WeatherNextAvailability

/**
 * When the WeatherNext host last answered 404/410 — the one fact
 * [WeatherNextAvailability.showPill] needs beyond the location. Written by the viewer, read by the
 * precipitation render.
 */
internal object WeatherNextGoneStore {
    private const val PREFS = "weathernext_prefs"
    private const val KEY_GONE_AT = "gone_at_ms"

    fun goneAtMs(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_GONE_AT, -1L).takeIf { it >= 0 }

    fun markGone(context: Context, nowMs: Long = System.currentTimeMillis()) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_GONE_AT, nowMs).apply()

    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_GONE_AT).apply()
}
