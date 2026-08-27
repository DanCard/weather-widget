package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource

/**
 * Re-files the past slice of a source's timestamped history as observation rows that drive the
 * "actuals" (observed temperature) line for sources with an approved historical product.
 * NWS supplies real station readings and does NOT need this. Forecast-only sources such as
 * Open-Meteo must return no rows here.
 *
 * Shared by Android ([ForecastRepository.saveHistoricalActuals]) and the desktop service so
 * the mapping (and its precip provenance gate) cannot drift between platforms.
 */
object HistoricalActualsBackfill {

    /**
     * The synthetic `stationId` stamped on every backfill observation row for [sourceId] (e.g.
     * `"NWS_MAIN"`, `"WEATHER_API_MAIN"`). These rows are not real station observations — they exist
     * only to drive the actual line — so the observations/stations UI uses this to recognise and
     * filter them (see `ObservationSourceMatcher`).
     */
    fun syntheticStationId(sourceId: String): String = "${sourceId}_MAIN"

    /**
     * @param hourly the source's history list, typically fetched with a `past_days` window so it
     *   spans both history and forecast. Only entries at or before [nowMs] are kept.
     * @param sourceId the [WeatherSource] id these hours belong to; becomes the observation `api`.
     *
     * Temperature is carried over only when [WeatherSource.supportsTemperatureActuals] permits it.
     * Precipitation and cloud cover are then kept only when the source's explicit provenance
     * permits those fields. A forecast-only source returns no observation rows at all.
     *
     * [hourly] need not be hourly. `observations` is keyed on `(stationId, timestamp)`, so Open-Meteo
     * 15-minute temperature and cloud rows land without collision and without being forced through
     * the hour-indexed forecast-history pipeline.
     */
    fun build(
        hourly: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        nowMs: Long,
        fetchedAt: Long = nowMs,
    ): List<ObservationReading> {
        val source = WeatherSource.fromId(sourceId)
        if (!source.supportsTemperatureActuals || !source.supportsHistoricalActualsBackfill) {
            return emptyList()
        }
        val kind = source.historicalDataKind
        val keepHistoricalPrecip = kind.preservesHistoricalPrecipitation
        val keepCloud = source.supportsCloudActuals
        val stationId =
            if (source == WeatherSource.TOMORROW_IO) {
                TomorrowIoActuals.RECENT_HISTORY_STATION_ID
            } else {
                syntheticStationId(sourceId)
            }
        val stationName =
            if (source == WeatherSource.TOMORROW_IO) {
                TomorrowIoActuals.RECENT_HISTORY_STATION_NAME
            } else {
                "$sourceId: History Backfill"
            }
        return hourly
            .filter { it.dateTime <= nowMs }
            .map { hour ->
                ObservationReading(
                    stationId = stationId,
                    stationName = stationName,
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
                    // Provenance is the only cloud gate. A timestamped provider-history value is
                    // usable immediately; waiting for the enclosing hour to end made an
                    // instantaneous 12:15 sample disappear until 13:00. Sources whose past values
                    // are ordinary forecasts still carry null via supportsCloudActuals.
                    cloudCover = if (keepCloud) hour.cloudCover else null,
                    cloudCoverLow = if (keepCloud) hour.cloudCoverLow else null,
                    cloudCoverMid = if (keepCloud) hour.cloudCoverMid else null,
                    cloudCoverHigh = if (keepCloud) hour.cloudCoverHigh else null,
                    cloudEnvelopeBaseMeters = if (keepCloud) hour.cloudEnvelopeBaseMeters else null,
                    cloudEnvelopeTopMeters = if (keepCloud) hour.cloudEnvelopeTopMeters else null,
                    cloudVerticalKind = when {
                        !keepCloud -> CloudVerticalKind.NONE
                        hour.cloudCoverLow != null || hour.cloudCoverMid != null || hour.cloudCoverHigh != null ->
                            CloudVerticalKind.PROVIDER_BANDS
                        hour.cloudEnvelopeBaseMeters != null || hour.cloudEnvelopeTopMeters != null ->
                            CloudVerticalKind.TOTAL_ENVELOPE
                        else -> CloudVerticalKind.NONE
                    },
                )
            }
    }
}
