package com.weatherwidget.data.remote

import com.weatherwidget.data.model.DailyForecast
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Pure NWS daily-forecast mapping logic shared between the Android widget and the desktop app.
 *
 * Previously this lived only in Android's `NwsForecastMapper`, and the desktop carried its own
 * simpler reimplementation (`DesktopWeatherService.mapNwsToDaily`). The two diverged in a subtle
 * but important way: the desktop grouped every forecast period by its `startTime` date, so an
 * overnight "Tonight"/"Saturday Night" period (which ends the next morning) was filed under the
 * evening date instead of the morning date. That left the final forecast day with no overnight low
 * and fabricated `low = daytime high` — a degenerate flat bar. Keeping a single shared
 * implementation makes both platforms agree on the calendar-day convention (a day's low is the low
 * of the night that *ends* that morning) and on the gridpoints backstop.
 */
object NwsDailyMapper {

    fun extractNwsForecastDate(isoString: String): String? =
        runCatching { ZonedDateTime.parse(isoString).toLocalDate().toString() }.getOrNull()
            ?: runCatching { LocalDate.parse(isoString.take(10)).toString() }.getOrNull()

    /**
     * Folds the human-readable NWS `/forecast` day/night periods into [acc]. Daytime periods supply
     * the high (keyed by the period's start date); night periods supply the low keyed by the date
     * the night *ends* (the morning), so a calendar day pairs its morning low with its afternoon
     * high. Only fills nulls — earlier values (e.g. gridpoint merges) take precedence.
     * Returns the subset of periods that fall on [todayDateString] (used for diagnostics).
     */
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

            // The same -100°F sentinel that poisons the gridpoint series also appears here. Reject it
            // before it can become a temperature, but keep the period's condition and precip data.
            val periodTempF = period.temperature.toFloat()
            val usableTemp = NwsTemperaturePlausibility.isPlausibleF(periodTempF)

