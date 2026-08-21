package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsDailyMapper
import com.weatherwidget.data.remote.NwsHourlyGridMerge
import com.weatherwidget.widget.WidgetConstants
import kotlinx.coroutines.async
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

    suspend fun fetchFromNws(
        latitude: Double,
        longitude: Double,
    ): Pair<List<ForecastEntity>, List<HourlyForecastEntity>> = coroutineScope {
        val grid = nwsApi.getGridPoint(latitude, longitude)
        val forecastDeferred = async { nwsApi.getForecast(grid) }
        val hourlyDeferred = async { nwsApi.getHourlyForecast(grid) }
        val gridpointsDeferred = async {
            try {
                nwsApi.getGridpointsBundle(grid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "getGridpointsBundle failed: ${e.message}")
                appLogDao.log(
                    "NWS_GRIDPOINTS_FAIL",
                    "exception=${e::class.simpleName} message=${e.message} " +
                        "grid=${grid.gridId}/${grid.gridX},${grid.gridY}",
                )
                NwsApi.GridpointsBundle(
                    skyCoverByHour = emptyMap(),
                    qpfIntervals = emptyList(),
                    dailyTemperatures = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap()),
                )
            }
        }

        val forecastPeriods = forecastDeferred.await()
        val rawHourlyPeriods = hourlyDeferred.await()
        val gridpoints = gridpointsDeferred.await()
        val skyCoverMap = gridpoints.skyCoverByHour
        val gridQpfIntervals = gridpoints.qpfIntervals
        val gridDailyTemps = gridpoints.dailyTemperatures

        // If skyCover is empty after a successful fetch, the API returned a structurally
        // valid response missing the skyCover field — distinct from the catch-block path
        // above. Log enough sibling-field counts to tell the two cases apart post-hoc.
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

        // Sky cover + grid QPF live in the gridpoints response, not the hourly endpoint. Merge
        // them onto the hourly rows via the shared helper so Android and desktop populate
        // identical cloudCover/precip data. See NwsHourlyGridMerge.
        val hourlyPeriods = NwsHourlyGridMerge.applyGridpointData(
            rawHourlyPeriods, skyCoverMap, gridQpfIntervals,
        )

        persistNwsPeriodSummary(grid.forecastUrl, forecastPeriods)

        val todayDate = LocalDate.now()
        val todayDateString = todayDate.toString()

        val acc = NwsDailyMapper.NwsDayAccumulator()

        initPrecipFromHourly(hourlyPeriods, todayDate, acc.precipProbabilityMap, acc.precipAmountMap)
        initConditionsFromHourly(hourlyPeriods, acc.conditionMap, acc.conditionSourceMap)

        val gridMergedDates = NwsDailyMapper.mergeGridpointTemperatures(
            acc.temperatureMap, gridDailyTemps, todayDate,
            highTempSourceMap = acc.highTempSourceMap,
            lowTempSourceMap = acc.lowTempSourceMap,
        )
        if (gridMergedDates.isNotEmpty()) {
            val detail = gridMergedDates.sorted().joinToString(",") { d ->
                val (h, l) = acc.temperatureMap[d] ?: (null to null)
                "$d:h=${h}/l=${l}"
            }
            appLogDao.log("NWS_GRID_TEMP_PRIMARY", "dates=${gridMergedDates.size} $detail")
        }

        val todayForecastPeriods = NwsDailyMapper.applyForecastPeriods(
            forecastPeriods, todayDateString, acc
        )
        logTodayDiagnostics(
            todayDateString, todayForecastPeriods, acc
        )

        // NWS can serve a -100°F missing-data sentinel on both the gridpoint and period paths (see
        // NwsTemperaturePlausibility). Both were dropped at ingest; recover the real value from the
        // hourly series, which does not carry the sentinel.
        // Relative cross-check, on top of the absolute range gate above: a value can be inside
        // NwsTemperaturePlausibility's bounds and still be plainly wrong (a July low of 20°F). The
        // absolute gate cannot see that; the provider's own hourly series for the same day can.
        // Diverging values must be cleared before the repair runs, because the repair only fills
        // nulls — unlike the absolute gate's rejections, these were actually written.
        val divergedTemps = NwsDailyMapper.detectHourlyDivergence(acc.temperatureMap, hourlyPeriods)
        if (divergedTemps.isNotEmpty()) {
            appLogDao.log(
                "NWS_TEMP_DIVERGED",
                "count=${divergedTemps.size} tolerance=${NwsDailyMapper.HOURLY_DIVERGENCE_TOLERANCE_F}" +
                    " ${divergedTemps.joinToString("; ") { it.describe() }}",
                "WARN",
            )
            NwsDailyMapper.clearRejectedTemps(acc.temperatureMap, divergedTemps)
        }

        val rejectedTemps = gridDailyTemps.rejected + acc.rejectedTemps + divergedTemps
        if (rejectedTemps.isNotEmpty()) {
            appLogDao.log(
                "NWS_TEMP_REJECTED",
                "count=${rejectedTemps.size} ${rejectedTemps.joinToString("; ") { it.describe() }}",
                "WARN",
            )
            val repairs = NwsDailyMapper.fillTemperatureGapsFromHourly(
                acc.temperatureMap, rejectedTemps, hourlyPeriods,
                highTempSourceMap = acc.highTempSourceMap,
                lowTempSourceMap = acc.lowTempSourceMap,
            )
            appLogDao.log(
                "NWS_TEMP_REJECTED",
                if (repairs.isEmpty()) {
                    "no hourly repair available for ${rejectedTemps.size} rejected value(s)"
                } else {
                    "repaired=${repairs.size} ${repairs.joinToString("; ")}"
                },
                "WARN",
            )
        }

        val preservedTerminalLowOnlyDay = NwsDailyMapper.removePhantomFutureDays(acc.temperatureMap, todayDate)
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

        Pair(forecastEntities, hourlyEntities)
    }

    fun initPrecipFromHourly(
        hourlyPeriods: List<NwsApi.HourlyForecastPeriod>,
        todayDate: LocalDate,
        precipProbabilityMap: MutableMap<String, Int>,
        precipAmountMap: MutableMap<String, Float>,
    ) {
        hourlyPeriods.forEach { hour ->
            val dateString = hour.localDate
            if (dateString == todayDate.toString()) {
                val probability = hour.precipProbability ?: 0
                if (probability > (precipProbabilityMap[dateString] ?: 0)) {
                    precipProbabilityMap[dateString] = probability
                }
            }
            hour.precipAmountMm?.let { amount ->
                precipAmountMap[dateString] = (precipAmountMap[dateString] ?: 0f) + amount
            }
        }
    }

    fun initConditionsFromHourly(
        hourlyPeriods: List<NwsApi.HourlyForecastPeriod>,
        conditionMap: MutableMap<String, String>,
        sourceMap: MutableMap<String, String>,
    ) {
        val todayDate = LocalDate.now()
        hourlyPeriods.groupBy { it.localDate }
            .forEach { (dateString, periods) ->
                if (LocalDate.parse(dateString).isAfter(todayDate)) {
                    val targetHours = listOf(13, 14, 12, 15)
                    var bestPeriod: NwsApi.HourlyForecastPeriod? = null
                    for (hour in targetHours) {
                        bestPeriod = periods.find { it.localHour == hour }
                        if (bestPeriod != null) break
                    }

                    if (bestPeriod != null) {
                        val midText = bestPeriod.shortForecast
                        val hasFog = periods.any {
                            it.localHour in 5..10 && it.shortForecast.lowercase().contains("fog")
                        }
                        val isSunny = midText.lowercase().contains("sunny") || midText.lowercase().contains("clear")

                        if (hasFog && isSunny) {
                            conditionMap[dateString] = "Fog then $midText"
                            sourceMap[dateString] = "HOURLY_MIDDAY_TRANSITION:${bestPeriod.startTime}"
                            return@forEach
                        }

                        if (midText.lowercase().contains("fog")) {
                            periods.find { it.shortForecast.lowercase().contains("sunny") || it.shortForecast.lowercase().contains("clear") }?.let {
                                conditionMap[dateString] = it.shortForecast
                                sourceMap[dateString] = "HOURLY_MIDDAY_SUN_PRIORITY:${it.startTime}"
                                return@forEach
                            }
                        }

                        conditionMap[dateString] = midText
                        sourceMap[dateString] = "HOURLY_MIDDAY:${bestPeriod.startTime}"
                    }
                }
            }
    }

    suspend fun logTodayDiagnostics(
        todayDateString: String,
        todayPeriods: List<NwsApi.ForecastPeriod>,
        acc: NwsDailyMapper.NwsDayAccumulator,
    ) {
        todayPeriods.firstOrNull { it.isDaytime }?.let { period ->
            acc.conditionMap[todayDateString] = period.shortForecast
            acc.conditionSourceMap[todayDateString] = "FCST_DAY:${period.name}@${period.startTime}"
        }

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
