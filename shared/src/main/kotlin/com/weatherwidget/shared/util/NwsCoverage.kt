package com.weatherwidget.shared.util

/**
 * Where NWS (api.weather.gov) has forecast data — the US and its territories.
 *
 * Outside these boxes `/points` answers 404 InvalidPoint, so NWS can never be a working source
 * there. [SourceCoverage] turns this into "NWS is unavailable here" without touching the user's
 * enabled list.
 */
object NwsCoverage {
    fun covers(lat: Double, lon: Double): Boolean =
        (lat in 24.0..50.0 && lon in -125.0..-66.0) || // CONUS
            (lat in 51.0..72.0 && lon in -180.0..-130.0) || // Alaska
            (lat in 18.0..23.0 && lon in -161.0..-154.0) || // Hawaii
            (lat in 17.0..19.0 && lon in -68.0..-65.0) // Puerto Rico
}
