package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.AviationWeatherStationFilter

/**
 * `aviationweather.gov` METAR row → [ObservationReading], the one shape the observation pipeline
 * consumes on both platforms.
 *
 * Parallel to [NwsObservationMapper]. Standalone rows retain `api=METAR`; the NWS current
 * fetch-both path may additionally create an `api=NWS` web-origin presentation copy when the same
 * station's Aviation Weather METAR is strictly newer. NWS hands over a decoded payload whose temperature already
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
        val temperatureF = celsiusToFahrenheit(tempC)

        val decoded = row.rawOb?.let(MetarDecoder::decode)

        // The payload's decoded `clouds` array wins; the raw report only fills a gap. An EMPTY
        // array means "not reported" and must stay empty so MetarSkyCover maps it to null — the
        // invariant NwsObservationMapperCloudTest pins on the NWS side.
        val layers = row.cloudLayers.ifEmpty { decoded?.skyLayers ?: emptyList() }
        val cloudProfile = MetarSkyCover.verticalProfile(layers)

        return ObservationReading(
            stationId = row.stationId,
            stationName = row.stationName.ifBlank { station.info.name },
            timestamp = row.observedAtMillis,
            temperature = temperatureF,
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
            // Standalone provenance: the optional NWS presentation copy is created by its caller.
            isWebFallback = false,
            // Every row from this feed is a real report — the endpoint serves METARs and SPECIs and
            // nothing else. There is no ASOS 5-minute interleave here, which is the ONLY thing
            // `isMetar` exists to separate out on the NWS path. MetarCloudBlender prefers these
            // rows for cloud, so leaving the default `false` would quietly deny them that.
            isMetar = true,
            // The feed carries a `qcField` but its scale is undocumented; marking rows on a guess
            // would silently drop them from the blend, so it stays unread until the encoding is
            // confirmed. What we CAN judge is the report against itself: MetarPlausibility rejects
            // only physically or structurally impossible readings (dewpoint above temperature, a
            // malformed T/Td group). Without it a garbled report arrives here unflagged and enters
            // the blend at full weight — which is exactly how KPAO's 50 F at 16:47 on 2026-08-31
            // pulled the actual line ~5 F below every real neighbour.
            qcFailed = MetarPlausibility.check(temperatureF, row.rawOb).failed,
            // METAR amounts are cumulative sky-cover layers. File them by rounded base altitude
            // (low <3 km, middle <8 km, high otherwise); total remains null because the report does
            // not independently measure the whole atmospheric column.
            cloudCover = null,
            cloudCoverLow = cloudProfile?.low?.coverPercent,
            rawMetar = row.rawOb,
            cloudCoverMid = cloudProfile?.mid?.coverPercent,
            cloudCoverHigh = cloudProfile?.high?.coverPercent,
            cloudBaseLowMeters = cloudProfile?.low?.baseMeters,
            cloudBaseMidMeters = cloudProfile?.mid?.baseMeters,
            cloudBaseHighMeters = cloudProfile?.high?.baseMeters,
            cloudVerticalKind = if (cloudProfile?.let { it.low != null || it.mid != null || it.high != null } == true) {
                CloudVerticalKind.CUMULATIVE_LAYERS
            } else {
                CloudVerticalKind.NONE
            },
        )
    }

    private fun celsiusToFahrenheit(celsius: Float): Float = (celsius * 1.8f) + 32f
}
