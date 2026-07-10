package com.weatherwidget.data.repository

import com.weatherwidget.data.remote.NominatimApi
import com.weatherwidget.data.remote.IpGeolocationApi
import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.remote.GeocodeResult

class SharedLocationResolver(
    private val nominatimApi: NominatimApi,
    private val ipGeolocationApi: IpGeolocationApi,
) {
    suspend fun searchText(query: String): List<ResolvedLocation> =
        runCatching {
            nominatimApi.search(query).map { it.toResolved(source = "Nominatim") }
        }.getOrElse { emptyList() }

    suspend fun fromCoordinates(
        lat: Double,
        lon: Double,
    ): ResolvedLocation {
        val reverse = runCatching { nominatimApi.reverse(lat, lon) }.getOrNull()
        return ResolvedLocation(
            lat = lat,
            lon = lon,
            label = reverse?.displayName ?: "${lat.formatCoord()}, ${lon.formatCoord()}",
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
            label = displayName,
            source = source,
        )
}
