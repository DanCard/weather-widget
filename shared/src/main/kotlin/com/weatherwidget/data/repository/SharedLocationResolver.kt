package com.weatherwidget.data.repository

import com.weatherwidget.data.remote.GoogleGeocodingApi
import com.weatherwidget.data.remote.NominatimApi
import com.weatherwidget.data.remote.IpGeolocationApi
import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.remote.GeocodeResult
import com.weatherwidget.shared.util.Log
import kotlinx.coroutines.CancellationException

private const val TAG = "SharedLocationResolver"

class SharedLocationResolver(
    private val nominatimApi: NominatimApi,
    private val ipGeolocationApi: IpGeolocationApi,
    private val googleGeocodingApi: GoogleGeocodingApi? = null,
) {
    /**
     * Forward-geocode a free-text query. Throws on network/HTTP failure so callers can tell
     * "offline" apart from a genuine empty result instead of blaming the user's spelling.
     *
     * When [googleGeocodingApi] is configured, tries Google Geocoding first on the normalized query;
     * if Google returns no results or fails (e.g. quota/auth), falls back to Nominatim using
     * [AddressQueryCleaner.candidateQueries] (normalized -> unit-stripped -> city/ZIP fallback).
     */
    suspend fun searchText(query: String): List<ResolvedLocation> {
        val candidates = AddressQueryCleaner.candidateQueries(query)
        if (candidates.isEmpty()) return emptyList()

        if (googleGeocodingApi != null && googleGeocodingApi.isConfigured()) {
            try {
                val googleResults = googleGeocodingApi.search(candidates.first())
                if (googleResults.isNotEmpty()) {
                    return googleResults.map { it.toResolved(source = "Google") }
                }
                Log.d(TAG, "Google Geocoding returned 0 results; falling back to Nominatim")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Google Geocoding failed (${e::class.simpleName}: ${e.message}); falling back to Nominatim")
            }
        }

        for ((index, candidate) in candidates.withIndex()) {
            val results = nominatimApi.search(candidate)
            if (results.isNotEmpty()) {
                if (index > 0) {
                    Log.d(TAG, "searchText fallback[$index] matched q=\"$candidate\" results=${results.size}")
                }
                return results.map { it.toResolved(source = "Nominatim") }
            }
        }
        return emptyList()
    }

    suspend fun fromCoordinates(
        lat: Double,
        lon: Double,
    ): ResolvedLocation {
        val reverse = runCatching { nominatimApi.reverse(lat, lon) }.getOrNull()
        return ResolvedLocation(
            lat = lat,
            lon = lon,
            label = reverse?.shortLabel() ?: "${lat.formatCoord()}, ${lon.formatCoord()}",
            source = "Manual coordinates",
        )
    }

    /**
     * Compact human name for the coordinates ("Mountain View, California"), or null when the
     * reverse lookup fails. Never includes the coordinates themselves — callers that show a
     * "Name (lat, lon)" label compose the two.
     */
    suspend fun friendlyName(
        lat: Double,
        lon: Double,
    ): String? =
        runCatching { nominatimApi.reverse(lat, lon) }.getOrNull()
            ?.compactName()
            ?.takeIf { it.isNotBlank() }

    suspend fun suggestPrefill(log: (String) -> Unit = {}): ResolvedLocation? {
        log("Starting IP location lookup...")
        val ip = runCatching { ipGeolocationApi.locate() }.getOrNull()
        if (ip != null) {
            val label = listOfNotNull(ip.city, ip.region, ip.country).filter { it.isNotBlank() }.joinToString(", ")
            log("IP lookup found ${label.ifBlank { "${ip.lat}, ${ip.lon}" }}.")
            return ResolvedLocation(
                lat = ip.lat,
                lon = ip.lon,
                label = label.ifBlank { "${ip.lat}, ${ip.lon}" },
                source = "IP lookup",
            )
        }
        return null
    }

    private fun Double.formatCoord(): String = "%.4f".format(this)

    private fun GeocodeResult.toResolved(source: String): ResolvedLocation =
        ResolvedLocation(
            lat = lat,
            lon = lon,
            label = shortLabel(),
            source = source,
        )

    /**
     * The label users pick from and later see in Settings: the postal-style short address when
     * Nominatim sent a structured one, else its full display name.
     */
    private fun GeocodeResult.shortLabel(): String = shortAddress ?: displayName
}
