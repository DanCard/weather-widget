package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource

/**
 * Where NWS (api.weather.gov) has forecast data — the US and its territories — and the one rule
 * both platforms apply when a location leaves it: retire NWS from the visible-source list.
 *
 * Outside these boxes `/points` answers 404 InvalidPoint, so NWS can never be a working source
 * there; leaving it in the list puts a source that cannot load one header tap away (desktop) or
 * keeps painting the previous site's cached NWS forecast under the new label (Android).
 *
 * Android's `SetupSourceAvailabilityChecker` probes the live API and consults [covers] only when
 * the probe is inconclusive (offline, timeout); the desktop location picker and config load use
 * the box directly. Works in `List<String>` of `WeatherSource.id`, like [WeatherSourceOrdering],
 * because that is how both platforms persist the visible order.
 */
object NwsCoverage {
    fun covers(lat: Double, lon: Double): Boolean =
        (lat in 24.0..50.0 && lon in -125.0..-66.0) || // CONUS
            (lat in 51.0..72.0 && lon in -180.0..-130.0) || // Alaska
            (lat in 18.0..23.0 && lon in -161.0..-154.0) || // Hawaii
            (lat in 17.0..19.0 && lon in -68.0..-65.0) // Puerto Rico

    /**
     * [visibleIds] without NWS. Never empties the list — a config whose only source is NWS falls
     * back to Open-Meteo, the keyless source that works everywhere. Same instance when NWS was
     * not present, so callers can detect a no-op by identity.
     */
    fun retireNws(visibleIds: List<String>): List<String> {
        if (WeatherSource.NWS.id !in visibleIds) return visibleIds
        val without = visibleIds.filter { it != WeatherSource.NWS.id }
        return without.ifEmpty { listOf(WeatherSource.OPEN_METEO.id) }
    }

    /**
     * [visibleIds] with NWS put back in front — its default position — for a site inside coverage
     * after an automatic [retireNws]. Only callers that know the removal was automatic (not the
     * user unticking NWS in Settings) should call this. Same instance when NWS is already present.
     */
    fun restoreNws(visibleIds: List<String>): List<String> =
        if (WeatherSource.NWS.id in visibleIds) visibleIds else listOf(WeatherSource.NWS.id) + visibleIds

    /** [retireNws] when [lat]/[lon] is outside coverage; [visibleIds] unchanged otherwise. */
    fun visibleSourcesFor(lat: Double, lon: Double, visibleIds: List<String>): List<String> =
        if (covers(lat, lon)) visibleIds else retireNws(visibleIds)
}
