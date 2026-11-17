package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import kotlin.math.roundToInt

/**
 * The daily view's rain % for a window (8am–8pm day, 8pm–8am night) from a source whose hourly
 * chances top out below certainty: the chance of rain in **at least one hour**, treating hours as
 * independent — `1 − ∏(1 − pₕ)`.
 *
 * Google's hourly chances are 5%-stepped and stop at 85% a day ahead: on 2026-11-18 four hours at
 * 85% carried 5.8–12.6 mm/h ("Heavy Rain") while the label read 85% and every other source said
 * 89–100%. Independence is the upper bound (hours of one system are correlated; the max is the lower
 * bound), and over Google's 10% drizzle floor it overstates dry days — twelve 10% hours combine to
 * 72%. So the combined value is shown only when the window's max already reaches [MIN_MAX_PERCENT]
 * (user, 2026-11-17: option 2 at 85%). Google only; every other source shows the window max.
 * plans/261117-google-combined-daily-rain-chance.md
 */
object CombinedRainChance {
    /** A window's max hourly chance from which the combined chance is shown instead. */
    const val MIN_MAX_PERCENT = 85

    private val SOURCES = setOf(WeatherSource.GOOGLE_WEATHER.id)

    /** True when [sourceId]'s daily windows show the combined chance (subject to [MIN_MAX_PERCENT]). */
    fun appliesTo(sourceId: String?): Boolean = sourceId in SOURCES

    /** `1 − ∏(1 − p/100)` as a whole percent; null for no hours. */
    fun combine(probabilities: List<Int>): Int? {
        if (probabilities.isEmpty()) return null
        val allDry = probabilities.fold(1.0) { acc, p -> acc * (1.0 - p.coerceIn(0, 100) / 100.0) }
        return ((1.0 - allDry) * 100.0).roundToInt().coerceIn(0, 100)
    }

    /**
     * The window's chance to show: [combined] for a source in [SOURCES] whose [max] is at least
     * [MIN_MAX_PERCENT], else [max]. A missing [combined] (a row written before the column existed)
     * keeps the max, so nothing goes blank.
     */
    fun shown(sourceId: String?, max: Int?, combined: Int?): Int? {
        if (max == null || combined == null || !appliesTo(sourceId) || max < MIN_MAX_PERCENT) return max
        return maxOf(max, combined)
    }
}
