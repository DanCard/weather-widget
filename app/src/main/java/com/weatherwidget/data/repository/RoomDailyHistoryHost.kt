package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.shared.actuals.DailyHistoryRun
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * Room + prefs side of [DailyHistoryRun]. Its reads and writes are pinned to desktop's SQLite Host
 * by `DailyHistoryHostContract` (`:shared` testFixtures).
 */
internal class RoomDailyHistoryHost(
    context: Context,
    private val forecastDao: ForecastDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val hourlyForecastHistoryDao: HourlyForecastHistoryDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
) : DailyHistoryRun.Host {
    private val prefs by lazy {
        SharedPreferencesUtil.getPrefs(context, "weather_prefs")
    }

    override suspend fun dailyHistory(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
        dailyHistoryDao.getExtremesInRange(startMs, endMs, latitude, longitude).map { it.toDailyHistory() }

    override suspend fun forecastFetches(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
        forecastDao.getAllForecastsInRange(startMs, endMs, latitude, longitude).map { it.toMaintenanceRow() }

    override suspend fun hourlyForecasts(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
        hourlyForecastDao.getHourlyForecasts(startMs, endMs, latitude, longitude).map { it.toHourlyForecast() }

    override suspend fun hourlyHistory(startMs: Long, endMs: Long, latitude: Double, longitude: Double, source: String) =
        hourlyForecastHistoryDao.getHistoryInRangeForBucketWindow(
            startDateTime = startMs,
            endDateTime = endMs,
            bucketStart = Long.MIN_VALUE,
            bucketEnd = Long.MAX_VALUE,
            lat = latitude,
            lon = longitude,
            source = source,
        ).map {
            HourlyForecastEntity(
                dateTime = it.dateTime,
                locationLat = it.locationLat,
                locationLon = it.locationLon,
                temperature = it.temperature,
                condition = it.condition,
                source = it.source,
                precipProbability = it.precipProbability,
                cloudCover = it.cloudCover,
                cloudCoverLow = it.cloudCoverLow,
                cloudCoverMid = it.cloudCoverMid,
                cloudCoverHigh = it.cloudCoverHigh,
                precipAmountMm = it.precipAmountMm,
                fetchedAt = it.fetchedAt,
            ).toHourlyForecast()
        }

    override suspend fun insertDailyHistory(rows: List<DailyHistory>) {
        dailyHistoryDao.insertAll(rows.map { it.toEntity() })
    }

    override suspend fun updateForecastOverlay(row: DailyHistory) {
        dailyHistoryDao.updateForecastOverlay(
            date = row.date,
            source = row.source,
            locationLat = row.locationLat,
            locationLon = row.locationLon,
            forecastHighTemp = row.forecastHighTemp,
            forecastLowTemp = row.forecastLowTemp,
            lastWriter = row.lastWriter,
        )
    }

    override suspend fun updatePriorForecast(row: DailyHistory) {
        dailyHistoryDao.updatePriorForecast(
            date = row.date,
            source = row.source,
            locationLat = row.locationLat,
            locationLon = row.locationLon,
            priorForecastHighTemp = row.priorForecastHighTemp,
            priorForecastLowTemp = row.priorForecastLowTemp,
            lastWriter = row.lastWriter,
        )
    }

    override suspend fun isDone(marker: DailyHistoryRun.Marker) = prefs.getBoolean(marker.prefKey, false)

    override suspend fun markDone(marker: DailyHistoryRun.Marker, summary: String) {
        appLogDao.log(marker.name, summary, "INFO")
        prefs.edit().putBoolean(marker.prefKey, true).apply()
    }

    override suspend fun lastUpkeepMs() = prefs.getLong(PREF_UPKEEP_LAST_MS, 0L)

    override suspend fun markUpkeep(nowMs: Long) {
        prefs.edit().putLong(PREF_UPKEEP_LAST_MS, nowMs).apply()
    }

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)

    private val DailyHistoryRun.Marker.prefKey: String
        get() = when (this) {
            DailyHistoryRun.Marker.CHANCE_BACKFILL -> PREF_CHANCE_BACKFILL_DONE
            DailyHistoryRun.Marker.FROZEN_DISPLAY_BACKFILL -> PREF_FROZEN_DISPLAY_BACKFILL_DONE
        }

    private companion object {
        const val PREF_CHANCE_BACKFILL_DONE = "rain_chance_backfill_done"
        const val PREF_UPKEEP_LAST_MS = "daily_history_upkeep_last_ms"
        const val PREF_FROZEN_DISPLAY_BACKFILL_DONE = "frozen_display_backfill_done"
    }
}
