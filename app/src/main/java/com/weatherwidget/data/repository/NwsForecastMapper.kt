package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.widget.WidgetConstants
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

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
        locationName: String,
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

        val hourlyPeriodsWithSkyCover = if (skyCoverMap.isNotEmpty()) {
            rawHourlyPeriods.map { period ->
                val hourKey = runCatching {
                    Instant.ofEpochMilli(period.startTime)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                }.getOrNull()
                val cover = hourKey?.let { skyCoverMap[it] }
                if (cover != null) period.copy(cloudCover = cover) else period
            }
        } else {
            rawHourlyPeriods
        }
        val hourlyPeriods = if (gridQpfIntervals.isNotEmpty()) {
            hourlyPeriodsWithSkyCover.map { period ->
                val gridAmount = resolveGridQpfForHourlyPeriod(period, gridQpfIntervals)
                if (gridAmount != null) {
                    period.copy(precipAmountMm = gridAmount)
                } else {
                    period
                }
            }
        } else {
            hourlyPeriodsWithSkyCover
        }

        persistNwsPeriodSummary(grid.forecastUrl, forecastPeriods)

        val todayDate = LocalDate.now()
        val todayDateString = todayDate.toString()

        val acc = NwsDayAccumulator()

        initPrecipFromHourly(hourlyPeriods, todayDate, acc.precipProbabilityMap, acc.precipAmountMap)
        initConditionsFromHourly(hourlyPeriods, acc.conditionMap, acc.conditionSourceMap)

        val gridMergedDates = mergeGridpointTemperatures(
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

        val todayForecastPeriods = applyForecastPeriods(
            forecastPeriods, todayDateString, acc
        )
        logTodayDiagnostics(
            todayDateString, todayForecastPeriods, acc
        )

        val preservedTerminalLowOnlyDay = removePhantomFutureDays(acc.temperatureMap, todayDate)
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
                forecastDate = todayDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                locationLat = latitude,
                locationLon = longitude,
                locationName = locationName,
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

        val hourlyEntities = hourlyPeriods.map { period ->
            HourlyForecastEntity(
                period.startTime, latitude, longitude, period.temperature,
                period.shortForecast, WeatherSource.NWS.id, period.precipProbability, period.cloudCover, period.precipAmountMm, System.currentTimeMillis()
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

    fun resolveGridQpfForHourlyPeriod(
        period: NwsApi.HourlyForecastPeriod,
        intervals: List<NwsApi.QuantitativePrecipitationInterval>,
    ): Float? {
        if (intervals.isEmpty()) return null
        val periodEnd = period.startTime + 60 * 60 * 1000L
        val overlapping = intervals.filter { interval ->
            interval.startTime < periodEnd && interval.endTime > period.startTime
        }
        if (overlapping.isEmpty()) return null

        return overlapping.sumOf { interval ->
            val overlapStart = max(period.startTime, interval.startTime)
            val overlapEnd = min(periodEnd, interval.endTime)
            val overlapMs = (overlapEnd - overlapStart).coerceAtLeast(0L)
            if (overlapMs == 0L) {
                0.0
            } else {
                val intervalMs = (interval.endTime - interval.startTime).coerceAtLeast(1L)
                interval.amountMm.toDouble() * overlapMs.toDouble() / intervalMs.toDouble()
            }
        }.toFloat()
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

    companion object {
        fun extractNwsForecastDate(isoString: String): String? =
            runCatching { ZonedDateTime.parse(isoString).toLocalDate().toString() }.getOrNull()
            ?: runCatching { LocalDate.parse(isoString.take(10)).toString() }.getOrNull()

        fun applyForecastPeriods(
            forecastPeriods: List<NwsApi.ForecastPeriod>,
            todayDateString: String,
            acc: NwsDayAccumulator,
        ): List<NwsApi.ForecastPeriod> {
            val todayPeriods = mutableListOf<NwsApi.ForecastPeriod>()
            forecastPeriods.forEach { period ->
                val dateString = extractNwsForecastDate(period.startTime) ?: return@forEach
            if (dateString == todayDateString) todayPeriods.add(period)

            val periodAmount = period.precipAmountMm
            if (periodAmount != null && !acc.precipAmountMap.containsKey(dateString)) {
                acc.precipAmountMap[dateString] = periodAmount
            }

            if (period.isDaytime) {
                period.precipProbability?.let { probability ->
                    acc.daytimePrecipProbabilityMap[dateString] = probability
                    if (dateString != todayDateString) {
                        acc.precipProbabilityMap[dateString] = probability
                    }
                }
                val currentTemps = acc.temperatureMap[dateString] ?: (null to null)
                val newHigh = currentTemps.first ?: period.temperature.toFloat()
                acc.temperatureMap[dateString] = newHigh to currentTemps.second
                if (currentTemps.first == null) {
                    acc.highTempSourceMap[dateString] = "FCST:${period.name}@${period.startTime}"
                }
                acc.periodTimeMap[dateString] = period.startTime to period.endTime
            } else {
                period.precipProbability?.let { probability ->
                    acc.nighttimePrecipProbabilityMap[dateString] = probability
                }
                val lowDateString = extractNwsForecastDate(period.endTime) ?: dateString
                val currentLowTemps = acc.temperatureMap[lowDateString] ?: (null to null)
                val newLow = currentLowTemps.second ?: period.temperature.toFloat()
                acc.temperatureMap[lowDateString] = currentLowTemps.first to newLow
                if (currentLowTemps.second == null) {
                    acc.lowTempSourceMap[lowDateString] = "FCST:${period.name}@${period.startTime}"
                }
            }

            if (acc.conditionMap[dateString] == null) {
                acc.conditionMap[dateString] = period.shortForecast
                acc.conditionSourceMap[dateString] = "FCST:${period.name}@${period.startTime}"
            }
        }
        return todayPeriods
        }

        /**
         * Merge daily highs/lows from the raw NWS gridpoints endpoint into temperatureMap.
         * Only fills nulls — values already supplied by /forecast take precedence.
         * Caps at horizonDays days from today to avoid unbounded ingestion if NWS extends the gridpoints window.
         * Returns the set of dateStrings whose temperatureMap entry changed (for diagnostics).
         */
        fun mergeGridpointTemperatures(
            temperatureMap: MutableMap<String, Pair<Float?, Float?>>,
            extremes: NwsApi.DailyTemperatureExtremes,
            today: LocalDate,
            horizonDays: Int = 8,
            highTempSourceMap: MutableMap<String, String>? = null,
            lowTempSourceMap: MutableMap<String, String>? = null,
        ): Set<String> {
            val changed = mutableSetOf<String>()
            val maxDate = today.plusDays((horizonDays - 1).toLong())
            val candidateDates = extremes.maxByDate.keys + extremes.minByDate.keys
            for (dateString in candidateDates) {
                val date = runCatching { LocalDate.parse(dateString) }.getOrNull() ?: continue
                if (date.isBefore(today) || date.isAfter(maxDate)) continue
                val current = temperatureMap[dateString] ?: (null to null)
                val highFromGrid = current.first == null && extremes.maxByDate.containsKey(dateString)
                val lowFromGrid = current.second == null && extremes.minByDate.containsKey(dateString)
                val newHigh = current.first ?: extremes.maxByDate[dateString]
                val newLow = current.second ?: extremes.minByDate[dateString]
                if (newHigh != current.first || newLow != current.second) {
                    temperatureMap[dateString] = newHigh to newLow
                    if (highFromGrid) highTempSourceMap?.put(dateString, "GRID:max")
                    if (lowFromGrid) lowTempSourceMap?.put(dateString, "GRID:min")
                    changed.add(dateString)
                }
            }
            return changed
        }

        fun removePhantomFutureDays(
            temperatureMap: MutableMap<String, Pair<Float?, Float?>>,
            today: LocalDate,
        ): Pair<String, Float>? {
            val lastFutureDate =
                temperatureMap.keys
                    .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                    .filter { it.isAfter(today) }
                    .maxOrNull()

            val preserved =
                lastFutureDate?.toString()?.let { dateStr ->
                    temperatureMap[dateStr]?.let { temps ->
                        if (temps.first == null && temps.second != null) {
                            dateStr to temps.second!!
                        } else {
                            null
                        }
                    }
                }

            temperatureMap.entries.removeAll { (dateStr, temps) ->
                val date = LocalDate.parse(dateStr)
                date.isAfter(today) &&
                    temps.first == null &&
                    !(date == lastFutureDate && temps.second != null)
            }

            return preserved
        }
    }

    suspend fun logTodayDiagnostics(
        todayDateString: String,
        todayPeriods: List<NwsApi.ForecastPeriod>,
        acc: NwsDayAccumulator,
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

    data class NwsDayAccumulator(
        val temperatureMap: MutableMap<String, Pair<Float?, Float?>> = mutableMapOf(),
        val conditionMap: MutableMap<String, String> = mutableMapOf(),
        val conditionSourceMap: MutableMap<String, String> = mutableMapOf(),
        val highTempSourceMap: MutableMap<String, String> = mutableMapOf(),
        val lowTempSourceMap: MutableMap<String, String> = mutableMapOf(),
        val precipProbabilityMap: MutableMap<String, Int> = mutableMapOf(),
        val daytimePrecipProbabilityMap: MutableMap<String, Int> = mutableMapOf(),
        val nighttimePrecipProbabilityMap: MutableMap<String, Int> = mutableMapOf(),
        val precipAmountMap: MutableMap<String, Float> = mutableMapOf(),
        val periodTimeMap: MutableMap<String, Pair<String?, String?>> = mutableMapOf(),
    )
}
