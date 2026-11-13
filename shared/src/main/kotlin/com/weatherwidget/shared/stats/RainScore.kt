package com.weatherwidget.shared.stats

import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * How a source's rain-amount forecast is graded against measured rain
 * (plans/261113-per-source-rain-score.md).
 *
 * - **Paired hours.** A day's totals sum only hours that hold both a predicted and a measured amount.
 *   A `0` on either side is data (forecast dry, gauge dry); only a *missing* hour is unpaired. Both
 *   totals therefore always cover the same hours, so a coverage gap never reads as forecast error.
 * - A day **counts** with at least [MIN_PAIRED_HOURS] paired hours. That counts hours with data,
 *   not hours with rain: a 6-hour shower among 18 measured-dry hours is 24 paired hours.
 * - The headline error is over **rain days** only (forecast or measured ≥ [RAIN_DAY_MM]); dry-vs-dry
 *   days score ≈0 for everyone and would hide the difference. The hit rate covers every counted day.
 */
object RainScore {
    const val MIN_PAIRED_HOURS = 20

    /** 0.01", the measurable-rain convention. */
    const val RAIN_DAY_MM = 0.254f

    /** Sources whose errors differ by no more than this share the ★. */
    const val TIE_MM = 0.254f

    /** Fewer rain days than this and a source is listed, not ranked. */
    const val MIN_RAIN_DAYS = 3

    private const val EPSILON = 1e-4f

    data class Paired(val predMm: Float, val actualMm: Float, val hours: Int) {
        val counted: Boolean get() = hours >= MIN_PAIRED_HOURS
        val errorMm: Float get() = predMm - actualMm
        val isRainDay: Boolean get() = predMm >= RAIN_DAY_MM || actualMm >= RAIN_DAY_MM
        val hit: Boolean get() = (predMm >= RAIN_DAY_MM) == (actualMm >= RAIN_DAY_MM)
    }

    /** Totals over the hours both [pred] and [actual] hold, restricted to [only] when given. */
    fun pair(
        pred: Map<LocalDateTime, Float>,
        actual: Map<LocalDateTime, Float>,
        only: Set<LocalDateTime>? = null,
    ): Paired {
        var hours = pred.keys intersect actual.keys
        if (only != null) hours = hours intersect only
        return Paired(
            predMm = hours.sumOf { pred.getValue(it).toDouble() }.toFloat(),
            actualMm = hours.sumOf { actual.getValue(it).toDouble() }.toFloat(),
            hours = hours.size,
        )
    }

    /**
     * One date across sources, on the hours **every counted source** shares with the measurement, so
     * a source that is missing the wettest hour cannot win by skipping it. Sources that do not count
     * on their own hours show no total.
     */
    data class DayComparison(
        val commonHours: Int,
        /** Measured total over the common hours; null when the day cannot be compared. */
        val actualMm: Float?,
        /** Each source's predicted total over the common hours; null for a source that did not count. */
        val predMm: Map<WeatherSource, Float?>,
        /** Closest sources (ties within [TIE_MM]); empty on a dry day or with fewer than two sources. */
        val closest: Set<WeatherSource>,
    )

    fun compareDay(
        predBySource: Map<WeatherSource, Map<LocalDateTime, Float>>,
        actual: Map<LocalDateTime, Float>,
    ): DayComparison {
        val counted = predBySource.filterValues { pair(it, actual).counted }
        val common = counted.values.fold(actual.keys) { acc, pred -> acc intersect pred.keys }
        if (counted.isEmpty() || common.size < MIN_PAIRED_HOURS) {
            // With no counted source, report the best any single source managed.
            val hours = if (counted.isEmpty()) predBySource.values.maxOfOrNull { pair(it, actual).hours } ?: 0 else common.size
            return DayComparison(hours, null, predBySource.mapValues { null }, emptySet())
        }
        val totals = counted.mapValues { (_, pred) -> pair(pred, actual, common) }
        val closest = if (totals.size >= 2 && totals.values.any { it.isRainDay }) {
            val best = totals.values.minOf { abs(it.errorMm) }
            totals.filterValues { abs(it.errorMm) - best <= TIE_MM + EPSILON }.keys
        } else {
            emptySet()
        }
        return DayComparison(
            commonHours = common.size,
            actualMm = totals.values.first().actualMm,
            predMm = predBySource.mapValues { (source, _) -> totals[source]?.predMm },
            closest = closest,
        )
    }

    /** A source's grade over its days (each paired on its own hours). */
    fun score(source: WeatherSource, days: List<Paired>): RainSourceScore {
        val counted = days.filter { it.counted }
        val rain = counted.filter { it.isRainDay }
        return RainSourceScore(
            source = source,
            countedDays = counted.size,
            rainDays = rain.size,
            meanAbsErrorMm = rain.takeIf { it.isNotEmpty() }?.map { abs(it.errorMm) }?.average()?.toFloat(),
            biasMm = rain.takeIf { it.isNotEmpty() }?.map { it.errorMm }?.average()?.toFloat(),
            hitRate = counted.takeIf { it.isNotEmpty() }?.let { c -> c.count { it.hit }.toFloat() / c.size },
        )
    }

    /** Ranked sources first, smallest rain-day error first; the rest after, in their given order. */
    fun rank(scores: List<RainSourceScore>): List<RainSourceScore> =
        scores.filter { it.ranked }.sortedBy { it.meanAbsErrorMm } + scores.filterNot { it.ranked }
}
