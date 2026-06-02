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
)

@Serializable
private data class NominatimPlace(
    @SerialName("display_name")
    val displayName: String? = null,
    val lat: String? = null,
    val lon: String? = null,
) {
    fun toResult(): GeocodeResult? {
        val parsedLat = lat?.toDoubleOrNull() ?: return null
        val parsedLon = lon?.toDoubleOrNull() ?: return null
        return GeocodeResult(
            displayName = displayName?.takeIf { it.isNotBlank() } ?: "$parsedLat, $parsedLon",
            lat = parsedLat,
            lon = parsedLon,
        )
    }
}
