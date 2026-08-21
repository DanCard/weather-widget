package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.CloudActualSettling

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
     * Temperature is always carried over. Precipitation and cloud cover are kept only when the
     * source's explicit [WeatherSource.historicalDataKind] permits provider-history values;
     * otherwise they are nulled so an ordinary forecast sliced into the past is not presented as
     * historical weather.
     *
     * [hourly] may be sub-hourly. `observations` is keyed on `(stationId, timestamp)`, so a
     * 15-minute series lands without collision and without the hour-indexed pipeline seeing it.
     */
    fun build(
        hourly: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        nowMs: Long,
        fetchedAt: Long = nowMs,
    ): List<ObservationReading> {
        val keepCloud =
            WeatherSource.fromId(sourceId)
                .historicalDataKind
                .preservesHistoricalPrecipitation
        val keepHistoricalPrecip =
            WeatherSource.fromId(sourceId)
                .historicalDataKind
                .preservesHistoricalPrecipitation
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
                    precipAmountMm = if (keepHistoricalPrecip) hour.precipAmountMm else null,
                    // Cloud carries TWO gates, not one.
                    //
                    // The provenance gate (as precip): an ordinary forecast sliced into the past is
                    // not an observation of the sky, so sources whose past values are never revised
                    // carry null rather than a restated forecast.
                    //
                    // The settling gate: even for a source that does revise, an hour that has merely
                    // ENDED still holds the pre-hour forecast for a while. Temperature does not need
                    // this and must not get it — it would blank the newest two hours of the actual
                    // temperature line for every forecast-only source. See [CloudActualSettling].
                    cloudCover = if (keepCloud && CloudActualSettling.hasSettled(hour.dateTime, nowMs)) {
                        hour.cloudCover
                    } else {
                        null
                    },
                    cloudCoverLow = if (keepCloud && CloudActualSettling.hasSettled(hour.dateTime, nowMs)) {
                        hour.cloudCoverLow
                    } else {
                        null
                    },
                )
            }
    }
}
