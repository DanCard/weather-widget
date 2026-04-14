package com.weatherwidget.widget.handlers

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.ObservationBlender
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
    ): ObservationResolver.ObservedCurrentTemperature? {
        if (repository == null) return null

        val queryWindow = GraphDataLoader.buildCurrentTempResolutionWindow(now)
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
        queryWindow: GraphDataLoader.CurrentTempResolutionWindow = GraphDataLoader.buildCurrentTempResolutionWindow(now),
    ): ObservationResolver.ObservedCurrentTemperature? {
        val resolved = ObservationBlender.resolveCurrentObservation(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            userLat = lat,
            userLon = lon,
            now = now,
            lookbackHours = 12L,
            lookaheadHours = 2L,
        )

        Log.d(
            TAG,
            "resolveGraphStyleCurrentTemp: source=${displaySource.id} now=$now " +
                "window=${queryWindow.start}..${queryWindow.end} obs=${observations.size} fcst=${hourlyForecasts.size} " +
                "resolvedTemp=${resolved?.first} resolvedAt=${resolved?.second} anchorAt=${resolved?.third}",
        )

        return resolved?.let { (temp, time, anchorTime) ->
            ObservationResolver.ObservedCurrentTemperature(
                temperature = temp,
                observedAt = anchorTime,
                source = displaySource.id,
                rowFetchedAt = System.currentTimeMillis()
            )
        }
    }
}