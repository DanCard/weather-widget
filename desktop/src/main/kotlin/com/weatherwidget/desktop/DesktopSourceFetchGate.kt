package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceFetchGate
import com.weatherwidget.shared.sourceview.SourceViewTally
import com.weatherwidget.shared.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Desktop's inputs to [SourceFetchGate] — the daemon's background loops (3c forecasts, 3d
 * observations) fetch only the non-displayed sources it keeps. Reads `source_view_days` from the
 * shared `weather.db`. **Fails open**: if the table can't be read, every usable source is fetched.
 * performance/261010-fetch-only-sources-likely-to-be-viewed.md
 */
internal object DesktopSourceFetchGate {
    private const val TAG = "DesktopSourceFetchGate"
    const val LOG_TAG = "SOURCE_FETCH_GATE"

    @Volatile
    private var lastLogged: Pair<LocalDate, String>? = null

    internal fun resetLogMemoForTesting() {
        lastLogged = null
    }

    fun load(dao: DesktopWeatherDao, config: DesktopConfig, nowMs: Long = System.currentTimeMillis()): SourceFetchGate.BackgroundFetch? =
        try {
            val zone = ZoneId.systemDefault()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val enabled = config.effectiveSources.map(WeatherSource::fromId)
            val gate = SourceFetchGate.backgroundFetch(
                enabled = enabled,
                displayedIds = setOf(config.displaySource),
                primaryId = enabled.firstOrNull()?.id,
                rows = dao.sourceViewDaysSince(SourceViewTally.dayMs(today.minusDays(SourceFetchGate.RECENT_VIEW_DAYS))),
                trackingSince = dao.sourceViewTrackingStartMs()?.let { LocalDate.ofEpochDay(it / SourceViewTally.DAY_MS) },
                today = today,
                latitude = config.lat,
                longitude = config.lon,
            )
            val line = gate.logLine()
            if (lastLogged != today to line) {
                lastLogged = today to line
                dao.log(LOG_TAG, line, "INFO")
            }
            gate
        } catch (e: Exception) {
            Log.w(TAG, "gate unavailable, fetching every source: ${e.message}")
            null
        }

    /** Non-displayed sources the background loops fetch, in the usable order; every one when the gate is unavailable. */
    fun otherSources(dao: DesktopWeatherDao, config: DesktopConfig, nowMs: Long = System.currentTimeMillis()): List<String> {
        val others = config.effectiveSources.filter { it != config.displaySource }
        val gate = load(dao, config, nowMs) ?: return others
        val kept = gate.forecasts.mapTo(HashSet()) { it.id }
        return others.filter { it in kept }
    }
}
