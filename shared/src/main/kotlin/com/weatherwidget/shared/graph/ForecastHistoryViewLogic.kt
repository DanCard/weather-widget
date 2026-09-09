package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.TempUtils
import java.time.LocalDate

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
