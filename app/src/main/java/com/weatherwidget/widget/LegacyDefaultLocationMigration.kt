package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * One-time cleanup of the retired Google-HQ placeholder coordinates.
 *
 * The app used to write `37.4220, -122.0841` (Google HQ) as the "GPS never resolved" placeholder. The
 * placeholder is now the **absence** of finite coordinates, so an install that still carries the old
 * sentinel on disk would read as a legitimate, deliberately-chosen location:
 *
 *  - `ActiveLocationResolver.current()` would return it, so the no-location gate would never fire;
 *  - `GpsResampler` would compare each fresh fix against it and, for anyone near Mountain View,
 *    propose nothing;
 *  - the user would be permanently pinned there, with no error shown — strictly worse than the
 *    behaviour this change set out to fix.
 *
 * So the sentinel has to be actively erased once, at upgrade.
 *
 * **Prefs are not the only place it lives.** The first version of this migration cleared the two
 * preference copies and stopped, on the belief that they were the last references to those
 * coordinates. They were not: a month of `forecasts` rows carries them too, and
 * [ActiveLocationResolver.resolve] reads them back through a location-blind `getLatestWeather()` and
 * **re-persists** the result as canonical. The sentinel resurrected itself on the first worker run,
 * so v1 was a no-op for exactly the installs it targeted. The purge of those rows is deferred to the
 * worker (see [consumePendingReport]); until it runs, [isPurgePending] suppresses that fallback so no
 * `resolve()` call site can beat the purge to it.
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
    internal const val LEGACY_DEFAULT_LAT = 37.4220
    internal const val LEGACY_DEFAULT_LON = -122.0841

    private const val WEATHER_PREFS_NAME = "weather_prefs"

    /**
     * **v2 deliberately re-runs on installs that already ran v1.** v1 cleared the prefs but not the
     * forecast rows, so `resolve()` re-persisted the sentinel and those installs are sitting on it
     * again with `..._v1 = true`. Re-running finds it in the active-location prefs a second time and
     * this time the purge stops it coming back. An install that has since chosen a real location
     * matches nothing and no-ops, so the re-run is free for everyone else.
     */
    private const val KEY_MIGRATED = "legacy_default_cleared_v2"
    private const val KEY_PENDING_REPORT = "legacy_default_cleared_v2_report"

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
     * True between [runIfNeeded] clearing a sentinel and the worker purging the matching forecast
     * rows. While it holds, `ActiveLocationResolver.resolve()` must not fall back to the coordinates
     * of the latest cached weather — those rows are the sentinel's third hiding place, and two of
     * `resolve()`'s six call sites (`WidgetStartupCoordinator`, `WidgetRefreshContextResolver`) can
     * run from `onUpdate` or a widget tap before any worker does.
     *
     * A prefs read, deliberately: this is consulted on paths that must not open the database.
     */
    fun isPurgePending(context: Context): Boolean =
        SharedPreferencesUtil.getPrefs(context, WEATHER_PREFS_NAME).contains(KEY_PENDING_REPORT)

    /**
     * Returns and clears the app_logs message left by [runIfNeeded], or null when there is nothing to
     * report. Called by the worker so the migration is durably observable without the migration itself
     * having to open the database.
     *
     * Consuming this also ends [isPurgePending], so the caller must purge **first** — a report
     * consumed after a failed purge would re-enable the fallback with the rows still there.
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