            if (period.isDaytime) {
                period.precipProbability?.let { probability ->
                    acc.daytimePrecipProbabilityMap[dateString] = probability
                    if (dateString != todayDateString) {
                        acc.precipProbabilityMap[dateString] = probability
                    }
                }
                val currentTemps = acc.temperatureMap[dateString] ?: (null to null)
                if (!usableTemp) {
                    acc.recordRejectedTemp(period, dateString, isMax = true, tempF = periodTempF)
                } else {
                    val newHigh = currentTemps.first ?: periodTempF
                    acc.temperatureMap[dateString] = newHigh to currentTemps.second
                    if (currentTemps.first == null) {
                        acc.highTempSourceMap[dateString] = "FCST:${period.name}@${period.startTime}"
                    }
                }
                acc.periodTimeMap[dateString] = period.startTime to period.endTime
            } else {
                period.precipProbability?.let { probability ->
                    acc.nighttimePrecipProbabilityMap[dateString] = probability
                }
                val lowDateString = extractNwsForecastDate(period.endTime) ?: dateString
                val currentLowTemps = acc.temperatureMap[lowDateString] ?: (null to null)
                if (!usableTemp) {
                    acc.recordRejectedTemp(period, lowDateString, isMax = false, tempF = periodTempF)
                } else {
                    val newLow = currentLowTemps.second ?: periodTempF
                    acc.temperatureMap[lowDateString] = currentLowTemps.first to newLow
                    if (currentLowTemps.second == null) {
                        acc.lowTempSourceMap[lowDateString] = "FCST:${period.name}@${period.startTime}"
                    }
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

    /**
     * Removes phantom future days that have only a low (or nothing) once all sources are merged,
     * except the single terminal low-only day, which is preserved (NWS legitimately reports the
     * final overnight low without a following daytime high). Returns that preserved (date, low) if
     * any, for diagnostics.
     */
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

    /**
     * High-level convenience for callers (the desktop) that only need a plain [DailyForecast] list:
     * folds the day/night periods with the calendar-day convention, fills gaps from the gridpoints
     * [extremes], drops phantom future days, then projects each day into a [DailyForecast].
     *
     * Days whose high is still null after merging are skipped — the plain [DailyForecast] model has
     * non-null temperatures and cannot render a high-less day. When a real low cannot be resolved it
     * falls back to the high (only reachable beyond the gridpoints horizon), never to a fabricated
     * value from an unrelated period.
     */
    fun buildDailyForecasts(
        periods: List<NwsApi.ForecastPeriod>,
        extremes: NwsApi.DailyTemperatureExtremes,
        today: LocalDate,
        hourlyPeriods: List<NwsApi.HourlyForecastPeriod> = emptyList(),
    ): List<DailyForecast> {
        val acc = NwsDayAccumulator()
        applyForecastPeriods(periods, today.toString(), acc)
        mergeGridpointTemperatures(acc.temperatureMap, extremes, today)
        // Recover anything the plausibility gate dropped before phantom-day removal, so a day whose
        // only defect was a sentinel low is repaired rather than discarded. Keeps desktop at parity
        // with Android's NwsForecastMapper.
        fillTemperatureGapsFromHourly(
            acc.temperatureMap, extremes.rejected + acc.rejectedTemps, hourlyPeriods,
            highTempSourceMap = acc.highTempSourceMap,
            lowTempSourceMap = acc.lowTempSourceMap,
        )
        removePhantomFutureDays(acc.temperatureMap, today)

        val periodsByDate = periods.groupBy { extractNwsForecastDate(it.startTime) }

        return acc.temperatureMap.mapNotNull { (date, temps) ->
            val high = temps.first ?: return@mapNotNull null
            val low = temps.second ?: high
            val dayPeriods = periodsByDate[date].orEmpty()
            val condition = dayPeriods.firstOrNull { it.isDaytime }?.shortForecast
                ?: dayPeriods.firstOrNull()?.shortForecast
                ?: acc.conditionMap[date]
                ?: ""
            val precipProbability = dayPeriods.mapNotNull { it.precipProbability }.maxOrNull()
                ?: acc.precipProbabilityMap[date]
            DailyForecast(
                date = date,
                highTemp = high,
                lowTemp = low,
                condition = condition,
                precipProbability = precipProbability,
                precipAmountMm = acc.precipAmountMap[date],
                // NWS's native 12-hour period chances — used by the daily rain label only as a
                // fallback when hourly rows are missing, and by the past-day path (see
                // DailyRainLabels.resolveDailyLabelPrecip). Note NWS periods run 6am/6pm, not the
                // app's 8am/8pm day/night boundary.
                daytimePrecipProbability = acc.daytimePrecipProbabilityMap[date],
                nighttimePrecipProbability = acc.nighttimePrecipProbabilityMap[date],
            )
        }.sortedBy { it.date }
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
        /** Implausible values dropped at ingest, for the hourly repair path and diagnostics. */
        val rejectedTemps: MutableList<RejectedNwsTemperature> = mutableListOf(),
    ) {
        internal fun recordRejectedTemp(
            period: NwsApi.ForecastPeriod,
            dateString: String,
            isMax: Boolean,
            tempF: Float,
        ) {
            val startMs = parseInstantMs(period.startTime) ?: return
            val endMs = parseInstantMs(period.endTime) ?: return
            rejectedTemps += RejectedNwsTemperature(
                origin = "FCST:${period.name}",
                dateString = dateString,
                isMax = isMax,
                windowStartMs = startMs,
                windowEndMs = endMs,
                rawValueF = tempF,
            )
        }
    }

    private fun parseInstantMs(isoString: String): Long? =
        runCatching { ZonedDateTime.parse(isoString).toInstant().toEpochMilli() }.getOrNull()

    /**
     * How far a stored daily extreme may sit from the same day's hourly extreme before we stop
     * believing it. Deliberately wide.
     *
     * The two series legitimately disagree by a degree or two: NWS files a day's low against the
     * night that *ends* that morning, while this check buckets hourly readings by calendar day, and
     * provider rounding differs between the daily and hourly endpoints. Observed on 2026-07-28:
     * daily high 78°F against a calendar-day hourly max of 80°F — normal, and it must not trip.
     * A sentinel, by contrast, diverges by ~140°F. The gap between those two magnitudes is where
     * this threshold lives; tightening it toward equality would produce constant false positives.
     */
    const val HOURLY_DIVERGENCE_TOLERANCE_F = 20f

    /**
     * A day needs this many plausible hourly readings before its hourly extreme is trustworthy
     * enough to judge the daily value. The last day of a forecast horizon is often a short partial
     * (57 rows spanning a fraction of the day, in the 2026-07-28 capture), and a partial day's min
     * can be far off the true min purely because the cold hours are missing.
     */
    const val MIN_HOURS_FOR_DIVERGENCE_CHECK = 12

    /**
     * Cross-checks each stored daily extreme against the same day's hourly series and reports the
     * ones that diverge implausibly far, as [RejectedNwsTemperature] so they feed the existing
     * [fillTemperatureGapsFromHourly] repair path.
     *
     * This is the *relative* counterpart to [NwsTemperaturePlausibility]'s absolute range gate. The
     * absolute gate can only catch values outside all of terrestrial weather; a July low of 20°F is
     * badly wrong but sails straight through it. Comparing against what the same provider said in
     * the same fetch is a far sharper instrument.
     *
     * The reported window is the calendar day, not the originating forecast period — that is the
     * span we can reconstruct reliably here, and it costs at most a degree or two of precision on
     * the subsequent repair (see [HOURLY_DIVERGENCE_TOLERANCE_F]). A degree of imprecision is an
     * enormously better outcome than a sentinel reaching the renderer.
     */
    fun detectHourlyDivergence(
        temperatureMap: Map<String, Pair<Float?, Float?>>,
        hourlyPeriods: List<NwsApi.HourlyForecastPeriod>,
        toleranceF: Float = HOURLY_DIVERGENCE_TOLERANCE_F,
    ): List<RejectedNwsTemperature> {
        if (hourlyPeriods.isEmpty()) return emptyList()
        val byDate = hourlyPeriods
            .filter { NwsTemperaturePlausibility.isPlausibleF(it.temperature) }
            .groupBy { it.localDate }

        val diverged = mutableListOf<RejectedNwsTemperature>()
        for ((dateString, temps) in temperatureMap) {
            val hours = byDate[dateString] ?: continue
            if (hours.size < MIN_HOURS_FOR_DIVERGENCE_CHECK) continue

            val windowStartMs = hours.minOf { it.startTime }
            // +1ms so the last reading falls inside the half-open [start, end) the repair applies.
            val windowEndMs = hours.maxOf { it.startTime } + 1
            val (high, low) = temps

            if (high != null && abs(high - hours.maxOf { it.temperature }) > toleranceF) {
                diverged += RejectedNwsTemperature(
                    origin = "XCHK:hourly", dateString = dateString, isMax = true,
                    windowStartMs = windowStartMs, windowEndMs = windowEndMs, rawValueF = high,
                )
            }
            if (low != null && abs(low - hours.minOf { it.temperature }) > toleranceF) {
                diverged += RejectedNwsTemperature(
                    origin = "XCHK:hourly", dateString = dateString, isMax = false,
                    windowStartMs = windowStartMs, windowEndMs = windowEndMs, rawValueF = low,
                )
            }
        }
        return diverged
    }

    /**
     * Nulls the slots named by [rejected] so [fillTemperatureGapsFromHourly] will repair them —
     * that function only ever fills nulls, so a value rejected by the *relative* check must be
     * cleared first (unlike the absolute gate's rejections, which never got written).
     */
    fun clearRejectedTemps(
        temperatureMap: MutableMap<String, Pair<Float?, Float?>>,
        rejected: List<RejectedNwsTemperature>,
    ) {
        for (miss in rejected) {
            val current = temperatureMap[miss.dateString] ?: continue
            temperatureMap[miss.dateString] =
                if (miss.isMax) null to current.second else current.first to null
        }
    }

    /**
     * Fills temperature slots left null by the plausibility gate using NWS's own hourly series,
     * which does not carry the sentinel. Each repair is computed over the *originating period's*
     * window rather than the calendar day, preserving NWS's convention that a day's low belongs to
     * the night ending that morning — for the 2026-07-27 incident that is the difference between
     * the correct 59°F (18:00→06:00) and a calendar-day 58°F.
     *
     * Only fills what is still null, so sane daily data always wins; this is strictly a repair path.
     * Returns a description of each repair for diagnostics.
     */
    fun fillTemperatureGapsFromHourly(
        temperatureMap: MutableMap<String, Pair<Float?, Float?>>,
        rejected: List<RejectedNwsTemperature>,
        hourlyPeriods: List<NwsApi.HourlyForecastPeriod>,
        highTempSourceMap: MutableMap<String, String>? = null,
        lowTempSourceMap: MutableMap<String, String>? = null,
    ): List<String> {
        if (rejected.isEmpty() || hourlyPeriods.isEmpty()) return emptyList()

        val repairs = mutableListOf<String>()
        for (miss in rejected) {
            val current = temperatureMap[miss.dateString] ?: (null to null)
            // A later sane value may already have filled this slot — never overwrite it.
            if (if (miss.isMax) current.first != null else current.second != null) continue

            val inWindow = hourlyPeriods.filter {
                it.startTime >= miss.windowStartMs && it.startTime < miss.windowEndMs &&
                    NwsTemperaturePlausibility.isPlausibleF(it.temperature)
            }
            if (inWindow.isEmpty()) continue

            val repaired = if (miss.isMax) {
                inWindow.maxOf { it.temperature }
            } else {
                inWindow.minOf { it.temperature }
            }

            temperatureMap[miss.dateString] = if (miss.isMax) {
                repaired to current.second
            } else {
                current.first to repaired
            }
            val sourceLabel = "HOURLY:${if (miss.isMax) "max" else "min"}@${miss.origin}"
            if (miss.isMax) {
                highTempSourceMap?.put(miss.dateString, sourceLabel)
            } else {
                lowTempSourceMap?.put(miss.dateString, sourceLabel)
            }
            repairs += "${miss.describe()} -> $repaired (${inWindow.size}h)"
        }
        return repairs
    }
}
