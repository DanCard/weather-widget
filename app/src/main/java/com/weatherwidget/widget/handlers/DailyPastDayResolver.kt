package com.weatherwidget.widget.handlers

import android.util.Log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyDayValueResolver
import java.time.LocalDate

internal object DailyPastDayResolver {
    private const val TAG = "DailyPastDayResolver"

    data class PastDayValues(
        val finalHigh: Float?,
        val finalLow: Float?,
        val fHigh: Float?,
        val fLow: Float?,
        val solidIsForecastFallback: Boolean,
    )

    fun resolvePastDayValues(
        actual: DailyHistory?,
        forecasts: List<ForecastEntity>,
        displaySource: WeatherSource,
        date: LocalDate,
        showComparison: Boolean = true,
    ): PastDayValues {
        var fHigh: Float? = null
        var fLow: Float? = null

        if (showComparison) {
            val (overlayHigh, overlayLow) = resolvePastDayOverlay(actual, forecasts, displaySource, date)
            fHigh = overlayHigh
            fLow = overlayLow
        }

        // A past day may have no daily_history actual row (forecast-only sources like
        // Open-Meteo, or sources whose actuals tracking started recently, like
        // Tomorrow.io). Fall back to the forecast values so the column still labels its
        // high/low (see DailyDayValueResolver.resolvePastLineValues).
        val pastValues = DailyDayValueResolver.resolvePastLineValues(
            actualHigh = actual?.computedHighTemp,
            actualLow = actual?.computedLowTemp,
            forecastHigh = fHigh,
            forecastLow = fLow,
        )

        return PastDayValues(
            finalHigh = pastValues.solidHigh,
            finalLow = pastValues.solidLow,
            fHigh = pastValues.forecastHigh,
            fLow = pastValues.forecastLow,
            solidIsForecastFallback = pastValues.solidIsForecastFallback,
        )
    }

    fun resolvePastDayOverlay(
        actual: DailyHistory?,
        forecasts: List<ForecastEntity>,
        displaySource: WeatherSource,
        date: LocalDate,
    ): Pair<Float?, Float?> {
        val frozenHigh = actual?.forecastHighTemp
        val frozenLow = actual?.forecastLowTemp
        if (frozenHigh != null && frozenLow != null) {
            Log.v(TAG, "resolvePastDayOverlay: past day $date overlay from frozen daily_history high=$frozenHigh low=$frozenLow")
            return Pair(frozenHigh, frozenLow)
        }
        val pastForecast = forecasts
            .filter { it.source == displaySource.id && !it.isClimateNormal && it.highTemp != null && it.lowTemp != null }
            .maxByOrNull { it.fetchedAt }
        if (pastForecast == null) {
            Log.d(TAG, "resolvePastDayOverlay: past day $date has no usable forecast snapshot from ${displaySource.id}; skipping forecast overlay")
        }
        return Pair(pastForecast?.highTemp, pastForecast?.lowTemp)
    }
}
