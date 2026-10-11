package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceFetchGate
import com.weatherwidget.shared.sourceview.SourceLastView
import com.weatherwidget.shared.sourceview.SourceViewTally
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Android's inputs to [SourceFetchGate]: the enabled/displayed/primary sources from
 * [WidgetStateManager] and the switches from `source_view_days`. **Fails open**: if the table can't
 * be read, every enabled source is fetched, as before the gate existed.
 * performance/261010-fetch-only-sources-likely-to-be-viewed.md
 */
object SourceFetchGateLoader {
    private const val TAG = "SourceFetchGate"
    const val LOG_TAG = "SOURCE_FETCH_GATE"

    @Volatile
    private var lastLogged: Pair<LocalDate, String>? = null

    @androidx.annotation.VisibleForTesting
    internal fun resetLogMemoForTesting() {
        lastLogged = null
    }

    /** The background fetch at [latitude]/[longitude], or null when it couldn't be computed (fetch everything). */
    suspend fun load(
        context: Context,
        widgetStateManager: WidgetStateManager,
        latitude: Double,
        longitude: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): SourceFetchGate.BackgroundFetch? = try {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val database = WeatherDatabase.getDatabase(context)
        val dao = database.sourceViewDao()
        val gate = SourceFetchGate.backgroundFetch(
            enabled = widgetStateManager.getVisibleSourcesOrder(),
            displayedIds = widgetStateManager.getActiveDisplaySourceIds(),
            primaryId = widgetStateManager.getPrimarySource().id,
            rows = dao.getSince(SourceViewTally.dayMs(today.minusDays(SourceFetchGate.RECENT_VIEW_DAYS))).map { it.toRow() },
            trackingSince = dao.getTrackingStart()?.let { LocalDate.ofEpochDay(it / SourceViewTally.DAY_MS) },
            today = today,
            latitude = latitude,
            longitude = longitude,
            actualsPreference = { widgetStateManager.getActualsProvider(it) },
        )
        // Once a local day, and whenever the decision changes.
        val line = gate.logLine()
        if (lastLogged != today to line) {
            lastLogged = today to line
            database.appLogDao().log(LOG_TAG, line, "INFO")
        }
        gate
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "gate unavailable, fetching every source: ${e.message}")
        null
    }

    /**
     * Each source's last view and the tracking grace, for [com.weatherwidget.data.remote.HourlyCadence]
     * ([SourceLastView]). Fails open to [SourceLastView.Recency.UNKNOWN] (every source in grace).
     */
    suspend fun loadRecency(
        context: Context,
        sourceIds: Collection<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): SourceLastView.Recency = try {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val dao = WeatherDatabase.getDatabase(context).sourceViewDao()
        SourceLastView.recency(
            sourceIds = sourceIds,
            timestamps = dao.getLastViews().associate { it.sourceId to it.lastViewedAtMs },
            rows = dao.getSince(SourceViewTally.dayMs(today.minusDays(SourceFetchGate.RECENT_VIEW_DAYS))).map { it.toRow() },
            trackingSince = dao.getTrackingStart()?.let { LocalDate.ofEpochDay(it / SourceViewTally.DAY_MS) },
            today = today,
            zone = zone,
            nowMs = nowMs,
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "view recency unavailable, every source in grace: ${e.message}")
        SourceLastView.Recency.UNKNOWN
    }

    /** The background forecast set, or every enabled source when the gate is unavailable. */
    suspend fun backgroundSources(
        context: Context,
        widgetStateManager: WidgetStateManager,
        location: Pair<Double, Double>?,
    ): List<WeatherSource> {
        val enabled = widgetStateManager.getVisibleSourcesOrder()
        val gate = location?.let { load(context, widgetStateManager, it.first, it.second) } ?: return enabled
        return enabled.filter { it in gate.forecasts }
    }
}
