package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.WeatherSource
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

    /** Format a directional bias for the accuracy summary (warmer actual → forecast ran "low"). */
    fun formatBias(bias: Double, useCelsius: Boolean = false): String {
        val displayBias = if (useCelsius) bias / 1.8 else bias
        val absBias = kotlin.math.abs(displayBias)
        val threshold = if (useCelsius) 0.5 / 1.8 else 0.5
        return when {
            absBias < threshold -> ""
            displayBias > 0 -> " (${String.format("%.1f", absBias)}° low)"
            else -> " (${String.format("%.1f", absBias)}° high)"
        }
    }
}
