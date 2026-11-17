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
 * 72%. So the combined value is shown only when the window's max already reaches the source's
 * cutoff ([MIN_MAX_PERCENT]; user, 2026-11-17: option 2 at 85%), just under that source's ceiling.
 * WeatherAPI joined the same day at 90%: its hours stop at ~92% (11-18: 84–90% from 7am to 2pm,
 * "Moderate rain", label 90%). Tomorrow.io joined at 85% (user, 2026-11-17; 11-18: 8am 85% at 5.1 mm,
 * 9am 80% at 13 mm, label 85%), though it reaches 90–99% on rare hours. Every other source shows the
 * window max.
 * plans/261117-google-combined-daily-rain-chance.md
 */
object CombinedRainChance {
    /** Per source: the window's max hourly chance from which the combined chance is shown instead. */
    val MIN_MAX_PERCENT: Map<String, Int> = mapOf(
        WeatherSource.GOOGLE_WEATHER.id to 85,
        WeatherSource.WEATHER_API.id to 90,
        WeatherSource.TOMORROW_IO.id to 85,
    )

    /** True when [sourceId]'s daily windows show the combined chance (subject to its cutoff). */
    fun appliesTo(sourceId: String?): Boolean = sourceId in MIN_MAX_PERCENT

    /** `1 − ∏(1 − p/100)` as a whole percent; null for no hours. */
    fun combine(probabilities: List<Int>): Int? {
        if (probabilities.isEmpty()) return null
        val allDry = probabilities.fold(1.0) { acc, p -> acc * (1.0 - p.coerceIn(0, 100) / 100.0) }
        return ((1.0 - allDry) * 100.0).roundToInt().coerceIn(0, 100)
    }

    /**
     * The window's chance to show: [combined] for a source in [MIN_MAX_PERCENT] whose [max] is at
     * least that source's cutoff, else [max]. A missing [combined] (a row written before the column existed)
     * keeps the max, so nothing goes blank.
     */
    fun shown(sourceId: String?, max: Int?, combined: Int?): Int? {
        val cutoff = MIN_MAX_PERCENT[sourceId] ?: return max
        if (max == null || combined == null || max < cutoff) return max
        return maxOf(max, combined)
    }
}
