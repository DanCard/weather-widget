package com.weatherwidget.widget.handlers

import android.content.Context
import com.weatherwidget.data.remote.weathernext.WeatherNextAvailability

/**
 * When the WeatherNext host last answered 404/410, and which. Written and read by the viewer: within
 * [WeatherNextAvailability.knownGone] it shows the gone message without asking the host again.
 */
internal object WeatherNextGoneStore {
    private const val PREFS = "weathernext_prefs"
    private const val KEY_GONE_AT = "gone_at_ms"
    private const val KEY_GONE_STATUS = "gone_status"

    fun goneAtMs(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_GONE_AT, -1L).takeIf { it >= 0 }

    fun goneStatus(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_GONE_STATUS, 404)

    fun markGone(context: Context, status: Int, nowMs: Long = System.currentTimeMillis()) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_GONE_AT, nowMs).putInt(KEY_GONE_STATUS, status).apply()

    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_GONE_AT).remove(KEY_GONE_STATUS).apply()
}
