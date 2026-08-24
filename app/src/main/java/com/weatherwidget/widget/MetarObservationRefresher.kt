package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.repository.MetarObservationSource
import com.weatherwidget.shared.util.MetarFetchPolicy

/**
 * Fetches raw airport METARs when the current run is a cadence they belong to, and stores them.
 *
 * METAR has no schedule of its own — [MetarFetchPolicy] derives its tier from what is visible and
 * what is displayed, and each caller declares which tiers ITS run covers:
 *
 * | caller | tiers accepted | cadence it rides |
 * |---|---|---|
 * | current-temp branch | `PRIMARY` | `CurrentTempFetchPolicy` — 10 / 16 min charging, 45 min opportunistic above 65 % |
 * | non-primary branch | `NON_PRIMARY` | `NonPrimaryObservationPolicy` — 30 min, charging AND screen-on |
 * | full sync | **both** | the battery-aware main fetch (60–480 min), plus every user-initiated refresh and source toggle |
 *
 * The full sync accepts both tiers deliberately. Measured 2026-08-23 on emulator-5554: hitting
 * "refresh data" and toggling the displayed source BOTH log `SYNC_START force=true`, and the
 * scheduled data fetch is the only path that reliably runs at all. Restricting METAR to the two
 * current-temp loops left it never fetching in practice — `NonPrimaryObservationPolicy` returns null
 * unless charging AND screen-on, and its loop's only dependable kickstart is a plug-in event because
 * `ScreenOnReceiver` never fires ([[screenonreceiver_implicit_broadcasts_dead]]).
 *
 * Every caller invokes this from INSIDE its own already-gated fetch block, so charging, screen-state
 * and battery rules are inherited rather than re-implemented, and no new wakeup is introduced.
 */
internal class MetarObservationRefresher(
    private val context: Context,
    private val source: MetarObservationSource,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
) {
    /** The tier this location currently sits in, or NONE when nothing visible would read the rows. */
    fun currentTier(): MetarFetchPolicy.Tier {
        val activeSourceIds = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
            .map { widgetStateManager.getCurrentDisplaySource(it).id }
            .distinct()
            .toSet()
        return MetarFetchPolicy.tierFor(widgetStateManager.getVisibleSourcesOrder(), activeSourceIds)
    }

    /**
     * Fetches and stores when [currentTier] is one of [acceptTiers]. Never throws: the displayed
     * source has already fetched by the time this runs and must still paint, so a failure in the
     * supplementary feed is logged and swallowed.
     */
    suspend fun refreshIfDue(
        acceptTiers: Set<MetarFetchPolicy.Tier>,
        latitude: Double,
        longitude: Double,
        reason: String,
    ) {
        val tier = currentTier()
        if (tier !in acceptTiers) return
        try {
            val rows = source.fetchObservations(latitude, longitude)
            if (rows.isEmpty()) return
            WeatherDatabase.getDatabase(context).observationDao().insertAll(rows)
            appLogDao.log(
                "METAR_OBS_STORED",
                "reason=$reason tier=${tier.name} rows=${rows.size} " +
                    "stations=${rows.map { it.stationId }.distinct().joinToString("|")}",
                "INFO",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.logException("METAR_OBS_FAIL", "reason=$reason tier=${tier.name}", e)
        }
    }
}
