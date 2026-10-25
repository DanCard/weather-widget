package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopForecastRow
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.shared.actuals.DailyHistoryMaintenance
import com.weatherwidget.shared.actuals.DailyHistoryRun

/**
 * Desktop SQLite side of [DailyHistoryRun]; markers are permanent app_logs rows. Its reads and writes
 * are pinned to Android's Room Host by `DailyHistoryHostContract` (`:shared` testFixtures).
 */
internal class DesktopDailyHistoryHost(private val weatherDao: DesktopWeatherDao) : DailyHistoryRun.Host {
    override suspend fun dailyHistory(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
        weatherDao.getExtremesInRange(startMs, endMs, latitude, longitude)

    override suspend fun forecastFetches(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
        weatherDao.getForecastFetchesInRange(startMs, endMs, latitude, longitude).map { it.toMaintenanceRow(latitude, longitude) }

    override suspend fun hourlyForecasts(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
        weatherDao.getHourlyForecastsAllSources(latitude, longitude, startMs, endMs)

    override suspend fun hourlyHistory(startMs: Long, endMs: Long, latitude: Double, longitude: Double, source: String) =
        weatherDao.getHourlyHistorySnapshots(latitude, longitude, source, startMs, endMs)

    override suspend fun insertDailyHistory(rows: List<DailyHistory>) = weatherDao.upsertDailyHistory(rows)

    override suspend fun updateForecastOverlay(row: DailyHistory) = weatherDao.updateForecastOverlay(row)

    override suspend fun updatePriorForecast(row: DailyHistory) = weatherDao.updatePriorForecast(row)

    override suspend fun isDone(marker: DailyHistoryRun.Marker) =
        weatherDao.getRecentLogsByTags(listOf(marker.doneTag), limit = 1).isNotEmpty()

    override suspend fun markDone(marker: DailyHistoryRun.Marker, summary: String) =
        weatherDao.log(marker.doneTag, summary, "INFO")

    override suspend fun lastUpkeepMs() =
        weatherDao.getRecentLogsByTags(listOf(DailyHistoryRun.UPKEEP_TAG), limit = 1).firstOrNull()?.timestamp ?: 0L

    // The UPKEEP_TAG row upkeepIfDue logs is the marker; app_logs keeps 72 h, past the 24 h interval.
    override suspend fun markUpkeep(nowMs: Long) = Unit

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)

    /** Flattens a desktop forecast row into the shared planner's row type. */
    private fun DesktopForecastRow.toMaintenanceRow(latitude: Double, longitude: Double) =
        DailyHistoryMaintenance.ForecastHistoryRow(
            dateMs = targetDate,
            source = source,
            locationLat = locationLat ?: latitude,
            locationLon = locationLon ?: longitude,
            highTemp = highTemp,
            lowTemp = lowTemp,
            precipAmountMm = precipAmountMm,
            condition = condition,
            fetchedAt = fetchedAt,
            isClimateNormal = isClimateNormal,
            precipProbability = precipProbability,
            daytimePrecipProbability = daytimePrecipProbability,
            nighttimePrecipProbability = nighttimePrecipProbability,
            lastConfirmedAt = lastConfirmedAt ?: fetchedAt,
        )

    companion object {
        const val CHANCE_BACKFILL_DONE_TAG = "CHANCE_BACKFILL_DONE"
        const val FROZEN_DISPLAY_BACKFILL_DONE_TAG = "FROZEN_DISPLAY_BACKFILL_DONE"

        private val DailyHistoryRun.Marker.doneTag: String
            get() = when (this) {
                DailyHistoryRun.Marker.CHANCE_BACKFILL -> CHANCE_BACKFILL_DONE_TAG
                DailyHistoryRun.Marker.FROZEN_DISPLAY_BACKFILL -> FROZEN_DISPLAY_BACKFILL_DONE_TAG
            }
    }
}
