package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.TempUtils
import com.weatherwidget.shared.util.TodayLowSettle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Source-agnostic view-state logic for the forecast-history screen, shared between Android
 * (`ForecastHistoryActivity`) and desktop (`ForecastHistoryWindow`). Pure functions only — no DB,
 * no platform types — so both UIs make the same decisions about which graph mode and button label
 * to show and how to look up actuals.
 */
object ForecastHistoryViewLogic {
    const val MAX_HISTORY_DAYS_BACK = 395L // 13 months

    enum class GraphMode { EVOLUTION, ERROR }

    enum class ButtonMode { EVOLUTION, ERROR, TEMPERATURE }

    enum class ActualLookupMode { NONE, SOURCE_SPECIFIC, ANY_SOURCE }

    /** Whether the mode button should launch hourly temperature view rather than toggle graph mode. */
    fun shouldLaunchTemperature(hasDate: Boolean, showTemperatureButton: Boolean): Boolean =
        hasDate && showTemperatureButton

    /** Show the hourly button when viewing today/future without actuals. */
    fun shouldShowTemperatureButton(
        date: LocalDate?,
        hasActualValues: Boolean,
        today: LocalDate = LocalDate.now(),
    ): Boolean = date != null && !date.isBefore(today) && !hasActualValues

    fun resolveButtonMode(showTemperatureButton: Boolean, graphMode: GraphMode): ButtonMode =
        if (showTemperatureButton) ButtonMode.TEMPERATURE
        else if (graphMode == GraphMode.EVOLUTION) ButtonMode.EVOLUTION
        else ButtonMode.ERROR

    fun resolveActualLookupMode(
        date: LocalDate,
        requestedSource: WeatherSource?,
        today: LocalDate = LocalDate.now(),
    ): ActualLookupMode =
        if (!date.isBefore(today)) {
            ActualLookupMode.NONE
        } else if (requestedSource != null) {
            ActualLookupMode.SOURCE_SPECIFIC
        } else {
            ActualLookupMode.ANY_SOURCE
        }

    /**
     * The "Location actual" lines (daily_history computed blend) for one day, and the extreme times
     * [ForecastEvolutionCutoff.points] uses to drop hindcasts.
     */
    data class LocationActuals(
        val high: Float?,
        val low: Float?,
        val highReachedAt: Long?,
        val lowReachedAt: Long?,
    ) {
        /** Show the "Location actual" legend entry: something is drawn. */
        val hasAny: Boolean get() = high != null || low != null

        companion object {
            val NONE = LocationActuals(null, null, null, null)
        }
    }

    /** Whether [locationActuals] needs today's hourly forecast (for the low's settle look-ahead). */
    fun needsTodayHourly(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean = date == today

    /**
     * Past day: both extremes, final. Today: the low only, once [TodayLowSettle] settles it — the
     * same rule and inputs as the widget's today column, so the line appears when the column stops
     * printing the forecast (user, 2026-10-13); the high is still running. A settled low's time is
     * final, so it also drops later low hindcasts, as on a past day. Future: nothing.
     *
     * [todayHourly] is the source's (plus `GENERIC_GAP`) hourly forecast for today; empty otherwise.
     */
    fun <T> locationActuals(
        date: LocalDate,
        now: LocalDateTime,
        zone: ZoneId,
        lat: Double?,
        lon: Double?,
        computedHigh: Float?,
        computedLow: Float?,
        computedHighAt: Long?,
        computedLowAt: Long?,
        todayHourly: List<T>,
        epochMs: (T) -> Long,
        temperature: (T) -> Float,
    ): LocationActuals {
        val today = now.toLocalDate()
        return when {
            date.isBefore(today) -> LocationActuals(computedHigh, computedLow, computedHighAt, computedLowAt)
            date == today && computedLow != null &&
                TodayLowSettle.decide(now, lat, lon, zone, computedLow, todayHourly, epochMs, temperature).settled ->
                LocationActuals(high = null, low = computedLow, highReachedAt = null, lowReachedAt = computedLowAt)
            else -> LocationActuals.NONE
        }
    }

    fun normalizeSource(rawSource: String?): WeatherSource? =
        WeatherSource.fromDisplaySourceOrNull(rawSource)

    /**
     * Format a directional bias for the accuracy summary (warmer actual → forecast ran "low").
     *
     * @param formatSuffix called with the formatted bias value (e.g. "1.2°") and whether the
     *   forecast ran low; returns the full suffix string. Defaults to English " (1.2° low)".
     *   Android callers pass a lambda that resolves `R.string.bias_low_suffix` /
     *   `R.string.bias_high_suffix` for localization.
     */
    fun formatBias(
        bias: Double,
        useCelsius: Boolean,
        formatSuffix: (biasValue: String, isLow: Boolean) -> String =
            { v, low -> " ($v ${if (low) "low" else "high"})" },
    ): String {
        val displayBias = TempUtils.displayDelta(bias, useCelsius)
        val absBias = kotlin.math.abs(displayBias)
        val threshold = TempUtils.displayDelta(0.5, useCelsius)
        if (absBias < threshold) return ""
        val biasValue = "%.1f°".format(absBias)
        return formatSuffix(biasValue, displayBias > 0)
    }
}
