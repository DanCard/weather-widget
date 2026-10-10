package com.weatherwidget.data.repository

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.getLatestForecastsInRange
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyHourlySummaries
import com.weatherwidget.shared.util.DailyPrecipPeriods
import com.weatherwidget.shared.util.ForecastTempRounding
import com.weatherwidget.data.local.hourlySummary
import com.weatherwidget.data.local.withHourlySummary
import com.weatherwidget.shared.util.PredictionDate
import com.weatherwidget.shared.util.SameDayExtremeCutoff
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Owns daily forecast mapping, snapshot history, and coherent current-batch reads.
 *
 * All daily write invariants live here: quantized coordinates, site-exact comparisons,
 * meaningful-change detection, cadence buckets, and complete batch markers.
 */
internal class ForecastSnapshotStore(
    private val forecastDao: ForecastDao,
    private val appLogDao: AppLogDao,
    private val widgetStateManager: WidgetStateManager,
    private val gapFiller: ClimateGapFiller,
    /**
     * Wall clock for [saveForecastSnapshot]'s defaults. The same-day cutoffs make what a write
     * stores depend on the hour, so tests pin it rather than fail every evening.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Maps a provider's daily row. Day/night precip are the provider's own values here; the stored
     * values are resolved at save time ([saveForecastSnapshot]) from the provider's values and the
     * row's hourly maxima ([DailyPrecipPeriods], shared with desktop).
     */
    fun mapDailyForecast(
        day: DailyForecast,
        latitude: Double,
        longitude: Double,
        sourceId: String,
    ): ForecastEntity {
        val targetDate = LocalDate.parse(day.date)

        return ForecastEntity(
            targetDate = targetDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            dateOfPrediction = LocalDate.now().toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            locationLat = latitude,
            locationLon = longitude,
            highTemp = day.highTemp,
            lowTemp = day.lowTemp,
            condition = day.condition,
            nativeDailyIconToken = day.iconToken,
            isClimateNormal = false,
            source = sourceId,
            precipProbability = day.precipProbability,
            daytimePrecipProbability = day.daytimePrecipProbability,
            nighttimePrecipProbability = day.nighttimePrecipProbability,
            precipAmountMm = day.precipAmountMm,
        )
    }

    suspend fun saveForecastSnapshot(
        weatherForecasts: List<ForecastEntity>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        batchFetchedAt: Long = clock(),
        nowMs: Long = clock(),
    ) {
        val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
        val todayDate = PredictionDate.of(nowMs)
        val todayEpoch = PredictionDate.epochMs(nowMs)
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val candidates = weatherForecasts.filter { forecast ->
            val date = LocalDate.ofEpochDay(forecast.targetDate / WidgetConstants.MS_IN_A_DAY)
            if (date.isBefore(todayDate) || forecast.isClimateNormal) return@filter false
            val periodEnd = forecast.periodEndTime?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
            }
            if (periodEnd != null && periodEnd.isBefore(now)) {
                appLogDao.log(
                    "SNAPSHOT_SKIP_ELAPSED",
                    "date=$date source=${forecast.source} periodEnd=$periodEnd",
                )
                return@filter false
            }
            true
        }
        if (candidates.isEmpty()) return

        val existingForecasts = forecastDao.getForecastsInRangeBySource(
            startDate = candidates.minOf { it.targetDate },
            endDate = candidates.maxOf { it.targetDate },
            lat = latitude,
            lon = longitude,
            source = sourceId,
        )
        val latestByDate = siteExactLatestForecastByDate(existingForecasts, keyLat, keyLon)
        // Newest stored row per day that has a low — the latest row may have none (a source that no
        // longer reports the night ending this morning), so it cannot be the one to keep from.
        val latestLowByDate = existingForecasts
            .filter { it.locationLat == keyLat && it.locationLon == keyLon && it.lowTemp != null }
            .groupBy { it.targetDate }
            .mapValues { (_, rows) -> rows.maxBy { it.fetchedAt } }
        val nowMs = now.toInstant().toEpochMilli()
        val zone = now.zone

        val forecastsToSave = candidates.mapNotNull { forecast ->
            val date = LocalDate.ofEpochDay(forecast.targetDate / WidgetConstants.MS_IN_A_DAY)
            val high = forecast.highTemp?.takeIf { it.isFinite() }
            val low = forecast.lowTemp?.takeIf { it.isFinite() }
            if (high == null && low == null) return@mapNotNull null
            val isToday = forecast.targetDate == todayEpoch

            val filtered = SameDayExtremeCutoff.filter(
                targetDate = date,
                highTemp = ForecastTempRounding.forStorage(high, isToday),
                lowTemp = ForecastTempRounding.forStorage(low, isToday),
                nowMs = nowMs,
                zone = zone,
            )
            // Keep the last real pre-cutoff prediction rather than nulling the field: a post-cutoff
            // batch must not become the latest row with a hole where the forecast used to be.
            val prior = latestByDate[forecast.targetDate]
            val highToStore = if (filtered.frozeHigh) prior?.highTemp else filtered.highTemp
            val lowToStore = if (filtered.frozeLow) {
                prior?.lowTemp
            } else {
                filtered.lowTemp ?: latestLowByDate[forecast.targetDate].let {
                    SameDayExtremeCutoff.keptTodayLow(isToday, it?.lowTemp, it?.fetchedAt, nowMs)
                }
            }
            // The frozen side's raw value is kept, not dropped: readers ignore hindcast*, and a past
            // day with no pre-cutoff forecast draws it as a dashed fallback (PastDayForecastOverlay).
            val hindcastHigh = if (filtered.frozeHigh) ForecastTempRounding.forStorage(high, isToday) else null
            val hindcastLow = if (filtered.frozeLow) ForecastTempRounding.forStorage(low, isToday) else null
            if (filtered.frozeAny) {
                appLogDao.log(
                    "SNAPSHOT_SKIP_HINDCAST",
                    "date=$date source=$sourceId " +
                        "froze=${listOfNotNull("high".takeIf { filtered.frozeHigh }, "low".takeIf { filtered.frozeLow }).joinToString("+")} " +
                        "prior_high=${prior?.highTemp} prior_low=${prior?.lowTemp}",
                )
            }
            if (highToStore == null && lowToStore == null && hindcastHigh == null && hindcastLow == null) {
                return@mapNotNull null
            }

            // A window this fetch's hourly did not cover keeps the previous row's value
            // (DailyHourlySummaries); never blanked by a fetch that does not reach the day.
            val summary = DailyHourlySummaries.carryForward(forecast.hourlySummary, prior?.hourlySummary)
            val periods = DailyPrecipPeriods.resolve(
                providerDay = forecast.daytimePrecipProbability,
                providerNight = forecast.nighttimePrecipProbability,
                hourly = summary,
            )

            ForecastEntity(
                targetDate = forecast.targetDate,
                dateOfPrediction = todayEpoch,
                locationLat = keyLat,
                locationLon = keyLon,
                highTemp = highToStore,
                lowTemp = lowToStore,
                condition = forecast.condition,
                nativeDailyIconToken = forecast.nativeDailyIconToken,
                isClimateNormal = forecast.isClimateNormal,
                source = sourceId,
                precipProbability = forecast.precipProbability,
                daytimePrecipProbability = periods.day,
                nighttimePrecipProbability = periods.night,
                precipAmountMm = forecast.precipAmountMm,
                batchFetchedAt = batchFetchedAt,
                fetchedAt = System.currentTimeMillis(),
                hindcastHighTemp = hindcastHigh,
                hindcastLowTemp = hindcastLow,
            ).withHourlySummary(summary)
        }

        if (forecastsToSave.isEmpty()) return
        val unchangedBatchRows = mutableListOf<ForecastEntity>()
        val changedForecasts = forecastsToSave.filter { newlyFetched ->
            val existing = latestByDate[newlyFetched.targetDate]
            val fieldsMatch = existing != null &&
                existing.highTemp == newlyFetched.highTemp &&
                existing.lowTemp == newlyFetched.lowTemp &&
                existing.condition == newlyFetched.condition &&
                existing.nativeDailyIconToken == newlyFetched.nativeDailyIconToken &&
                existing.precipProbability == newlyFetched.precipProbability &&
                existing.daytimePrecipProbability == newlyFetched.daytimePrecipProbability &&
                existing.nighttimePrecipProbability == newlyFetched.nighttimePrecipProbability &&
                existing.precipAmountMm == newlyFetched.precipAmountMm &&
                existing.hindcastHighTemp == newlyFetched.hindcastHighTemp &&
                existing.hindcastLowTemp == newlyFetched.hindcastLowTemp &&
                existing.hourlySummary == newlyFetched.hourlySummary
            val newDataIsStrictlyBetter = existing != null &&
                (
                    (existing.highTemp == null && newlyFetched.highTemp != null) ||
                        (existing.lowTemp == null && newlyFetched.lowTemp != null)
                )
            if (fieldsMatch && !newDataIsStrictlyBetter) {
                appLogDao.log(
                    "SNAPSHOT_SKIP",
                    "date=${newlyFetched.targetDate} source=$sourceId " +
                        "existing_high=${existing.highTemp} new_high=${newlyFetched.highTemp} " +
                        "existing_low=${existing.lowTemp} new_low=${newlyFetched.lowTemp} " +
                        "existing_cond=${existing.condition} new_cond=${newlyFetched.condition} " +
                        "existing_precip=${existing.precipProbability} " +
                        "new_precip=${newlyFetched.precipProbability}",
                )
                unchangedBatchRows += existing.copy(batchFetchedAt = batchFetchedAt)
                false
            } else {
                if (existing != null && newDataIsStrictlyBetter) {
                    appLogDao.log(
                        "SNAPSHOT_UPGRADE",
                        "date=${newlyFetched.targetDate} source=$sourceId " +
                            "existing_high=${existing.highTemp} new_high=${newlyFetched.highTemp} " +
                            "existing_low=${existing.lowTemp} new_low=${newlyFetched.lowTemp}",
                    )
                }
                appLogDao.log(
                    "SNAPSHOT_SAVE",
                    "date=${newlyFetched.targetDate} source=$sourceId",
                )
                true
            }
        }

        if (sourceId == WeatherSource.NWS.id) {
            appLogDao.log(
                "NWS_BATCH_SAVE_SUMMARY",
                buildNwsBatchSaveSummary(
                    batchFetchedAt = batchFetchedAt,
                    rawForecasts = weatherForecasts,
                    forecastsToSave = forecastsToSave,
                    changedForecasts = changedForecasts,
                ),
            )
        }

        if (changedForecasts.isNotEmpty()) {
            val prioritySourceIds = widgetStateManager.getActiveDisplaySourceIds()
            val bucketStart = ForecastHistoryPolicy.timestampToGroupPredictions(
                System.currentTimeMillis(),
                sourceId,
                prioritySourceIds,
            )
            val bucketEnd = bucketStart + ForecastHistoryPolicy.bucketMs(
                sourceId,
                prioritySourceIds,
            )
            forecastDao.deleteForecastsInBucket(
                source = sourceId,
                lat = keyLat,
                lon = keyLon,
                targetDates = changedForecasts.map { it.targetDate },
                bucketStart = bucketStart,
                bucketEnd = bucketEnd,
            )
        }

        val currentBatchRows = unchangedBatchRows + changedForecasts
        if (currentBatchRows.isNotEmpty()) {
            forecastDao.insertAll(currentBatchRows)
        }
    }

    suspend fun getCachedData(
        latitude: Double,
        longitude: Double,
    ): List<ForecastEntity> {
        val today = LocalDate.now()
        val rows = forecastDao.getLatestForecastsInRange(
            today.minusDays(CACHE_LOOKBACK_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            today.plusDays(CACHE_FORECAST_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            latitude,
            longitude,
        )
        return gapFiller.appendGaps(rows, latitude, longitude, today, CACHE_FORECAST_DAYS)
    }

    suspend fun getCachedDataBySource(
        latitude: Double,
        longitude: Double,
        source: WeatherSource,
    ): List<ForecastEntity> {
        val today = LocalDate.now()
        val startDate = today.minusDays(CACHE_LOOKBACK_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endDate = today.plusDays(CACHE_FORECAST_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val sourceData = forecastDao.getForecastsInRangeBySource(
            startDate,
            endDate,
            latitude,
            longitude,
            source.id,
        )
        val latestBatchFetchedAt = sourceData.maxOfOrNull { it.batchFetchedAt }
        val liveSourceData = latestBatchFetchedAt?.let { batch ->
            sourceData.filter { it.batchFetchedAt == batch }
        }.orEmpty()

        if (source == WeatherSource.NWS) {
            appLogDao.log(
                "NWS_BATCH_RENDER_SUMMARY",
                "batch=$latestBatchFetchedAt liveCount=${liveSourceData.size} " +
                    "liveMinDate=${formatEpochDate(liveSourceData.minOfOrNull { it.targetDate })} " +
                    "liveMaxDate=${formatEpochDate(liveSourceData.maxOfOrNull { it.targetDate })}",
            )
        }

        val coveredDates = liveSourceData.map {
            LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY)
        }.toSet()
        val gapData = gapFiller.gapRows(
            latitude,
            longitude,
            coveredDates,
            today,
            CACHE_FORECAST_DAYS,
        )
        val latestSourceByDate = liveSourceData.groupBy { it.targetDate }
            .mapValues { (_, rows) -> rows.first() }
        val latestGapByDate = gapData.groupBy { it.targetDate }
            .mapValues { (_, rows) -> rows.first() }

        return (latestGapByDate.keys + latestSourceByDate.keys)
            .sorted()
            .mapNotNull { date -> latestSourceByDate[date] ?: latestGapByDate[date] }
    }

    private fun buildNwsBatchSaveSummary(
        batchFetchedAt: Long,
        rawForecasts: List<ForecastEntity>,
        forecastsToSave: List<ForecastEntity>,
        changedForecasts: List<ForecastEntity>,
    ): String {
        val rawMaxDate = rawForecasts.maxOfOrNull { it.targetDate }
        val filteredMaxDate = forecastsToSave.maxOfOrNull { it.targetDate }
        val savedMaxDate = changedForecasts.maxOfOrNull { it.targetDate }
        val terminalRow = forecastsToSave.maxByOrNull { it.targetDate }
        return "batch=$batchFetchedAt " +
            "rawCount=${rawForecasts.size} rawMaxDate=${formatEpochDate(rawMaxDate)} " +
            "filteredCount=${forecastsToSave.size} filteredMaxDate=${formatEpochDate(filteredMaxDate)} " +
            "savedCount=${changedForecasts.size} savedMaxDate=${formatEpochDate(savedMaxDate)} " +
            "terminal=${formatTerminalRow(terminalRow)}"
    }

    private fun formatEpochDate(epochMs: Long?): String =
        epochMs?.let {
            LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY).toString()
        } ?: "null"

    private fun formatTerminalRow(row: ForecastEntity?): String {
        if (row == null) return "null"
        return "${formatEpochDate(row.targetDate)} high=${row.highTemp} low=${row.lowTemp}"
    }

    companion object {
        private const val CACHE_LOOKBACK_DAYS = 7L
        private const val CACHE_FORECAST_DAYS = 30L

        @VisibleForTesting
        internal fun siteExactLatestForecastByDate(
            boxRows: List<ForecastEntity>,
            lat: Double,
            lon: Double,
        ): Map<Long, ForecastEntity> =
            boxRows.asSequence()
                .filter { it.locationLat == lat && it.locationLon == lon }
                .distinctBy { it.targetDate }
                .associateBy { it.targetDate }
    }
}
