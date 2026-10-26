package com.weatherwidget.data.remote

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * NWS's forecast fetch on both platforms: the forecast only, as the shared [RawFetch] every source is
 * stored from (`ForecastPersistRun`), with the diagnostic lines. Station observations and the current
 * reading are the observation path's, never this fetch's (decision F6, user 2026-10-25,
 * `plans/261025-shared-source-fetch-table.md`; desktop pulled the week's station observations here).
 * Was Android's `NwsForecastMapper.fetchFromNws` and desktop's `fetchNwsForecast`.
 *
 * [RawFetch.elapsedHourly]: the issuance's already-elapsed hours from the raw grid — history-only
 * material for the elapsed-hour backfill, never written live; empty when the grid leg failed.
 */
object NwsForecastReply {
    private const val PERIOD_SUMMARY_COUNT = 8

    data class BatchSummary(
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

    suspend fun fetch(
        nwsApi: NwsApi,
        latitude: Double,
        longitude: Double,
        parts: ForecastParts,
        log: suspend (tag: String, message: String, level: String) -> Unit,
        today: LocalDate = LocalDate.now(),
        nowMs: Long = System.currentTimeMillis(),
    ): RawFetch = coroutineScope {
        val grid = nwsApi.getGridPoint(latitude, longitude)
        val bundle = NwsForecastFetch.fetch(nwsApi, grid, parts)
        bundle.gridpointsFailure?.let { e ->
            log(
                "NWS_GRIDPOINTS_FAIL",
                "exception=${e::class.simpleName} message=${e.message} grid=${grid.gridId}/${grid.gridX},${grid.gridY}",
                "DEBUG",
            )
        }
        val forecastPeriods = bundle.forecastPeriods
        val hourlyPeriods = bundle.hourlyPeriods
        val gridpoints = bundle.gridpoints
        val gridDailyTemps = gridpoints.dailyTemperatures

        // If skyCover is empty after a successful fetch, the API returned a structurally valid response
        // missing the skyCover field — distinct from the failure path above. Sibling-field counts tell the
        // two cases apart post-hoc.
        if (gridpoints.skyCoverByHour.isEmpty()) {
            log(
                "NWS_SKYCOVER_EMPTY",
                "grid=${grid.gridId}/${grid.gridX},${grid.gridY} qpf=${gridpoints.qpfIntervals.size} " +
                    "maxDays=${gridDailyTemps.maxByDate.size} minDays=${gridDailyTemps.minByDate.size}",
                "DEBUG",
            )
        }

        // Gridpoint maxTemperature/minTemperature are NOT observations — they are the raw NDFD *forecast*
        // grid. NWS actuals come from station observations (plans/260808-nws-actuals-forecast-contamination.md).

        // Hourly-only (ForecastParts.HOURLY): no /forecast periods were asked, so there is no daily batch
        // to assemble or summarize; the caller stores the hours only.
        if (!parts.daily) {
            val (hourlyOnly, elapsedOnly) = hourlyResult(bundle, nowMs, log)
            return@coroutineScope RawFetch(hourly = hourlyOnly, elapsedHourly = elapsedOnly)
        }

        logPeriodSummary(grid.forecastUrl, forecastPeriods, log)

        val todayDateString = today.toString()
        val assembly = NwsDailyMapper.assemble(forecastPeriods, gridDailyTemps, hourlyPeriods, today)
        val acc = assembly.acc

        if (assembly.gridMergedDates.isNotEmpty()) {
            val detail = assembly.gridMergedDates.sorted().joinToString(",") { d ->
                val (h, l) = acc.temperatureMap[d] ?: (null to null)
                "$d:h=$h/l=$l"
            }
            log("NWS_GRID_TEMP_PRIMARY", "dates=${assembly.gridMergedDates.size} $detail", "DEBUG")
        }

        // Today's condition from its daytime period is set in NwsDailyMapper.assemble.
        acc.temperatureMap[todayDateString]?.let { todayTemps ->
            log(
                "NWS_TODAY_SOURCE",
                "high=${todayTemps.first} (${acc.highTempSourceMap[todayDateString]}) " +
                    "low=${todayTemps.second} (${acc.lowTempSourceMap[todayDateString]}) " +
                    "cond=${acc.conditionMap[todayDateString]} (${acc.conditionSourceMap[todayDateString]})",
                "DEBUG",
            )
        }

        if (assembly.divergedTemps.isNotEmpty()) {
            log(
                "NWS_TEMP_DIVERGED",
                "count=${assembly.divergedTemps.size} tolerance=${NwsDailyMapper.HOURLY_DIVERGENCE_TOLERANCE_F}" +
                    " ${assembly.divergedTemps.joinToString("; ") { it.describe() }}",
                "WARN",
            )
        }

        val rejectedTemps = assembly.rejectedTemps
        if (rejectedTemps.isNotEmpty()) {
            log("NWS_TEMP_REJECTED", "count=${rejectedTemps.size} ${rejectedTemps.joinToString("; ") { it.describe() }}", "WARN")
            log(
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
            log("NWS_PARTIAL_DAY_KEEP", "date=$date low=$lowTemp lowSource=${acc.lowTempSourceMap[date]}", "DEBUG")
        }

        val daily = NwsDailyMapper.dailyForecasts(acc)
        logBatchSummary(grid.forecastUrl, batchSummary(forecastPeriods, daily, preservedTerminalLowOnlyDay), log)

        val (hourly, elapsedHourly) = hourlyResult(bundle, nowMs, log)
        RawFetch(hourly = hourly, elapsedHourly = elapsedHourly, daily = daily)
    }

    /** The forecast hours (stamped [nowMs]) and the issuance's elapsed hours, from [bundle]. */
    private suspend fun hourlyResult(
        bundle: NwsForecastBundle,
        nowMs: Long,
        log: suspend (String, String, String) -> Unit,
    ): Pair<List<HourlyForecast>, List<HourlyForecast>> {
        val hourly = bundle.hourlyPeriods.map { period ->
            val periodAgeMs = nowMs - period.startTime
            if (periodAgeMs > 0 && period.startTime % HOUR_MS == 0L) {
                val time = Instant.ofEpochMilli(period.startTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
                log("NWS_HOURLY_STALE", "time=$time temp=${period.temperature} ageMin=${periodAgeMs / 60000}", "DEBUG")
            }
            HourlyForecast(
                dateTime = period.startTime,
                temperature = period.temperature,
                condition = period.shortForecast,
                source = WeatherSource.NWS.id,
                precipProbability = period.precipProbability,
                // NWS reports a single sky-cover value, not a layer breakdown; the graph's fallback draws
                // the total for this source.
                cloudCover = period.cloudCover,
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
        return hourly to elapsedHourly
    }

    private suspend fun logPeriodSummary(
        url: String,
        forecastPeriods: List<NwsApi.ForecastPeriod>,
        log: suspend (String, String, String) -> Unit,
    ) {
        if (forecastPeriods.isEmpty()) return
        val now = ZonedDateTime.now()
        val compactSummary = forecastPeriods.take(PERIOD_SUMMARY_COUNT).mapIndexed { index, period ->
            val start = runCatching { ZonedDateTime.parse(period.startTime) }.getOrNull()
            val end = runCatching { ZonedDateTime.parse(period.endTime) }.getOrNull()
            val marker = when {
                end != null && end.isBefore(now) -> "PAST"
                start != null && start.isBefore(now) -> "ACTIVE"
                else -> "FUTURE"
            }
            "$index[$marker]:${period.name}@${period.startTime}..${period.endTime}=${period.temperature}"
        }.joinToString("; ")
        log("NWS_PERIOD_SUMMARY", "url=$url first8=$compactSummary", "DEBUG")
    }

    private suspend fun logBatchSummary(url: String, summary: BatchSummary, log: suspend (String, String, String) -> Unit) {
        log(
            "NWS_BATCH_SUMMARY",
            "url=$url rawCount=${summary.periodCount} " +
                "rawLast=${summary.lastPeriodName}@${summary.lastPeriodStart}..${summary.lastPeriodEnd}" +
                " temp=${summary.lastPeriodTemp} isDay=${summary.lastPeriodIsDaytime} " +
                "mappedCount=${summary.mappedCount} mappedMaxDate=${summary.mappedMaxTargetDate} " +
                "terminalLowOnlyPreserved=${summary.terminalLowOnlyPreserved} " +
                "preservedDate=${summary.preservedDate} preservedLow=${summary.preservedLowTemp}",
            "DEBUG",
        )
    }

    fun batchSummary(
        forecastPeriods: List<NwsApi.ForecastPeriod>,
        daily: List<DailyForecast>,
        preservedTerminalLowOnlyDay: Pair<String, Float>?,
    ): BatchSummary {
        val lastPeriod = forecastPeriods.lastOrNull()
        return BatchSummary(
            periodCount = forecastPeriods.size,
            lastPeriodName = lastPeriod?.name,
            lastPeriodStart = lastPeriod?.startTime,
            lastPeriodEnd = lastPeriod?.endTime,
            lastPeriodTemp = lastPeriod?.temperature,
            lastPeriodIsDaytime = lastPeriod?.isDaytime,
            mappedCount = daily.size,
            mappedMaxTargetDate = daily.maxOfOrNull { it.date },
            terminalLowOnlyPreserved = preservedTerminalLowOnlyDay != null,
            preservedDate = preservedTerminalLowOnlyDay?.first,
            preservedLowTemp = preservedTerminalLowOnlyDay?.second,
        )
    }

    private const val HOUR_MS = 3_600_000L
}
