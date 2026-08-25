package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.repository.SynopticObservationSource
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
        try {
            val rows = source.fetchObservations(latitude, longitude, hours = hours)
            if (rows.isEmpty()) return
            WeatherDatabase.getDatabase(context).observationDao().insertAll(rows)
            appLogDao.log(
                "SYNOPTIC_OBS_STORED",
                "reason=$reason tier=${tier.name} hours=$hours rows=${rows.size} " +
                    "stations=${rows.map { it.stationId }.distinct().joinToString("|")}",
                "INFO",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.logException("SYNOPTIC_OBS_FAIL", "reason=$reason tier=${tier.name}", e)
        }
    }
}
