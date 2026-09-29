package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource

/**
 * Which enabled sources can serve a location. The one rule both platforms apply.
 *
 * The user's enabled list records only the user's choices; coverage is a fact about the current
 * location and is derived here every time, never written back. Moving to Warsaw does not untick
 * NWS, so moving back to Mountain View has nothing to restore. The model this replaces removed NWS
 * from the list and relied on an "the app did it" marker to put it back; any path that lost the
 * marker (an older build, a Settings save) left NWS off for good — the 2026-09-29 report
 * ("NWS should have been automatically enabled since it was disabled automatically").
 *
 * Works in `List<String>` of [WeatherSource.id], like [WeatherSourceOrdering], because that is how
 * both platforms persist the list.
 */
object SourceCoverage {
    /** True when [sourceId] can serve [lat]/[lon]. No location (null or NaN) restricts nothing. */
    fun supports(sourceId: String, lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return true
        return when (sourceId) {
            WeatherSource.NWS.id -> NwsCoverage.covers(lat, lon)
            else -> true
        }
    }

    /**
     * [enabledIds] minus the sources that cannot serve [lat]/[lon], in the user's order. Never
     * empty — an enabled list with nothing usable here falls back to Open-Meteo, the keyless
     * source that works everywhere; that fallback is for display and fetching only, never stored.
     * Same instance when nothing is filtered, so callers can detect a no-op by identity.
     */
    fun effectiveSources(enabledIds: List<String>, lat: Double?, lon: Double?): List<String> {
        val usable = enabledIds.filter { supports(it, lat, lon) }
        if (usable.size == enabledIds.size) return enabledIds
        return usable.ifEmpty { listOf(WeatherSource.OPEN_METEO.id) }
    }
}
