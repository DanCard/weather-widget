package com.weatherwidget.data.repository

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.shared.actuals.DailyHistoryMaintenance
import com.weatherwidget.shared.actuals.DailyHistoryRun
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.shared.util.FrozenRainChanceRepair
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WidgetConstants
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "DailyHistorySnapshotter"

/**
 * Owns daily-history freeze windows and one-time repair/backfill policies.
 *
 * The passes are [DailyHistoryRun] (`:shared`), the same run desktop makes; this class is its Room
 * and prefs [DailyHistoryRun.Host]. [repairFrozenRainChanceIfNeeded] is Android-only (a
 * one-time repair of rows written before the site-aware chance resolution existed).
 */
internal class DailyHistorySnapshotter(
    context: Context,
    private val forecastDao: ForecastDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val hourlyForecastHistoryDao: HourlyForecastHistoryDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
) {
    private val prefs by lazy {
        SharedPreferencesUtil.getPrefs(context, "weather_prefs")
    }

    private val run = DailyHistoryRun(RoomHost())

    /** [DailyHistoryRun.afterFetch]. */
    suspend fun afterFetch(latitude: Double, longitude: Double) =
        run.afterFetch(latitude, longitude, System.currentTimeMillis())

    /** [DailyHistoryRun.upkeepIfDue]. */
    suspend fun upkeepIfDue(latitude: Double, longitude: Double, isCharging: Boolean) =
        run.upkeepIfDue(latitude, longitude, System.currentTimeMillis(), isCharging)

    /** [DailyHistoryRun.ensureForecastOnlyHistoryRows]. */
    suspend fun ensureForecastOnlyHistoryRows(latitude: Double, longitude: Double) =
        run.ensureForecastOnlyHistoryRows(latitude, longitude, System.currentTimeMillis())

    /** [DailyHistoryRun.settlePastForecastOverlays]. */
    suspend fun settlePastForecastOverlays(latitude: Double, longitude: Double) =
        run.settlePastForecastOverlays(latitude, longitude, System.currentTimeMillis())

    /** [DailyHistoryRun.snapshotDisplayedRainChance]. */
    suspend fun snapshotDisplayedRainChance(latitude: Double, longitude: Double) =
        run.snapshotDisplayedRainChance(latitude, longitude, System.currentTimeMillis())

    suspend fun repairFrozenRainChanceIfNeeded(
        latitude: Double,
        longitude: Double,
    ) {
        if (prefs.getBoolean(PREF_CHANCE_REPAIR_DONE, false)) return
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val startMs = today.minusDays(CHANCE_BACKFILL_LOOKBACK_DAYS)
            .toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val rows = dailyHistoryDao.getExtremesInRange(
            startMs,
            endMs,
            latitude,
            longitude,
        ).filter {
            it.forecastDayPrecipChance != null ||
                it.forecastNightPrecipChance != null
        }
        if (rows.isEmpty()) {
            prefs.edit().putBoolean(PREF_CHANCE_REPAIR_DONE, true).apply()
            return
        }

        val toInsert = mutableListOf<DailyHistoryEntity>()
        for (row in rows) {
            val date = LocalDate.ofEpochDay(row.date / WidgetConstants.MS_IN_A_DAY)
            val history = hourlyForecastHistoryDao.getHistoryInRangeAllSnapshots(
                startDateTime = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                endDateTime = date.plusDays(1).atTime(8, 0).atZone(zoneId)
                    .toInstant().toEpochMilli(),
                lat = latitude,
                lon = longitude,
            ).map { it.toHourlyForecast() }
            if (history.isEmpty()) continue

            val rederived = FrozenRainChanceRepair.rederive(
                history = history,
                displaySourceId = row.source,
                centerLat = latitude,
                centerLon = longitude,
                date = date,
                zoneId = zoneId,
            )
            val newDay = rederived.dayPrecip ?: row.forecastDayPrecipChance
            val newNight = rederived.nightPrecip ?: row.forecastNightPrecipChance
            if (
                newDay == row.forecastDayPrecipChance &&
                newNight == row.forecastNightPrecipChance
            ) {
                continue
            }
            appLogDao.log(
                "RAIN_CHANCE_REPAIR",
                "date=$date src=${row.source} " +
                    "day=${row.forecastDayPrecipChance}->$newDay " +
                    "night=${row.forecastNightPrecipChance}->$newNight",
                "INFO",
            )
            toInsert.add(
                row.copy(
                    lastWriter = DailyHistoryWriter.FORECAST_FREEZE.storedValue,
                    forecastDayPrecipChance = newDay,
                    forecastNightPrecipChance = newNight,
                ),
            )
        }
        if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
        prefs.edit().putBoolean(PREF_CHANCE_REPAIR_DONE, true).apply()
    }

    /** [DailyHistoryRun.backfillForecastChanceSnapshotsIfNeeded]. */
    suspend fun backfillForecastChanceSnapshotsIfNeeded(latitude: Double, longitude: Double) =
        run.backfillForecastChanceSnapshotsIfNeeded(latitude, longitude, System.currentTimeMillis())

    /** [DailyHistoryRun.backfillFrozenDisplayColumnsIfNeeded]. */
    suspend fun backfillFrozenDisplayColumnsIfNeeded(latitude: Double, longitude: Double) =
        run.backfillFrozenDisplayColumnsIfNeeded(latitude, longitude, System.currentTimeMillis())

    /** Room + prefs side of [DailyHistoryRun]. */
    private inner class RoomHost : DailyHistoryRun.Host {
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
    }

    private val DailyHistoryRun.Marker.prefKey: String
        get() = when (this) {
            DailyHistoryRun.Marker.CHANCE_BACKFILL -> PREF_CHANCE_BACKFILL_DONE
            DailyHistoryRun.Marker.FROZEN_DISPLAY_BACKFILL -> PREF_FROZEN_DISPLAY_BACKFILL_DONE
        }

    companion object {
        private const val PREF_CHANCE_BACKFILL_DONE = "rain_chance_backfill_done"
        private const val PREF_UPKEEP_LAST_MS = "daily_history_upkeep_last_ms"
        private const val PREF_CHANCE_REPAIR_DONE = "rain_chance_site_repair_done_v1"
        private const val PREF_FROZEN_DISPLAY_BACKFILL_DONE =
            "frozen_display_backfill_done"
        private const val CHANCE_BACKFILL_LOOKBACK_DAYS = 30L
    }
}

/** Flattens a Room forecast row into the shared planner's row type. */
internal fun ForecastEntity.toMaintenanceRow() =
    DailyHistoryMaintenance.ForecastHistoryRow(
        dateMs = targetDate,
        source = source,
        locationLat = locationLat,
        locationLon = locationLon,
        highTemp = highTemp,
        lowTemp = lowTemp,
        precipAmountMm = precipAmountMm,
        condition = condition,
        fetchedAt = fetchedAt,
        isClimateNormal = isClimateNormal,
        precipProbability = precipProbability,
        daytimePrecipProbability = daytimePrecipProbability,
        nighttimePrecipProbability = nighttimePrecipProbability,
        batchFetchedAt = batchFetchedAt,
    )
