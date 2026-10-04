package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.insertAllRetaggingStationTypes
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.SynopticObservationSource
import com.weatherwidget.shared.observations.PersonalStationThinning
import com.weatherwidget.shared.util.SynopticBackoffStore
import com.weatherwidget.shared.util.SynopticFetchGate
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
        /**
         * Process-wide single-flight / freshness state. This refresher (and so its gate) is rebuilt
         * for every `WeatherWidgetWorker` run; per-instance state never saw the overlapping run.
         */
        internal val FLIGHTS = SynopticFetchGate.Flights()
        private const val BACKOFF_PREFS = "synoptic_fetch_backoff"
        private const val KEY_FAIL_STREAK = "fail_streak"
        private const val KEY_BACKOFF_UNTIL_MS = "backoff_until_ms"
    }

    /** Backoff state in `synoptic_fetch_backoff` prefs; the state machine is shared with desktop. */
    private val gate = SynopticFetchGate(
        store = object : SynopticBackoffStore {
            private val prefs = context.getSharedPreferences(BACKOFF_PREFS, Context.MODE_PRIVATE)
            override fun failStreak() = prefs.getInt(KEY_FAIL_STREAK, 0)
            override fun backoffUntilMs() = prefs.getLong(KEY_BACKOFF_UNTIL_MS, 0L)
            override fun save(failStreak: Int, backoffUntilMs: Long) {
                prefs.edit().putInt(KEY_FAIL_STREAK, failStreak).putLong(KEY_BACKOFF_UNTIL_MS, backoffUntilMs).apply()
            }
        },
        log = { tag, message, level -> appLogDao.log(tag, message, level) },
        flights = FLIGHTS,
    )

    /**
     * Fetches Synoptic observations for the gap since the newest stored row at this site (see
     * [com.weatherwidget.shared.util.SynopticFetchWindow]), and stores them.
     */
    suspend fun refreshIfDue(
        acceptTiers: Set<SynopticFetchPolicy.Tier>,
        latitude: Double,
        longitude: Double,
        reason: String,
        /** The forced sync of a setup-screen location change; see [com.weatherwidget.shared.util.SynopticBackoff.shouldSkip]. */
        userLocationChange: Boolean = false,
    ) {
        val tier = currentTier()
        if (tier !in acceptTiers) return
        try {
            val recentMinutes = fetchWindowMinutes(latitude, longitude)
            val outcome = gate.run(
                context = "reason=$reason tier=${tier.name}",
                userLocationChange = userLocationChange,
                siteKey = SynopticFetchGate.siteKey(latitude, longitude),
            ) {
                source.fetchObservationsResult(
                    latitude,
                    longitude,
                    recentMinutes = recentMinutes,
                )
            } ?: return
            if (outcome !is FetchOutcome.Success) return
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
            WeatherDatabase.getDatabase(context).observationDao().insertAllRetaggingStationTypes(rows)
            appLogDao.log(
                "SYNOPTIC_OBS_STORED",
                "reason=$reason tier=${tier.name} recentMin=$recentMinutes rows=${rows.size} " +
                    "fetched=${fetched.size} thinned=${fetched.size - rows.size} " +
                    "stations=${rows.map { it.stationId }.distinct().joinToString("|")}",
                "INFO",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.logException("SYNOPTIC_OBS_FAIL", "reason=$reason tier=${tier.name}", e)
        }
    }

    /**
     * `recent=` minutes for this site: the gap since the newest stored SYNOPTIC row, or the deep
     * window when there is none (first run, new location, long gap). See
     * [com.weatherwidget.shared.util.SynopticFetchWindow].
     */
    private suspend fun fetchWindowMinutes(latitude: Double, longitude: Double): Long {
        val newest = WeatherDatabase.getDatabase(context).observationDao()
            .getNewestTimestampForApi(WeatherSource.SYNOPTIC.id, latitude, longitude)
        return com.weatherwidget.shared.util.SynopticFetchWindow
            .recentMinutes(newest, System.currentTimeMillis())
            .toLong()
    }
}
