package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * One-time cleanup of the retired Google-HQ placeholder coordinates.
 *
 * The app used to write `37.4220, -122.0841` (Google HQ) as the "GPS never resolved" placeholder, and
 * `LocationUpdater.allWidgetsAtDefault` recognised *those exact coordinates* as the signal to keep
 * auto-healing. The placeholder is now the **absence** of finite coordinates, so an install that still
 * carries the old sentinel on disk would read as a legitimate, deliberately-chosen location:
 *
 *  - `allWidgetsAtDefault` would return false, so the GPS auto-heal would stop trying;
 *  - `ActiveLocationResolver.current()` would return it, so the no-location gate would never fire;
 *  - the user would be permanently pinned to Mountain View, with no error shown — strictly worse than
 *    the behaviour this change set out to fix.
 *
 * So the sentinel has to be actively erased once, at upgrade. These are the last two references to
 * those coordinates in the app; everything else now works in terms of "unset".
 *
 * **Comparison uses [LocationMatch.sameSite], never `==`.** `HourlyObservationBackfill` learned this
 * the hard way: its original guard compared the raw constant with `==`, but the coordinate flowing
 * through had been 3-dp quantized (−122.0841 → −122.084), so the guard silently missed and the fetch
 * proceeded at HQ. Prefs additionally round-trip through `Float`, losing precision independently.
 *
 * Runs from `WeatherWidgetApp.onCreate` and touches SharedPreferences only — no database, so it stays
 * clear of the eager-DB-open trap that breaks tests installing an in-memory database. The app_logs
 * row is deferred: the migration leaves a report in prefs and [consumePendingReport] hands it to the
 * worker, which already owns an `AppLogDao`.
 */
internal object LegacyDefaultLocationMigration {

    /** The retired hard default (Google HQ). Deleted once rollout telemetry says the migration has run. */
    private const val LEGACY_DEFAULT_LAT = 37.4220
    private const val LEGACY_DEFAULT_LON = -122.0841

    private const val WEATHER_PREFS_NAME = "weather_prefs"
    private const val KEY_MIGRATED = "legacy_default_cleared_v1"
    private const val KEY_PENDING_REPORT = "legacy_default_cleared_v1_report"

    data class Outcome(
        val alreadyRun: Boolean,
        val clearedActiveLocation: Boolean,
        val clearedWidgetIds: List<Int>,
    ) {
        val clearedCount: Int get() = (if (clearedActiveLocation) 1 else 0) + clearedWidgetIds.size
    }

    /**
     * Clears any persisted copy of the legacy sentinel. Idempotent and cheap: after the first run a
     * single boolean read short-circuits it.
     */
    fun runIfNeeded(context: Context): Outcome {
        val weatherPrefs = SharedPreferencesUtil.getPrefs(context, WEATHER_PREFS_NAME)
        if (weatherPrefs.getBoolean(KEY_MIGRATED, false)) {
            return Outcome(alreadyRun = true, clearedActiveLocation = false, clearedWidgetIds = emptyList())
        }

        val clearedActive = clearActiveLocationIfLegacy(context)
        val clearedWidgets = clearWidgetLocationsIfLegacy(context)

        val outcome = Outcome(
            alreadyRun = false,
            clearedActiveLocation = clearedActive,
            clearedWidgetIds = clearedWidgets,
        )
        val editor = weatherPrefs.edit().putBoolean(KEY_MIGRATED, true)
        if (outcome.clearedCount > 0) {
            editor.putString(KEY_PENDING_REPORT, formatReport(outcome))
        }
        editor.commit()
        return outcome
    }

    /**
     * Returns and clears the app_logs message left by [runIfNeeded], or null when there is nothing to
     * report. Called by the worker so the migration is durably observable without the migration itself
     * having to open the database.
     */
    fun consumePendingReport(context: Context): String? {
        val prefs = SharedPreferencesUtil.getPrefs(context, WEATHER_PREFS_NAME)
        val report = prefs.getString(KEY_PENDING_REPORT, null) ?: return null
        prefs.edit().remove(KEY_PENDING_REPORT).commit()
        return report
    }

    private fun formatReport(outcome: Outcome): String =
        "cleared=${outcome.clearedCount} active=${outcome.clearedActiveLocation} " +
            "widgets=${outcome.clearedWidgetIds.sorted().joinToString(",")}"

    private fun clearActiveLocationIfLegacy(context: Context): Boolean {
        val active = ActiveLocationResolver.current(context) ?: return false
        if (!isLegacyDefault(active.first, active.second)) return false
        ActiveLocationResolver.clear(context)
        return true
    }

    /**
     * Scans every `widget_lat_*` key rather than only the currently-placed widget ids: a removed
     * widget leaves its coordinates behind, and a later widget can be assigned the same id and inherit
     * them.
     */
    private fun clearWidgetLocationsIfLegacy(context: Context): List<Int> {
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        val widgetIds = prefs.all.keys
            .filter { it.startsWith(ConfigActivity.KEY_LAT_PREFIX) }
            .mapNotNull { it.removePrefix(ConfigActivity.KEY_LAT_PREFIX).toIntOrNull() }

        val cleared = mutableListOf<Int>()
        val editor = prefs.edit()
        widgetIds.forEach { id ->
            val lat = prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", Float.NaN)
            val lon = prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$id", Float.NaN)
            if (lat.isNaN() || lon.isNaN()) return@forEach
            if (!isLegacyDefault(lat.toDouble(), lon.toDouble())) return@forEach
            editor.remove("${ConfigActivity.KEY_LAT_PREFIX}$id")
            editor.remove("${ConfigActivity.KEY_LON_PREFIX}$id")
            cleared += id
        }
        if (cleared.isNotEmpty()) editor.commit()
        return cleared
    }

    private fun isLegacyDefault(lat: Double, lon: Double): Boolean =
        LocationMatch.sameSite(lat, lon, LEGACY_DEFAULT_LAT, LEGACY_DEFAULT_LON)
}
