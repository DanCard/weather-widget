package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource

/**
 * Re-files the past slice of a source's hourly list as observation rows that drive the
 * "actuals" (observed temperature) line for forecast-only sources — APIs like Open-Meteo
 * that have no station-observation product of their own. NWS supplies real station readings
 * and does NOT need this; this is the fallback that lets every other source still render an
 * actual line.
 *
 * Shared by Android ([ForecastRepository.saveHistoricalActuals]) and the desktop service so
 * the mapping (and its precip provenance gate) cannot drift between platforms.
 */
object HistoricalActualsBackfill {

    /**
     * The synthetic `stationId` stamped on every backfill observation row for [sourceId] (e.g.
     * `"NWS_MAIN"`, `"OPEN_METEO_MAIN"`). These rows are not real station observations — they exist
     * only to drive the actual line — so the observations/stations UI uses this to recognise and
     * filter them (see `ObservationSourceMatcher`).
     */
    fun syntheticStationId(sourceId: String): String = "${sourceId}_MAIN"

    /**
     * @param hourly the source's hourly list, typically fetched with a `past_days` window so it
     *   spans both history and forecast. Only entries at or before [nowMs] are kept.
     * @param sourceId the [WeatherSource] id these hours belong to; becomes the observation `api`.
     *
     * Temperature is always carried over. Precipitation is kept only for sources whose past hours
     * are genuine actuals ([WeatherSource.providesHistoricalActuals] — a real history/reanalysis
     * product); otherwise it is nulled so a source's own past forecast is not presented as a
     * measured amount.
     */
    fun build(
        hourly: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        nowMs: Long,
        fetchedAt: Long = nowMs,
    ): List<ObservationReading> {
        val keepMeasuredPrecip = WeatherSource.fromId(sourceId).providesHistoricalActuals
        return hourly
            .filter { it.dateTime <= nowMs }
            .map { hour ->
                ObservationReading(
                    stationId = syntheticStationId(sourceId),
                    stationName = "$sourceId: History Backfill",
                    timestamp = hour.dateTime,
                    temperature = hour.temperature,
                    condition = hour.condition,
                    locationLat = latitude,
                    locationLon = longitude,
                    distanceKm = 0f,
                    stationType = "OFFICIAL",
                    api = sourceId,
                    fetchedAt = fetchedAt,
                    precipAmountMm = if (keepMeasuredPrecip) hour.precipAmountMm else null,
                )
            }
    }
}
