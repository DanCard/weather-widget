package com.weatherwidget.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

class NominatimApi
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
    ) {
        suspend fun search(query: String): List<GeocodeResult> {
            if (query.isBlank()) return emptyList()
            val response: String =
                httpClient.get("$BASE_URL/search") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                    parameter("q", query)
                    parameter("format", "jsonv2")
                    parameter("limit", "5")
                }.body()

            return json.decodeFromString<List<NominatimPlace>>(response).mapNotNull { it.toResult() }
        }

        suspend fun reverse(
            lat: Double,
            lon: Double,
        ): GeocodeResult? {
            val response: String =
                httpClient.get("$BASE_URL/reverse") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                    parameter("lat", lat)
                    parameter("lon", lon)
                    parameter("format", "jsonv2")
                    parameter("addressdetails", "1")
                }.body()

            return json.decodeFromString<NominatimPlace>(response).toResult()
        }

        companion object {
            private const val BASE_URL = "https://nominatim.openstreetmap.org"
            private const val USER_AGENT = "WeatherWidget/1.0 (contact@weatherwidget.app)"
        }
    }

data class GeocodeResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    /** Compact "place, region" name from the structured address (reverse lookups only). */
    val shortName: String? = null,
) {
    /**
     * Best available compact name: the structured short name, else the first two components
     * of the display name (Nominatim display names are comma-joined, most-specific first).
     */
    fun compactName(): String = shortName ?: displayName.split(", ").take(2).joinToString(", ")
}

@Serializable
private data class NominatimPlace(
    @SerialName("display_name")
    val displayName: String? = null,
    val lat: String? = null,
    val lon: String? = null,
    val address: NominatimAddress? = null,
) {
    fun toResult(): GeocodeResult? {
        val parsedLat = lat?.toDoubleOrNull() ?: return null
        val parsedLon = lon?.toDoubleOrNull() ?: return null
        return GeocodeResult(
            displayName = displayName?.takeIf { it.isNotBlank() } ?: "$parsedLat, $parsedLon",
            lat = parsedLat,
            lon = parsedLon,
            shortName = address?.toShortName(),
        )
    }
}

@Serializable
private data class NominatimAddress(
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val hamlet: String? = null,
    val municipality: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
) {
    /** "Mountain View, California" — most specific settlement plus region, whatever exists. */
    fun toShortName(): String? {
        val place = city ?: town ?: village ?: hamlet ?: municipality ?: county
        val region = state ?: country
        val parts = listOfNotNull(place, region).filter { it.isNotBlank() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
