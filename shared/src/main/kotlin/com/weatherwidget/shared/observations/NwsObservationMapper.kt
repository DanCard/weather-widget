package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "NwsObservationMapper"

/**
 * The one NWS-observation mapping, shared by Android (`NwsObservationSource.toEntity`) and the
 * desktop service (`toReading`) so the two cannot drift on unit conversion, the blank-name
 * fallback, or the METAR cloud rule.
 *
 * Platform-specific concerns stay out: the fetch-site coordinate keying (Android quantizes to the
 * shared write grid; the desktop DAO does it on write) and the entity conversion both wrap the
 * [ObservationReading] this returns.
 */
object NwsObservationMapper {

    fun toReading(
        observation: NwsApi.Observation,
        station: NwsApi.StationInfo,
        siteLat: Double,
        siteLon: Double,
        isWebFallback: Boolean = false,
    ): ObservationReading {
        val decodedMetar = if (observation.isMetar) MetarDecoder.decode(observation.rawMessage) else null
        val remarks = decodedMetar?.remarks

        // T-group tenths precision eliminates ±0.9°F quantization noise from integer-rounded API payloads.
        val tempCelsius = remarks?.preciseTempCelsius ?: observation.temperatureCelsius
        val max24hCelsius = observation.maxTempLast24hCelsius ?: remarks?.max24HourTempCelsius
        val min24hCelsius = observation.minTempLast24hCelsius ?: remarks?.min24HourTempCelsius
        val precipMm = observation.precipLastHourMm ?: remarks?.hourlyPrecipMm

        val layers = observation.cloudLayers.ifEmpty { decodedMetar?.skyLayers ?: emptyList() }

        return ObservationReading(
            stationId = station.id,
            stationName = observation.stationName.ifBlank { station.name },
            timestamp = parseTimestamp(observation.timestamp),
            temperature = celsiusToFahrenheit(tempCelsius),
            condition = observation.textDescription,
            locationLat = siteLat,
            locationLon = siteLon,
            distanceKm = distanceKm(siteLat, siteLon, station.lat, station.lon).toFloat(),
            stationType = station.type.name,
            api = "NWS",
            precipAmountMm = precipMm,
            maxTempLast24h = max24hCelsius?.let { celsiusToFahrenheit(it) },
            minTempLast24h = min24hCelsius?.let { celsiusToFahrenheit(it) },
            isWebFallback = isWebFallback,
            qcFailed = observation.qcFailed,
            // Set by whoever built the Observation: NwsApi from `rawMessage`, SynopticApi from
            // `metar_set_1`. Web-fallback readings ARE often METARs — the earlier claim that they never
            // are was wrong, and it cost the cloud curve its nearest station (2026-08-21).
            isMetar = observation.isMetar,
            // METAR sky condition is a below-~12,000 ft measurement, so it is filed as the LOW layer
            // and the total column stays null — same rule on both platforms (§3 of the METAR plan).
            cloudCover = null,
            cloudCoverLow = MetarSkyCover.lowPercent(layers),
            rawMetar = observation.rawMessage,
        )
    }

    private fun celsiusToFahrenheit(celsius: Float): Float = (celsius * 1.8f) + 32f

    /**
     * Hardened NWS timestamp parse: repairs a `+0000`-style offset to `+00:00`, and falls back to
     * "now" rather than throwing — one malformed timestamp must not abort the whole observation
     * mapping batch it rides in.
     */
    fun parseTimestamp(ts: String): Long {
        return try {
            var cleanStr = ts.trim()
            if (cleanStr.length >= 5) {
                val lastFour = cleanStr.takeLast(4)
                val sign = cleanStr[cleanStr.length - 5]
                if ((sign == '+' || sign == '-') && lastFour.all { it.isDigit() }) {
                    cleanStr = cleanStr.substring(0, cleanStr.length - 2) + ":" + cleanStr.substring(cleanStr.length - 2)
                }
            }
            java.time.ZonedDateTime.parse(cleanStr).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse timestamp '$ts': ${e.message}", e)
            System.currentTimeMillis()
        }
    }

    /** Haversine great-circle distance in km; feeds IDW weights and distance display only. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
