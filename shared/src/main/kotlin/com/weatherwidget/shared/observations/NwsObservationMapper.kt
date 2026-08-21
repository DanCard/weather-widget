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
    ): ObservationReading = ObservationReading(
        stationId = station.id,
        stationName = observation.stationName.ifBlank { station.name },
        timestamp = parseTimestamp(observation.timestamp),
        temperature = celsiusToFahrenheit(observation.temperatureCelsius),
        condition = observation.textDescription,
        locationLat = siteLat,
        locationLon = siteLon,
        distanceKm = distanceKm(siteLat, siteLon, station.lat, station.lon).toFloat(),
        stationType = station.type.name,
        api = "NWS",
        precipAmountMm = observation.precipLastHourMm,
        maxTempLast24h = observation.maxTempLast24hCelsius?.let { celsiusToFahrenheit(it) },
        minTempLast24h = observation.minTempLast24hCelsius?.let { celsiusToFahrenheit(it) },
        isWebFallback = isWebFallback,
        qcFailed = observation.qcFailed,
        // METAR sky condition is a below-~12,000 ft measurement, so it is filed as the LOW layer
        // and the total column stays null — same rule on both platforms (§3 of the METAR plan).
        cloudCover = null,
        cloudCoverLow = MetarSkyCover.lowPercent(observation.cloudLayers),
    )

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
