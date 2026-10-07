package com.weatherwidget.widget.handlers

import android.util.Log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyDayValueResolver
import com.weatherwidget.shared.util.PastDayForecastOverlay
import com.weatherwidget.shared.util.PriorDayForecast
import java.time.LocalDate
import java.time.ZoneId

internal object DailyPastDayResolver {
    private const val TAG = "DailyPastDayResolver"

    data class PastDayValues(
        val finalHigh: Float?,
        val finalLow: Float?,
        val fHigh: Float?,
        val fLow: Float?,
        val solidIsForecastFallback: Boolean,
        /** The right bar has a fallback side (post-cutoff or hourly value) — drawn dashed. */
        val forecastIsFallback: Boolean = false,
    )

    fun resolvePastDayValues(
        actual: DailyHistory?,
        forecasts: List<ForecastEntity>,
        displaySource: WeatherSource,
        date: LocalDate,
        showComparison: Boolean = true,
        hourlyTemps: List<Float> = emptyList(),
    ): PastDayValues {
        var fHigh: Float? = null
        var fLow: Float? = null
        var forecastIsFallback = false

        if (showComparison) {
            resolvePastDayOverlay(actual, forecasts, displaySource, date, hourlyTemps)?.let {
                fHigh = it.high
                fLow = it.low
                forecastIsFallback = it.isFallback
            }
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
            forecastIsFallback = forecastIsFallback,
        )
    }

    /**
     * The past day's "yesterday's forecast" pair, shared rule [PriorDayForecast.resolvePast]: frozen
     * columns first, else a live pick from [forecasts] (display source, the frozen row's site).
     */
    fun resolvePriorForecast(
        actual: DailyHistory?,
        forecasts: List<ForecastEntity>,
        displaySource: WeatherSource,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PriorDayForecast.Past? {
        val candidates = forecasts.filter {
            it.source == displaySource.id && !it.isClimateNormal &&
                (actual == null || LocationMatch.sameSite(it.locationLat, it.locationLon, actual.locationLat, actual.locationLon))
        }
        val resolved = PriorDayForecast.resolvePast(
            actual?.priorForecastHighTemp, actual?.priorForecastLowTemp, candidates, date, zone,
            fetchedAt = { it.fetchedAt }, high = { it.highTemp }, low = { it.lowTemp },
            hindcastHigh = { it.hindcastHighTemp }, hindcastLow = { it.hindcastLowTemp },
        )
        if (resolved != null && (actual?.priorForecastHighTemp == null || actual.priorForecastLowTemp == null)) {
            Log.v(TAG, "resolvePriorForecast: past day $date left bar picked live (not frozen yet) $resolved")
        }
        return resolved
    }

    /** The right bar, shared rule [PastDayForecastOverlay.resolve] (display source only). */
    fun resolvePastDayOverlay(
        actual: DailyHistory?,
        forecasts: List<ForecastEntity>,
        displaySource: WeatherSource,
        date: LocalDate,
        hourlyTemps: List<Float> = emptyList(),
    ): PastDayForecastOverlay.Resolved? {
        val resolved = PastDayForecastOverlay.resolve(
            frozenHigh = actual?.forecastHighTemp,
            frozenLow = actual?.forecastLowTemp,
            candidates = forecasts.filter { it.source == displaySource.id && !it.isClimateNormal },
            high = { it.highTemp },
            low = { it.lowTemp },
            fetchedAt = { it.fetchedAt },
            hindcastHigh = { it.hindcastHighTemp },
            hindcastLow = { it.hindcastLowTemp },
            hourlyTemps = hourlyTemps,
        )
        when {
            resolved == null ->
                Log.d(TAG, "resolvePastDayOverlay: past day $date has no usable forecast from ${displaySource.id}; skipping forecast overlay")
            resolved.isFallback ->
                Log.d(TAG, "resolvePastDayOverlay: past day $date overlay is a fallback (dashed) $resolved")
        }
        return resolved
    }
}
