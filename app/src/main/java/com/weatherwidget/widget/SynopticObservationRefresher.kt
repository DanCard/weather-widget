package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.repository.SynopticObservationSource
import com.weatherwidget.shared.observations.PersonalStationThinning
import com.weatherwidget.shared.util.SynopticBackoff
import com.weatherwidget.shared.util.SynopticFetchPolicy

/**
 * Fetches Synoptic observations when the current run is a cadence they belong to, and stores them.
 */
internal class SynopticObservationRefresher(
    private val context: Context,
    private val source: SynopticObservationSource,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
) {
    fun currentTier(): SynopticFetchPolicy.Tier {
        val activeSourceIds = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
            .map { widgetStateManager.getCurrentDisplaySource(it).id }
            .distinct()
            .toSet()
        return SynopticFetchPolicy.tierFor(
            visibleSources = widgetStateManager.getVisibleSourcesOrder(),
            activeDisplaySourceIds = activeSourceIds,
            actualsPreference = { widgetStateManager.getActualsProvider(it) },
        )
    }

    companion object {
        const val SHALLOW_HOURS = 2
        const val DEEP_HOURS = 24
        private const val BACKOFF_PREFS = "synoptic_fetch_backoff"
        private const val KEY_FAIL_STREAK = "fail_streak"
        private const val KEY_BACKOFF_UNTIL_MS = "backoff_until_ms"
    }

    suspend fun refreshIfDue(
        acceptTiers: Set<SynopticFetchPolicy.Tier>,
        latitude: Double,
        longitude: Double,
        reason: String,
        hours: Int = SHALLOW_HOURS,
    ) {
        val tier = currentTier()
        if (tier !in acceptTiers) return
        val prefs = context.getSharedPreferences(BACKOFF_PREFS, Context.MODE_PRIVATE)
        val nowMs = System.currentTimeMillis()
        val backoffUntilMs = prefs.getLong(KEY_BACKOFF_UNTIL_MS, 0L)
        if (nowMs < backoffUntilMs) {
            appLogDao.log(
                "SYNOPTIC_FETCH_BACKOFF_SKIP",
                "reason=$reason tier=${tier.name} " +
                    "streak=${prefs.getInt(KEY_FAIL_STREAK, 0)} " +
                    "backoffRemainingMin=${(backoffUntilMs - nowMs) / 60_000}",
                "DEBUG",
            )
            return
        }
        try {
            val outcome = source.fetchObservationsResult(latitude, longitude, hours = hours)
            when (outcome) {
                // A server answer of any kind proves the quota/network is back; clear the streak.
                is FetchOutcome.Success -> {
                    val fetched = outcome.value
                    if (fetched.isEmpty()) return
                    // Personal stations report every ~5 min, are weighted 0.05 and never carry sky; storing
                    // them at full density made them 80% of the Synoptic rows on the reporting device, and
                    // the table's size is what a cold read pays for. See PersonalStationThinning.
                    val rows = PersonalStationThinning.thin(
                        rows = fetched,
                        stationOf = { it.stationId },
                        stationTypeOf = { it.stationType },
                        timestampOf = { it.timestamp },
                    )
                    WeatherDatabase.getDatabase(context).observationDao().insertAll(rows)
                    prefs.edit().putInt(KEY_FAIL_STREAK, 0).putLong(KEY_BACKOFF_UNTIL_MS, 0L).apply()
                    appLogDao.log(
                        "SYNOPTIC_OBS_STORED",
                        "reason=$reason tier=${tier.name} hours=$hours rows=${rows.size} " +
                            "fetched=${fetched.size} thinned=${fetched.size - rows.size} " +
                            "stations=${rows.map { it.stationId }.distinct().joinToString("|")}",
                        "INFO",
                    )
                }
                is FetchOutcome.NoData -> {
                    prefs.edit().putInt(KEY_FAIL_STREAK, 0).putLong(KEY_BACKOFF_UNTIL_MS, 0L).apply()
                }
                is FetchOutcome.Failed -> {
                    // The fetcher already logged SYNOPTIC_FETCH_FAIL; this row tracks the escalating
                    // backoff so a long outage is visible from app_logs alone (see SynopticBackoff for
                    // the 2026-09-08 quota outage that motivated it).
                    val streak = prefs.getInt(KEY_FAIL_STREAK, 0) + 1
                    val backoffMs = SynopticBackoff.backoffFor(streak)
                    prefs.edit()
                        .putInt(KEY_FAIL_STREAK, streak)
                        .putLong(KEY_BACKOFF_UNTIL_MS, nowMs + backoffMs)
                        .apply()
                    appLogDao.log(
                        "SYNOPTIC_FETCH_BACKOFF_SET",
                        "reason=$reason tier=${tier.name} streak=$streak " +
                            "backoffMin=${backoffMs / 60_000} error=${outcome.reason}",
                        "DEBUG",
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.logException("SYNOPTIC_OBS_FAIL", "reason=$reason tier=${tier.name}", e)
        }
    }
}
