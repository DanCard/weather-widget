package com.weatherwidget.widget.handlers

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.widget.ObservationResolver
import java.time.LocalDateTime
import java.time.ZoneId

object CurrentTempResolver {
    private const val TAG = "CurrentTempResolver"

    suspend fun resolveGraphStyleCurrentTemp(
        repository: WeatherRepository?,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        now: LocalDateTime,
        personalStationWeight: Double = 1.0,
    ): ObservationResolver.ObservedCurrentTemperature? {
        if (repository == null) return null

        val queryWindow = com.weatherwidget.widget.CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)
        val zoneId = ZoneId.systemDefault()
        val minEpoch = queryWindow.start.atZone(zoneId).toInstant().toEpochMilli()
        val maxEpoch = queryWindow.end.atZone(zoneId).toInstant().toEpochMilli()
        val observations = repository.getObservationsInRange(minEpoch, maxEpoch, lat, lon)

        return resolveGraphStyleCurrentTempFromInputs(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            lat = lat,
            lon = lon,
            now = now,
            queryWindow = queryWindow,
            personalStationWeight = personalStationWeight,
        )
    }

    @VisibleForTesting
    internal fun resolveGraphStyleCurrentTempFromInputs(
        observations: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        lat: Double,
        lon: Double,
        now: LocalDateTime,
        queryWindow: com.weatherwidget.widget.CurrentTemperatureResolver.CurrentTempResolutionWindow = com.weatherwidget.widget.CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now),
        personalStationWeight: Double = 1.0,
    ): ObservationResolver.ObservedCurrentTemperature? {
        val resolved = ActualsAggregator.resolveCurrentObservation(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            displaySourceId = displaySource.id,
            userLat = lat,
            userLon = lon,
            nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            // Single-sourced: the today-column overlay re-derives this same observation and must use
            // the identical window (see CurrentTemperatureResolver.RESOLUTION_LOOKAHEAD_HOURS).
            lookbackHours = com.weatherwidget.widget.CurrentTemperatureResolver.RESOLUTION_LOOKBACK_HOURS,
            lookaheadHours = com.weatherwidget.widget.CurrentTemperatureResolver.RESOLUTION_LOOKAHEAD_HOURS,
            personalStationWeight = personalStationWeight,
        )

        Log.d(
            TAG,
            "resolveGraphStyleCurrentTemp: source=${displaySource.id} now=$now " +
                "window=${queryWindow.start}..${queryWindow.end} obs=${observations.size} fcst=${hourlyForecasts.size} " +
                "resolvedTemp=${resolved?.first} resolvedAt=${resolved?.second} anchorAt=${resolved?.third}",
        )

        return resolved?.let { (temp, timestamp, fetchedAt) ->
            ObservationResolver.ObservedCurrentTemperature(
                temperature = temp,
                observedAt = timestamp,
                source = displaySource.id,
                rowFetchedAt = fetchedAt
            )
        }
    }
}
