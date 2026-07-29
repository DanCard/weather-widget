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
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyHistoryFreeze
import com.weatherwidget.shared.util.DailyNoonCloudCover
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.shared.util.FrozenRainChanceRepair
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WidgetConstants
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "DailyHistorySnapshotter"

/**
 * Owns daily-history freeze windows and one-time repair/backfill policies.
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
        val existingByDateSource = dailyHistoryDao.getExtremesInRange(
            startMs,
            endMs,
            latitude,
            longitude,
        ).groupBy { it.date to it.source }

        val toInsert = mutableListOf<DailyHistoryEntity>()
        listOf(yesterday, today).forEach { date ->
            val dayWindowOpen = nowMs <
                date.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli()
            val nightWindowOpen = nowMs <
                date.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
            if (!dayWindowOpen && !nightWindowOpen) return@forEach
            val overlayOpen = DailyHistoryFreeze.overlayWindowOpen(nowMs, date, zoneId)
            val noonCloudOpen = DailyHistoryFreeze.noonCloudWindowOpen(nowMs, date, zoneId)
            val dateMs = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY

            dailyRows.filter { it.targetDate == dateMs }.forEach { row ->
                val fragments = existingByDateSource[dateMs to row.source].orEmpty()
                if (fragments.isEmpty()) return@forEach
                val resolved = DailyRainLabels.resolveLiveDayNightChanceAtSite(
                    displaySourceId = row.source,
                    daytimePrecipProbability = row.daytimePrecipProbability,
                    nighttimePrecipProbability = row.nighttimePrecipProbability,
                    precipProbability = row.precipProbability,
                    hourly = hourlyRows,
                    centerLat = latitude,
                    centerLon = longitude,
                    targetDate = date,
                    zoneId = zoneId,
                )
                val overlayRow = row.takeIf {
                    !it.isClimateNormal &&
                        it.source != WeatherSource.GENERIC_GAP.id &&
                        it.highTemp != null &&
                        it.lowTemp != null
                }
                val resolvedNoonCloud =
                    DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercentAtSite(
                        hourly = hourlyRows,
                        date = date,
                        displaySourceId = row.source,
                        centerLat = latitude,
                        centerLon = longitude,
                    )
                fragments.forEach { existing ->
                    val updated = freezeDailyHistoryFragment(
                        existing = existing,
                        row = row,
                        date = date,
                        dayWindowOpen = dayWindowOpen,
                        nightWindowOpen = nightWindowOpen,
                        overlayOpen = overlayOpen,
                        noonCloudOpen = noonCloudOpen,
                        resolved = resolved,
                        resolvedNoonCloud = resolvedNoonCloud,
                        overlayRow = overlayRow,
                    )
                    if (updated != existing) toInsert.add(updated)
                }
            }
        }
        if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
    }

    private suspend fun freezeDailyHistoryFragment(
        existing: DailyHistoryEntity,
        row: ForecastEntity,
        date: LocalDate,
        dayWindowOpen: Boolean,
        nightWindowOpen: Boolean,
        overlayOpen: Boolean,
        noonCloudOpen: Boolean,
        resolved: DailyRainLabels.ResolvedDailyPrecip,
        resolvedNoonCloud: Int?,
        overlayRow: ForecastEntity?,
    ): DailyHistoryEntity {
        val newDay = if (dayWindowOpen) {
            resolved.dayPrecip
        } else {
            existing.forecastDayPrecipChance
        }
        val newNight = if (nightWindowOpen) {
            resolved.nightPrecip
        } else {
            existing.forecastNightPrecipChance
        }
        val frozen = DailyHistoryFreeze.merge(
            overlayOpen = overlayOpen,
            noonCloudOpen = noonCloudOpen,
            resolvedHigh = overlayRow?.highTemp,
            resolvedLow = overlayRow?.lowTemp,
            resolvedPrecipAmountMm = overlayRow?.precipAmountMm,
            resolvedNoonCloudPercent = resolvedNoonCloud,
            existing = DailyHistoryFreeze.FrozenDisplay(
                forecastHighTemp = existing.forecastHighTemp,
                forecastLowTemp = existing.forecastLowTemp,
                forecastPrecipAmountMm = existing.forecastPrecipAmountMm,
                noonCloudPercent = existing.noonCloudPercent,
            ),
        )
        val updated = existing.copy(
            forecastDayPrecipChance = newDay,
            forecastNightPrecipChance = newNight,
            forecastHighTemp = frozen.forecastHighTemp,
            forecastLowTemp = frozen.forecastLowTemp,
            forecastPrecipAmountMm = frozen.forecastPrecipAmountMm,
            noonCloudPercent = frozen.noonCloudPercent,
        )
        Log.v(
            TAG,
            "freezeDisplay: date=$date src=${row.source} overlayOpen=$overlayOpen " +
                "noonCloudOpen=$noonCloudOpen dayWin=$dayWindowOpen " +
                "nightWin=$nightWindowOpen " +
                "dayChance=${existing.forecastDayPrecipChance}->$newDay" +
                "(resolved=${resolved.dayPrecip}) " +
                "nightChance=${existing.forecastNightPrecipChance}->$newNight" +
                "(resolved=${resolved.nightPrecip}) " +
                "high=${existing.forecastHighTemp}->${updated.forecastHighTemp} " +
                "low=${existing.forecastLowTemp}->${updated.forecastLowTemp} " +
                "amount=${existing.forecastPrecipAmountMm}->" +
                "${updated.forecastPrecipAmountMm} " +
                "noonCloud=${existing.noonCloudPercent}->${updated.noonCloudPercent}",
        )
        if (
            newDay != existing.forecastDayPrecipChance ||
            newNight != existing.forecastNightPrecipChance
        ) {
            appLogDao.log(
                "FREEZE_RAIN_CHANCE",
                "date=$date src=${row.source} dayWin=$dayWindowOpen " +
                    "nightWin=$nightWindowOpen resolvedDay=${resolved.dayPrecip} " +
                    "resolvedNight=${resolved.nightPrecip} " +
                    "day=${existing.forecastDayPrecipChance}->$newDay " +
                    "night=${existing.forecastNightPrecipChance}->$newNight",
            )
        }
        return updated
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
        }

        val toInsert = mutableListOf<DailyHistoryEntity>()
        for (row in rowsNeedingBackfill) {
            val date = LocalDate.ofEpochDay(row.date / WidgetConstants.MS_IN_A_DAY)
            val historyRows = historyRowsForDate(
                date = date,
                endHourNextDay = 8,
                latitude = latitude,
                longitude = longitude,
                source = row.source,
                zoneId = zoneId,
            )
            if (historyRows.isEmpty()) continue
            val stitched = HourlyForecastStitcher.stitch(
                current = emptyList(),
                history = historyRows,
                nowMs = System.currentTimeMillis(),
                centerLat = latitude,
                centerLon = longitude,
            )
            val dayNight = DailyRainLabels.calculateDayNightPrecipProbabilities(
                hourly = stitched,
                targetDate = date,
                displaySourceId = row.source,
                zoneId = zoneId,
            )
            if (dayNight.dayMax == null && dayNight.nightMax == null) continue
            toInsert.add(
                row.copy(
                    forecastDayPrecipChance = dayNight.dayMax,
                    forecastNightPrecipChance = dayNight.nightMax,
                ),
            )
        }
        if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
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
        }
        if (rowsNeedingBackfill.isEmpty()) {
            prefs.edit().putBoolean(PREF_FROZEN_DISPLAY_BACKFILL_DONE, true).apply()
            return
        }

        val snapshotsByDateSource = forecastDao.getAllForecastsInRange(
            startMs,
            endMs,
            latitude,
            longitude,
        ).groupBy { it.targetDate to it.source }
        val toInsert = mutableListOf<DailyHistoryEntity>()
        for (row in rowsNeedingBackfill) {
            val date = LocalDate.ofEpochDay(row.date / WidgetConstants.MS_IN_A_DAY)
            val overlay = snapshotsByDateSource[row.date to row.source].orEmpty()
                .filter {
                    !it.isClimateNormal &&
                        it.highTemp != null &&
                        it.lowTemp != null
                }
                .maxByOrNull { it.fetchedAt }
            val historyRows = historyRowsForDate(
                date = date,
                endHourNextDay = 0,
                latitude = latitude,
                longitude = longitude,
                source = row.source,
                zoneId = zoneId,
            )
            val noonCloud = if (historyRows.isEmpty()) {
                null
            } else {
                DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(
                    hourly = HourlyForecastStitcher.stitch(
                        current = emptyList(),
                        history = historyRows,
                        nowMs = System.currentTimeMillis(),
                        centerLat = latitude,
                        centerLon = longitude,
                    ),
                    date = date,
                    displaySourceId = row.source,
                    zone = zoneId,
                )
            }
            val updated = row.copy(
                forecastHighTemp = row.forecastHighTemp ?: overlay?.highTemp,
                forecastLowTemp = row.forecastLowTemp ?: overlay?.lowTemp,
                forecastPrecipAmountMm = row.forecastPrecipAmountMm
                    ?: overlay?.precipAmountMm,
                noonCloudPercent = row.noonCloudPercent ?: noonCloud,
            )
            if (updated != row) toInsert.add(updated)
        }
        if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
        appLogDao.log(
            "FROZEN_DISPLAY_BACKFILL",
            "backfilled=${toInsert.size} scanned=${rowsNeedingBackfill.size}",
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
    ) = hourlyForecastHistoryDao.getHistoryInRangeForBucketWindow(
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
