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
    ): Pair<Float, Float>? {
        val candidates = forecasts.filter {
            it.source == displaySource.id && !it.isClimateNormal &&
                (actual == null || LocationMatch.sameSite(it.locationLat, it.locationLon, actual.locationLat, actual.locationLon))
        }
        val resolved = PriorDayForecast.resolvePast(
            actual?.priorForecastHighTemp, actual?.priorForecastLowTemp, candidates, date, zone,
            fetchedAt = { it.fetchedAt }, high = { it.highTemp }, low = { it.lowTemp },
        )
        if (resolved != null && (actual?.priorForecastHighTemp == null || actual.priorForecastLowTemp == null)) {
            Log.v(TAG, "resolvePriorForecast: past day $date left bar picked live (not frozen yet) $resolved")
        }
        return resolved
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
        // Shared with desktop and the text-only path: a real range wins over a newer collapsed row.
        val pastForecast = PastDayForecastOverlay.pick(
            forecasts.filter { it.source == displaySource.id && !it.isClimateNormal },
            { it.highTemp },
            { it.lowTemp },
            { it.fetchedAt },
        )
        if (pastForecast == null) {
            Log.d(TAG, "resolvePastDayOverlay: past day $date has no usable forecast snapshot from ${displaySource.id}; skipping forecast overlay")
        }
        return Pair(pastForecast?.highTemp, pastForecast?.lowTemp)
    }
}
