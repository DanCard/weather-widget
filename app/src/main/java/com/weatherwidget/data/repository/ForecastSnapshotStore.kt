package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.getLatestForecastsInRange
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyForecastWrite
import com.weatherwidget.shared.util.DailyPrecipPeriods
import com.weatherwidget.data.local.hourlySummary
import com.weatherwidget.data.local.withHourlySummary
import com.weatherwidget.shared.util.PredictionDate
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate

/**
 * Owns daily forecast mapping, snapshot history, and coherent current-batch reads.
 *
 * All daily write invariants live here: quantized coordinates, site-exact comparisons,
 * meaningful-change detection, cadence buckets, and complete batch markers.
 */
internal class ForecastSnapshotStore(
    val forecastDao: ForecastDao,
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
     * One fetch's daily rows for [sourceId] at this site: the `:shared` [DailyForecastWrite] rule
     * (same as desktop) decides which rows are written and which existing ones only take this fetch's
     * [batchFetchedAt]; this reads the site's rows and writes the plan.
     */
    suspend fun saveForecastSnapshot(
        weatherForecasts: List<ForecastEntity>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        batchFetchedAt: Long? = null,
        nowMs: Long = clock(),
        userRefresh: Boolean = false,
    ) {
        val effectiveBatchFetchedAt = batchFetchedAt ?: nowMs
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val incoming = weatherForecasts.map { it.toIncoming() }
        val stored = DailyForecastWrite.readRange(incoming, nowMs)
            ?.let { storedForecastsAtSite(forecastDao, keyLat, keyLon, sourceId, it.start, it.endInclusive) }
            .orEmpty()
        val plan = DailyForecastWrite.plan(incoming, stored, sourceId, nowMs, effectiveBatchFetchedAt, userRefresh)
        plan.lines.forEach { appLogDao.log(it.tag, it.message, it.level) }
        writeForecastPlan(forecastDao, keyLat, keyLon, sourceId, plan.write, plan.restamp, effectiveBatchFetchedAt, nowMs)
    }

    private fun ForecastEntity.toIncoming() = DailyForecastWrite.Incoming(
        date = LocalDate.ofEpochDay(targetDate / WidgetConstants.MS_IN_A_DAY),
        highTemp = highTemp,
        lowTemp = lowTemp,
        condition = condition,
        iconToken = nativeDailyIconToken,
        precipProbability = precipProbability,
        precipAmountMm = precipAmountMm,
        daytimePrecipProbability = daytimePrecipProbability,
        nighttimePrecipProbability = nighttimePrecipProbability,
        summary = hourlySummary,
        isClimateNormal = isClimateNormal,
        periodEndMs = periodEndTime,
    )

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

    /**
     * The rows the daily view shows: at the newest fetch's site, from its first day on, each day's
     * newest batch. A SHORT fetch rewrites only the next 3 days; days past them keep the last LONG
     * fetch's rows (performance/261016-unified-long-short-forecast-horizons.md) — reading only the
     * newest batch filled them with climate normals. A day whose newest row is more than
     * [ForecastHorizonCadence.MAX_CARRIED_ROW_AGE_HOURS] older than the newest fetch is left to the
     * climate normal, as when a newer fetch covers fewer days. [rows] are ordered newest first per day.
     */
    internal fun shownRows(rows: List<ForecastEntity>): List<ForecastEntity> {
        val newest = rows.maxByOrNull { it.batchFetchedAt } ?: return emptyList()
        val atSite = rows.filter { it.locationLat == newest.locationLat && it.locationLon == newest.locationLon }
        val firstDay = atSite.filter { it.batchFetchedAt == newest.batchFetchedAt }.minOf { it.targetDate }
        val shownBatch = atSite.filter { it.targetDate >= firstDay }
            .groupBy { it.targetDate }
            .mapValues { (_, day) -> day.maxOf { it.batchFetchedAt } }
            .filterValues { com.weatherwidget.data.remote.ForecastHorizonCadence.showsCarriedRow(it, newest.batchFetchedAt) }
        return atSite.filter { shownBatch[it.targetDate] == it.batchFetchedAt }
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
        val liveSourceData = shownRows(sourceData)

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

    private fun formatEpochDate(epochMs: Long?): String =
        epochMs?.let {
            LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY).toString()
        } ?: "null"

    companion object {
        private const val CACHE_LOOKBACK_DAYS = 7L
        private const val CACHE_FORECAST_DAYS = 30L
    }
}
