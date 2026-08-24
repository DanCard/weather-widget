package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.AviationWeatherStationFilter

/**
 * `aviationweather.gov` METAR row → [ObservationReading], the one shape the observation pipeline
 * consumes on both platforms.
 *
 * Parallel to [NwsObservationMapper], and deliberately NOT merged with it: the two feeds disagree on
 * where the authoritative value lives. NWS hands over a decoded payload whose temperature already
 * carries T-group tenths where the station emits them, so that path trusts the payload
 * (`4bc4a298`). This feed's `temp` is likewise pre-decoded, but the raw report travels with every
 * row, so remarks that the payload does not surface can still be recovered later from `rawMetar`.
 */
object MetarObservationMapper {

    /** Provenance for every row this mapper produces. Never `"NWS"` — see the class KDoc of the source. */
    val SOURCE_ID: String = WeatherSource.METAR.id

    /**
     * Null when the row carries no usable temperature.
     *
     * A METAR without a temperature is not an observation for this app's purposes, and storing 0 °C
     * would poison the blend far worse than a missing row does — the same rule
     * [NwsObservationMapper] follows by way of `NwsApi`'s null-temperature guard.
     */
    fun toReading(
        row: AviationWeatherApi.MetarRow,
        station: AviationWeatherStationFilter.RankedStation,
        siteLat: Double,
        siteLon: Double,
    ): ObservationReading? {
        val tempC = row.temperatureCelsius ?: return null

        val decoded = row.rawOb?.let(MetarDecoder::decode)

        // The payload's decoded `clouds` array wins; the raw report only fills a gap. An EMPTY
        // array means "not reported" and must stay empty so MetarSkyCover maps it to null — the
        // invariant NwsObservationMapperCloudTest pins on the NWS side.
        val layers = row.cloudLayers.ifEmpty { decoded?.skyLayers ?: emptyList() }

        return ObservationReading(
            stationId = row.stationId,
            stationName = row.stationName.ifBlank { station.info.name },
            timestamp = row.observedAtMillis,
            temperature = celsiusToFahrenheit(tempC),
            // This feed ships no textDescription. The present-weather codes in the raw report could
            // supply one (brainstorm A4), but that is a separate change with its own UI surface;
            // an empty condition is honest until then.
            condition = "",
            locationLat = siteLat,
            locationLon = siteLon,
            distanceKm = station.distanceKm.toFloat(),
            stationType = station.info.type.name,
            api = SOURCE_ID,
            // No precip in the JSON. Pxxxx is "since the last hourly report", the same window as
            // NWS's precipitationLastHour, so the remarks group is a legitimate source here for the
            // same reason it is there.
            precipAmountMm = decoded?.remarks?.hourlyPrecipMm,
            // Left null on purpose. This feed exposes no rolling 24-hour extreme, and the remarks
            // `4sTTTTsTTTT` group is the LOCAL CALENDAR-DAY extreme for the day that just ended —
            // a different quantity, and filing it here is an off-by-one day. See NwsObservationMapper.
            maxTempLast24h = null,
            minTempLast24h = null,
            // Not a web fallback: this is a first-class transport, not a scrape standing in for one.
            isWebFallback = false,
            // Every row from this feed is a real report — the endpoint serves METARs and SPECIs and
            // nothing else. There is no ASOS 5-minute interleave here, which is the ONLY thing
            // `isMetar` exists to separate out on the NWS path. MetarCloudBlender prefers these
            // rows for cloud, so leaving the default `false` would quietly deny them that.
            isMetar = true,
            // The feed carries a `qcField` but its scale is undocumented; marking rows on a guess
            // would silently drop them from the blend. Left false until the encoding is confirmed.
            qcFailed = false,
            // METAR sky condition is a below-~12,000 ft measurement, so it is filed as the LOW layer
            // and the total column stays null — the same rule both platforms apply to NWS METARs.
            cloudCover = null,
            cloudCoverLow = MetarSkyCover.lowPercent(layers),
            rawMetar = row.rawOb,
        )
    }

    private fun celsiusToFahrenheit(celsius: Float): Float = (celsius * 1.8f) + 32f
}
