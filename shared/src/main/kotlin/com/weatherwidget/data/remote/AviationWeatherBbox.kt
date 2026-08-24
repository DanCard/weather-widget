package com.weatherwidget.data.remote

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the `bbox` query value for `aviationweather.gov/api/data`, which is the app's only
 * station-discovery path outside the United States.
 *
 * `api.weather.gov` discovery routes through `/points` → `observationStationsUrl`
 * (`NwsObservationSource:98`), which fails anywhere outside NWS coverage — a user in Paris gets an
 * empty station list and therefore no observations at all. A latitude/longitude box works
 * everywhere, so the same code discovers Bay Area and Île-de-France stations.
 *
 * The API expects `bbox=lat0,lon0,lat1,lon1` with the south-west corner first.
 */
object AviationWeatherBbox {

    /**
     * Half-height of the smallest box, in degrees of latitude — roughly 39 km, which reaches
     * several airports in any built-up area. [expand] widens it when that is not enough.
     */
    const val BASE_HALF_DEGREES = 0.35

    /** Roughly 150 km. Past this the stations are too far to inform a local blend. */
    const val MAX_HALF_DEGREES = 1.35

    /** Latitude beyond which longitude scaling is clamped; see [halfWidthDegrees]. */
    private const val MAX_SCALING_LATITUDE = 85.0

    /**
     * Longitude half-width for a box that should be as WIDE in kilometres as it is TALL.
     *
     * A degree of longitude shrinks with latitude, so a box that is 0.35° in both axes is square at
     * the equator and half as wide as it is tall at 60°. Without the `cos` correction, discovery at
     * high latitude silently searches a much narrower strip of ground than intended and can miss
     * stations that are well inside the intended radius.
     *
     * Clamped at [MAX_SCALING_LATITUDE] because `1 / cos(lat)` diverges at the pole; beyond that the
     * box simply spans all longitudes, which is the correct answer there anyway.
     */
    fun halfWidthDegrees(latitude: Double, halfHeightDegrees: Double): Double {
        val clamped = min(abs(latitude), MAX_SCALING_LATITUDE)
        val scale = cos(Math.toRadians(clamped))
        return min(halfHeightDegrees / scale, 180.0)
    }

    /**
     * The half-height for expansion step [step]: 0 → base, then doubling, capped at
     * [MAX_HALF_DEGREES]. Callers walk the ladder until enough stations are found.
     */
    fun halfHeightForStep(step: Int): Double {
        if (step <= 0) return BASE_HALF_DEGREES
        var half = BASE_HALF_DEGREES
        repeat(step) { half *= 2.0 }
        return min(half, MAX_HALF_DEGREES)
    }

    /** True once [halfHeightForStep] has saturated, so callers can stop walking the ladder. */
    fun isMaxStep(step: Int): Boolean = halfHeightForStep(step) >= MAX_HALF_DEGREES

    /**
     * `lat0,lon0,lat1,lon1` for the box centred on [latitude]/[longitude] at expansion [step].
     *
     * Latitude is clamped to ±90 rather than wrapped: a box over the pole that ran past 90 would be
     * rejected by the API. Longitude is clamped to ±180 for the same reason — a box that would cross
     * the antimeridian is widened to the whole hemisphere edge instead of being split, because the
     * API takes a single box and a split would need two requests. That over-fetches near ±180 and
     * under-fetches on the far side; [AviationWeatherStationFilter] re-ranks by true distance
     * afterwards, so the only cost is a few extra rows.
     */
    fun forLocation(latitude: Double, longitude: Double, step: Int = 0): String {
        val halfHeight = halfHeightForStep(step)
        val halfWidth = halfWidthDegrees(latitude, halfHeight)
        val lat0 = max(-90.0, latitude - halfHeight)
        val lat1 = min(90.0, latitude + halfHeight)
        val lon0 = max(-180.0, longitude - halfWidth)
        val lon1 = min(180.0, longitude + halfWidth)
        return "${fmt(lat0)},${fmt(lon0)},${fmt(lat1)},${fmt(lon1)}"
    }

    /** Fixed 4-dp formatting, locale-independent: `String.format` would emit `48,50` under fr-FR. */
    private fun fmt(value: Double): String {
        val rounded = kotlin.math.round(value * 10_000.0) / 10_000.0
        val whole = rounded.toLong()
        val frac = kotlin.math.round(abs(rounded - whole) * 10_000.0).toLong()
        if (frac == 0L) return whole.toString()
        val sign = if (rounded < 0 && whole == 0L) "-" else ""
        return "$sign$whole.${frac.toString().padStart(4, '0').trimEnd('0')}"
    }
}
