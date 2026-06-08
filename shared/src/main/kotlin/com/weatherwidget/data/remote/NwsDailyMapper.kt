package com.weatherwidget.data.remote

import com.weatherwidget.data.model.DailyForecast
import java.time.LocalDate
import java.time.ZonedDateTime

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
    ): List<DailyForecast> {
        val acc = NwsDayAccumulator()
        applyForecastPeriods(periods, today.toString(), acc)
        mergeGridpointTemperatures(acc.temperatureMap, extremes, today)
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
    )
}
