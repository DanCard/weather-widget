package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.shared.util.UsageHours
import com.weatherwidget.util.SharedPreferencesUtil
import java.time.ZoneId

/**
 * Records when the device is used, per hour of the week ([UsageHours]), and logs it once a day.
 * **Phase 1: log-only** — nothing reads it to decide a fetch
 * (performance/261019-android-usage-hours-slow-fetching-when-unused.md).
 *
 * Sightings: a screen-on heard while the process is alive, a worker run that finds the screen on,
 * a widget action, an activity coming to the front. Prefs only, aggregates only — no timestamps.
 */
internal object UsageHoursTracker {
    private const val PREFS_NAME = "usage_hours_prefs"
    private const val KEY_STATE = "state"
    private const val KEY_LOGGED_CHECK = "logged_check"
    private const val KEY_HOURS_LOG_DATE = "hours_log_date"
    const val HOURS_LOG_TAG = "USAGE_HOURS"
    const val CHECK_LOG_TAG = "USAGE_PREDICTION_CHECK"

    @Synchronized
    fun sight(context: Context, nowMs: Long = System.currentTimeMillis()) {
        update(context) { UsageHours.sight(it, nowMs, ZoneId.systemDefault()) }
    }

    /** Rolls completed hours forward without a sighting (they count as unused). */
    @Synchronized
    fun advance(context: Context, nowMs: Long = System.currentTimeMillis()) {
        update(context) { UsageHours.advance(it, nowMs, ZoneId.systemDefault()) }
    }

    /**
     * The day's log lines, each at most once: `USAGE_HOURS` once per local day, and the last completed
     * day's `USAGE_PREDICTION_CHECK` once. Advances the table first.
     */
    @Synchronized
    fun dailyLogLines(context: Context, nowMs: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val state = update(context) { UsageHours.advance(it, nowMs, ZoneId.systemDefault()) }
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val lines = mutableListOf<Pair<String, String>>()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (prefs.getString(KEY_HOURS_LOG_DATE, null) != today) {
            prefs.edit().putString(KEY_HOURS_LOG_DATE, today).apply()
            lines += HOURS_LOG_TAG to UsageHours.hoursLogLine(state)
        }
        UsageHours.checkLogLine(state)?.takeIf { it != prefs.getString(KEY_LOGGED_CHECK, null) }?.let {
            prefs.edit().putString(KEY_LOGGED_CHECK, it).apply()
            lines += CHECK_LOG_TAG to it
        }
        return lines
    }

    fun state(context: Context): UsageHours.State =
        UsageHours.decode(SharedPreferencesUtil.getPrefs(context, PREFS_NAME).getString(KEY_STATE, null)) ?: UsageHours.initial()

    private fun update(context: Context, change: (UsageHours.State) -> UsageHours.State): UsageHours.State {
        val next = change(state(context))
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).edit().putString(KEY_STATE, UsageHours.encode(next)).apply()
        return next
    }
}
