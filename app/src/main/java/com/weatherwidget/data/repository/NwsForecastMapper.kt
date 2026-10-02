package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsDailyMapper
import com.weatherwidget.data.remote.NwsForecastFetch
import com.weatherwidget.widget.WidgetConstants
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NwsForecastMapper @Inject constructor(
    private val nwsApi: NwsApi,
    private val appLogDao: AppLogDao,
) {
    private val TAG = "NwsForecastMapper"
    private val NWS_PERIOD_SUMMARY_COUNT = 8

    data class NwsBatchSummary(
        val periodCount: Int,
        val lastPeriodName: String?,
        val lastPeriodStart: String?,
        val lastPeriodEnd: String?,
        val lastPeriodTemp: Int?,
        val lastPeriodIsDaytime: Boolean?,
        val mappedCount: Int,
        val mappedMaxTargetDate: String?,
        val terminalLowOnlyPreserved: Boolean,
        val preservedDate: String?,
        val preservedLowTemp: Float?,
    )

    /**
     * @param elapsedHourly the issuance's already-elapsed hours from the raw grid, as the shared
     *   model — history-only material for `HourlyForecastStore.backfillElapsedHistory`, never
     *   written to the live table. Empty when the grid leg failed.
     */
    data class NwsFetchResult(
        val forecasts: List<ForecastEntity>,
        val hourly: List<HourlyForecastEntity>,
        val elapsedHourly: List<HourlyForecast> = emptyList(),
    )

    suspend fun fetchFromNws(
        latitude: Double,
        longitude: Double,
    ): NwsFetchResult = coroutineScope {
        val grid = nwsApi.getGridPoint(latitude, longitude)
        val bundle = NwsForecastFetch.fetch(nwsApi, grid)
        bundle.gridpointsFailure?.let { e ->
            Log.w(TAG, "getGridpointsBundle failed: ${e.message}")
            appLogDao.log(
                "NWS_GRIDPOINTS_FAIL",
                "exception=${e::class.simpleName} message=${e.message} " +
                    "grid=${grid.gridId}/${grid.gridX},${grid.gridY}",
            )
        }
        val forecastPeriods = bundle.forecastPeriods
        val hourlyPeriods = bundle.hourlyPeriods
        val gridpoints = bundle.gridpoints
        val skyCoverMap = gridpoints.skyCoverByHour
        val gridQpfIntervals = gridpoints.qpfIntervals
        val gridDailyTemps = gridpoints.dailyTemperatures

        // If skyCover is empty after a successful fetch, the API returned a structurally
        // valid response missing the skyCover field — distinct from the failure path above.
        // Log enough sibling-field counts to tell the two cases apart post-hoc.
        if (skyCoverMap.isEmpty()) {
            appLogDao.log(
                "NWS_SKYCOVER_EMPTY",
                "grid=${grid.gridId}/${grid.gridX},${grid.gridY} " +
                    "qpf=${gridQpfIntervals.size} " +
                    "maxDays=${gridDailyTemps.maxByDate.size} " +
                    "minDays=${gridDailyTemps.minByDate.size}",
            )
        }

        // Gridpoint maxTemperature/minTemperature are NOT observations — they are the raw NDFD
        // *forecast* grid, the same values mergeGridpointTemperatures uses for future days. Filing
        // the leftover past-date windows as "API actuals" made every past day's actual equal that
        // day's forecast. See plans/260808-nws-actuals-forecast-contamination.md. NWS actuals now
        // come from station observations via StationDailyExtremes.

        persistNwsPeriodSummary(grid.forecastUrl, forecastPeriods)

        val todayDate = LocalDate.now()
        val todayDateString = todayDate.toString()

        // The pipeline is shared with desktop (NwsDailyMapper.assemble); this only logs what it did.
        val assembly = NwsDailyMapper.assemble(forecastPeriods, gridDailyTemps, hourlyPeriods, todayDate)
        val acc = assembly.acc

        if (assembly.gridMergedDates.isNotEmpty()) {
            val detail = assembly.gridMergedDates.sorted().joinToString(",") { d ->
                val (h, l) = acc.temperatureMap[d] ?: (null to null)
                "$d:h=${h}/l=${l}"
            }
            appLogDao.log("NWS_GRID_TEMP_PRIMARY", "dates=${assembly.gridMergedDates.size} $detail")
        }

        logTodayDiagnostics(
            todayDateString, assembly.todayForecastPeriods, acc
        )

        if (assembly.divergedTemps.isNotEmpty()) {
            appLogDao.log(
                "NWS_TEMP_DIVERGED",
                "count=${assembly.divergedTemps.size} tolerance=${NwsDailyMapper.HOURLY_DIVERGENCE_TOLERANCE_F}" +
                    " ${assembly.divergedTemps.joinToString("; ") { it.describe() }}",
                "WARN",
            )
        }

        val rejectedTemps = assembly.rejectedTemps
        if (rejectedTemps.isNotEmpty()) {
            appLogDao.log(
                "NWS_TEMP_REJECTED",
                "count=${rejectedTemps.size} ${rejectedTemps.joinToString("; ") { it.describe() }}",
                "WARN",
            )
            appLogDao.log(
                "NWS_TEMP_REJECTED",
                if (assembly.repairs.isEmpty()) {
                    "no hourly repair available for ${rejectedTemps.size} rejected value(s)"
                } else {
                    "repaired=${assembly.repairs.size} ${assembly.repairs.joinToString("; ")}"
                },
                "WARN",
            )
        }

        val preservedTerminalLowOnlyDay = assembly.preservedTerminalLowOnlyDay
        preservedTerminalLowOnlyDay?.let { (date, lowTemp) ->
            val source = acc.lowTempSourceMap[date]
            Log.i(
                TAG,
                "Preserving terminal low-only NWS day: date=$date low=$lowTemp lowSource=$source",
            )
            appLogDao.log(
                "NWS_PARTIAL_DAY_KEEP",
                "date=$date low=$lowTemp lowSource=$source",
            )
        }

        val forecastEntities = acc.temperatureMap.map { (dateString, temperatures) ->
            val (pStart, pEnd) = acc.periodTimeMap[dateString] ?: (null to null)
            ForecastEntity(
                targetDate = LocalDate.parse(dateString).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                dateOfPrediction = todayDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                locationLat = latitude,
                locationLon = longitude,
                highTemp = temperatures.first,
                lowTemp = temperatures.second,
                condition = acc.conditionMap[dateString] ?: "Unknown",
                nativeDailyIconToken = acc.conditionMap[dateString],
                isClimateNormal = false,
                source = WeatherSource.NWS.id,
                precipProbability = acc.precipProbabilityMap[dateString],
                daytimePrecipProbability = acc.daytimePrecipProbabilityMap[dateString],
                nighttimePrecipProbability = acc.nighttimePrecipProbabilityMap[dateString],
                precipAmountMm = acc.precipAmountMap[dateString],
                periodStartTime = pStart?.let { runCatching { ZonedDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
                periodEndTime = pEnd?.let { runCatching { ZonedDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
            )
        }
        val batchSummary = buildBatchSummary(forecastPeriods, forecastEntities, preservedTerminalLowOnlyDay)
        persistNwsBatchSummary(grid.forecastUrl, batchSummary)

        val nowMs = System.currentTimeMillis()
        val hourlyEntities = hourlyPeriods.map { period ->
            val periodAgeMs = nowMs - period.startTime
            if (periodAgeMs > 0 && period.startTime % (3600 * 1000L) == 0L) {
                appLogDao.log("NWS_HOURLY_STALE", "time=${Instant.ofEpochMilli(period.startTime).atZone(ZoneId.systemDefault()).toLocalDateTime()} temp=${period.temperature} ageMin=${periodAgeMs / 60000}")
            }
            HourlyForecastEntity(
                dateTime = period.startTime,
                locationLat = latitude,
                locationLon = longitude,
                temperature = period.temperature,
                condition = period.shortForecast,
                source = WeatherSource.NWS.id,
                precipProbability = period.precipProbability,
                cloudCover = period.cloudCover,
                // NWS reports a single sky-cover value, not a layer breakdown; the graph's fallback
                // draws the total for this source.
                precipAmountMm = period.precipAmountMm,
                fetchedAt = nowMs,
            )
        }

        val elapsedHourly = bundle.elapsedHourlyPeriods.map { period ->
            HourlyForecast(
                dateTime = period.startTime,
                temperature = period.temperature,
                condition = period.shortForecast,
                precipProbability = period.precipProbability,
                precipAmountMm = period.precipAmountMm,
                cloudCover = period.cloudCover,
                source = WeatherSource.NWS.id,
            )
        }

        NwsFetchResult(forecastEntities, hourlyEntities, elapsedHourly)
    }

    suspend fun logTodayDiagnostics(
        todayDateString: String,
        todayPeriods: List<NwsApi.ForecastPeriod>,
        acc: NwsDailyMapper.NwsDayAccumulator,
    ) {
        // Today's condition from its daytime period is set in NwsDailyMapper.assemble (it used to be
        // set here, so desktop never got it).
        val todayTemps = acc.temperatureMap[todayDateString] ?: return
        appLogDao.log(
            "NWS_TODAY_SOURCE",
            "high=${todayTemps.first} (${acc.highTempSourceMap[todayDateString]}) " +
            "low=${todayTemps.second} (${acc.lowTempSourceMap[todayDateString]}) " +
            "cond=${acc.conditionMap[todayDateString]} (${acc.conditionSourceMap[todayDateString]})"
        )
    }

    suspend fun persistNwsPeriodSummary(url: String, forecastPeriods: List<NwsApi.ForecastPeriod>) {
        if (forecastPeriods.isEmpty()) return
        val now = ZonedDateTime.now()
        val compactSummary = forecastPeriods.take(NWS_PERIOD_SUMMARY_COUNT).mapIndexed { index, period ->
            val start = runCatching { ZonedDateTime.parse(period.startTime) }.getOrNull()
            val end = runCatching { ZonedDateTime.parse(period.endTime) }.getOrNull()
            val marker = when {
                end != null && end.isBefore(now) -> "PAST"
                start != null && start.isBefore(now) -> "ACTIVE"
                else -> "FUTURE"
            }
            "$index[$marker]:${period.name}@${period.startTime}..${period.endTime}=${period.temperature}"
        }.joinToString("; ")
        appLogDao.log("NWS_PERIOD_SUMMARY", "url=$url first8=$compactSummary")
    }

    suspend fun persistNwsBatchSummary(url: String, summary: NwsBatchSummary) {
        appLogDao.log(
            "NWS_BATCH_SUMMARY",
            "url=$url rawCount=${summary.periodCount} " +
                "rawLast=${summary.lastPeriodName}@${summary.lastPeriodStart}..${summary.lastPeriodEnd}" +
                " temp=${summary.lastPeriodTemp} isDay=${summary.lastPeriodIsDaytime} " +
                "mappedCount=${summary.mappedCount} mappedMaxDate=${summary.mappedMaxTargetDate} " +
                "terminalLowOnlyPreserved=${summary.terminalLowOnlyPreserved} " +
                "preservedDate=${summary.preservedDate} preservedLow=${summary.preservedLowTemp}",
        )
    }

    fun buildBatchSummary(
        forecastPeriods: List<NwsApi.ForecastPeriod>,
        forecastEntities: List<ForecastEntity>,
        preservedTerminalLowOnlyDay: Pair<String, Float>?,
    ): NwsBatchSummary {
        val lastPeriod = forecastPeriods.lastOrNull()
        val mappedMaxTargetDate =
            forecastEntities
                .maxByOrNull { it.targetDate }
                ?.targetDate
                ?.let { LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY).toString() }

        return NwsBatchSummary(
            periodCount = forecastPeriods.size,
            lastPeriodName = lastPeriod?.name,
            lastPeriodStart = lastPeriod?.startTime,
            lastPeriodEnd = lastPeriod?.endTime,
            lastPeriodTemp = lastPeriod?.temperature,
            lastPeriodIsDaytime = lastPeriod?.isDaytime,
            mappedCount = forecastEntities.size,
            mappedMaxTargetDate = mappedMaxTargetDate,
            terminalLowOnlyPreserved = preservedTerminalLowOnlyDay != null,
            preservedDate = preservedTerminalLowOnlyDay?.first,
            preservedLowTemp = preservedTerminalLowOnlyDay?.second,
        )
    }

}
