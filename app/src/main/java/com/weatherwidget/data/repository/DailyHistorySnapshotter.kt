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
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.actuals.DailyHistoryMaintenance
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
 * The planning rules live in [DailyHistoryMaintenance] (`:shared`) so Android and desktop agree
 * exactly; this class is the Android adapter that loads DAO rows, maps them to the shared row
 * types, and writes the planned rows back. [repairFrozenRainChanceIfNeeded] is Android-only (a
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

    /**
     * Creates forecast-only `daily_history` rows (DailyHistoryWriter.FORECAST_ONLY_ROW) for past
     * (date, source) pairs that have a complete forecast batch but no row at all — Open-Meteo
     * (no actuals product), Tomorrow.io before its actuals tracking started, or any source whose
     * actuals write never landed. Without these rows the daily widget/desktop history columns
     * lose their high/low labels and depend on the forecasts table's rolling retention.
     *
     * Idempotent: existing (date, source) rows are skipped, so this runs on every sync like the
     * freeze pass and covers both the one-time backfill and each day rollover.
     */
    suspend fun ensureForecastOnlyHistoryRows(
        latitude: Double,
        longitude: Double,
    ) {
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val startMs = today.minusDays(DailyHistoryMaintenance.FORECAST_ONLY_LOOKBACK_DAYS)
            .toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY

        val forecastRows = forecastDao.getForecastsInRange(
            startMs,
            endMs + WidgetConstants.MS_IN_A_DAY,
            latitude,
            longitude,
        )
        if (forecastRows.isEmpty()) return

        val existing = dailyHistoryDao.getExtremesInRange(
            startMs,
            endMs,
            latitude,
            longitude,
        ).map { it.date to it.source }.toSet()

        val rows = DailyHistoryMaintenance.planForecastOnlyRows(
            forecastRows = forecastRows.map { it.toMaintenanceRow() },
            existingKeys = existing,
            todayMs = endMs,
            nowMs = System.currentTimeMillis(),
        )
        if (rows.isEmpty()) return

        val entities = rows.map { it.toEntity() }
        dailyHistoryDao.insertAll(entities)
        Log.i(TAG, "ensureForecastOnlyHistoryRows: created ${entities.size} rows " +
            "sources=${entities.map { it.source }.distinct()} dates=${entities.minOf { it.date }}..${entities.maxOf { it.date }}")
        appLogDao.log(
            "FORECAST_ONLY_HISTORY",
            "created=${entities.size} sources=${entities.map { it.source }.distinct()} " +
                "first=${entities.minOf { it.date }} last=${entities.maxOf { it.date }}",
        )
    }

    suspend fun snapshotDisplayedRainChance(
        latitude: Double,
        longitude: Double,
    ) {
        val zoneId = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)
        val startMs = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val dailyRows = forecastDao.getForecastsInRange(
            startMs,
            endMs,
            latitude,
            longitude,
        )
        if (dailyRows.isEmpty()) return

        val hourlyRows = hourlyForecastDao.getHourlyForecasts(
            yesterday.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            today.plusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            latitude,
            longitude,
        ).map { it.toHourlyForecast() }
        val existing = dailyHistoryDao.getExtremesInRange(
            startMs,
            endMs,
            latitude,
            longitude,
        ).map { it.toDailyHistory() }

        val plan = DailyHistoryMaintenance.planSnapshotDisplayedRainChance(
            dailyRows = dailyRows.map { it.toMaintenanceRow() },
            hourly = hourlyRows,
            existing = existing,
            centerLat = latitude,
            centerLon = longitude,
            nowMs = nowMs,
            zoneId = zoneId,
        )
        plan.traces.forEach { Log.v(TAG, it) }
        plan.chanceChangeLogs.forEach { appLogDao.log("FREEZE_RAIN_CHANCE", it) }
        if (plan.rows.isNotEmpty()) dailyHistoryDao.insertAll(plan.rows.map { it.toEntity() })
    }

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

    suspend fun backfillForecastChanceSnapshotsIfNeeded(
        latitude: Double,
        longitude: Double,
    ) {
        if (prefs.getBoolean(PREF_CHANCE_BACKFILL_DONE, false)) return
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val startMs = today.minusDays(CHANCE_BACKFILL_LOOKBACK_DAYS)
            .toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val rowsNeedingBackfill = dailyHistoryDao.getExtremesInRange(
            startMs,
            endMs,
            latitude,
            longitude,
        ).filter {
            it.forecastDayPrecipChance == null &&
                it.forecastNightPrecipChance == null
        }.map { it.toDailyHistory() }

        val rows = DailyHistoryMaintenance.planChanceBackfill(
            rowsNeedingBackfill = rowsNeedingBackfill,
            historyFor = { date, source ->
                historyRowsForDate(date, 8, latitude, longitude, source, zoneId)
            },
            centerLat = latitude,
            centerLon = longitude,
            nowMs = System.currentTimeMillis(),
            zoneId = zoneId,
        )
        if (rows.isNotEmpty()) dailyHistoryDao.insertAll(rows.map { it.toEntity() })
        prefs.edit().putBoolean(PREF_CHANCE_BACKFILL_DONE, true).apply()
    }

    suspend fun backfillFrozenDisplayColumnsIfNeeded(
        latitude: Double,
        longitude: Double,
    ) {
        if (prefs.getBoolean(PREF_FROZEN_DISPLAY_BACKFILL_DONE, false)) return
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val startMs = today.minusDays(CHANCE_BACKFILL_LOOKBACK_DAYS)
            .toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val rowsNeedingBackfill = dailyHistoryDao.getExtremesInRange(
            startMs,
            endMs,
            latitude,
            longitude,
        ).filter {
            (it.forecastHighTemp == null && it.forecastLowTemp == null) ||
                it.noonCloudPercent == null
        }.map { it.toDailyHistory() }
        if (rowsNeedingBackfill.isEmpty()) {
            prefs.edit().putBoolean(PREF_FROZEN_DISPLAY_BACKFILL_DONE, true).apply()
            return
        }

        val snapshots = forecastDao.getAllForecastsInRange(
            startMs,
            endMs,
            latitude,
            longitude,
        ).map { it.toMaintenanceRow() }

        val rows = DailyHistoryMaintenance.planFrozenDisplayBackfill(
            rowsNeedingBackfill = rowsNeedingBackfill,
            snapshots = snapshots,
            historyFor = { date, source ->
                historyRowsForDate(date, 0, latitude, longitude, source, zoneId)
            },
            centerLat = latitude,
            centerLon = longitude,
            nowMs = System.currentTimeMillis(),
            zoneId = zoneId,
        )
        if (rows.isNotEmpty()) dailyHistoryDao.insertAll(rows.map { it.toEntity() })
        appLogDao.log(
            "FROZEN_DISPLAY_BACKFILL",
            "backfilled=${rows.size} scanned=${rowsNeedingBackfill.size}",
        )
        prefs.edit().putBoolean(PREF_FROZEN_DISPLAY_BACKFILL_DONE, true).apply()
    }

    private suspend fun historyRowsForDate(
        date: LocalDate,
        endHourNextDay: Int,
        latitude: Double,
        longitude: Double,
        source: String,
        zoneId: ZoneId,
    ): List<HourlyForecast> =
        hourlyForecastHistoryDao.getHistoryInRangeForBucketWindow(
            startDateTime = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endDateTime = date.plusDays(1).atTime(endHourNextDay, 0).atZone(zoneId)
                .toInstant().toEpochMilli(),
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

    companion object {
        private const val PREF_CHANCE_BACKFILL_DONE = "rain_chance_backfill_done"
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
    )
