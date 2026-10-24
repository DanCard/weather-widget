package com.weatherwidget.widget.handlers

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.CurrentTempRun
import com.weatherwidget.widget.ObservationResolver
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Android's Host for the observation half of `:shared` [CurrentTempRun]: it reads the rows, the run
 * blends them at now and takes the "from yesterday" delta. Views then resolve their header with
 * [CurrentTempRun.header] (plans/261024-shared-current-temp-run.md).
 */
object CurrentTempResolver {
    private const val TAG = "CurrentTempResolver"

    /**
     * [hourlyForecasts] should cover [CurrentTempRun.inputWindow] (the interaction paths load it with
     * [GraphDataLoader.loadCurrentTempInputHourlyForecasts]); a now-centred list alone leaves the
     * yesterday delta null.
     */
    suspend fun observe(
        repository: WeatherRepository?,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        now: LocalDateTime,
        personalStationWeight: Double = 1.0,
    ): ObservationResolver.ObservedCurrentTemperature? {
        if (repository == null) return null

        val inputWindow = CurrentTempRun.inputWindow(now)
        // Scoped identically to the daily header load (ActualsReadScope). This resolver produces the
        // `observedAt` that the today-column overlay re-derives and matches on EXACT equality, so if
        // these two reads ever admit different rows the station rows drop out of the overlay.
        val observations =
            repository.getObservationsInRange(
                inputWindow.first,
                inputWindow.last,
                lat,
                lon,
                ActualsReadScope.apisFor(displaySource),
            )

        return observeFromRows(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            lat = lat,
            lon = lon,
            now = now,
            personalStationWeight = personalStationWeight,
        )
    }

    /** [CurrentTempRun.observe] on rows already read; null when no reading blends at now. */
    @VisibleForTesting
    internal fun observeFromRows(
        observations: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        lat: Double,
        lon: Double,
        now: LocalDateTime,
        personalStationWeight: Double = 1.0,
    ): ObservationResolver.ObservedCurrentTemperature? {
        val observed = CurrentTempRun.observe(
            hourly = hourlyForecasts.map { it.toHourlyForecast() },
            observations = observations.map { it.toReading() },
            displaySource = displaySource,
            latitude = lat,
            longitude = lon,
            nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            personalStationWeight = personalStationWeight,
        )

        Log.d(
            TAG,
            "observe: source=${displaySource.id} now=$now obs=${observations.size} fcst=${hourlyForecasts.size} " +
                "windowObs=${observed.windowObservations} windowFcst=${observed.windowHourly} " +
                "resolvedTemp=${observed.temperature} resolvedAt=${observed.observedAt} " +
                "anchorAt=${observed.rowFetchedAt} fromYesterday=${observed.deltaFromYesterday}",
        )

        val temperature = observed.temperature ?: return null
        val observedAt = observed.observedAt ?: return null
        return ObservationResolver.ObservedCurrentTemperature(
            temperature = temperature,
            observedAt = observedAt,
            source = displaySource.id,
            rowFetchedAt = observed.rowFetchedAt ?: 0L,
            deltaFromYesterday = observed.deltaFromYesterday,
        )
    }
}
