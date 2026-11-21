package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "GoogleGeocodingApi"

/**
 * Google Maps Geocoding API client (`maps.googleapis.com/maps/api/geocode/json`).
 *
 * Used by [com.weatherwidget.data.repository.SharedLocationResolver] ahead of Nominatim when a
 * Google Maps Platform API key is configured. The classic Geocoding endpoint requires `key` as a
 * query parameter, so this class never logs the request URL or key.
 */
class GoogleGeocodingApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String,
) {
    fun isConfigured(): Boolean = apiKeyProvider().isNotBlank()

    suspend fun search(query: String): List<GeocodeResult> {
        if (query.isBlank()) return emptyList()
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return emptyList()

        val httpResponse =
            httpClient.get(BASE_URL) {
                header("Accept", "application/json")
                parameter("address", query)
                parameter("language", "en")
                parameter("key", key)
            }
        val body = httpResponse.bodyAsText()
        if (!httpResponse.status.isSuccess()) {
            throw IllegalStateException("Google Geocoding HTTP ${httpResponse.status.value}")
        }

        val payload = json.decodeFromString<GoogleGeocodeResponse>(body)
        val escapedQuery = query.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        Log.d(
            TAG,
            "search q=\"$escapedQuery\" httpStatus=${httpResponse.status.value} " +
                "apiStatus=${payload.status} results=${payload.results.size}",
        )

        return when (payload.status) {
            "OK" -> payload.results.take(MAX_RESULTS).mapNotNull { it.toGeocodeResult() }
            "ZERO_RESULTS" -> emptyList()
            else -> throw IllegalStateException(
                "Google Geocoding ${payload.status}: ${payload.errorMessage ?: "unknown error"}",
            )
        }
    }

    companion object {
        private const val BASE_URL = "https://maps.googleapis.com/maps/api/geocode/json"
        private const val MAX_RESULTS = 5
    }
}

@Serializable
private data class GoogleGeocodeResponse(
    val status: String = "",
    @SerialName("error_message")
    val errorMessage: String? = null,
    val results: List<GoogleGeocodeItem> = emptyList(),
)

@Serializable
private data class GoogleGeocodeItem(
    @SerialName("formatted_address")
    val formattedAddress: String? = null,
    val geometry: GoogleGeometry? = null,
    @SerialName("address_components")
    val addressComponents: List<GoogleAddressComponent> = emptyList(),
) {
    fun toGeocodeResult(): GeocodeResult? {
        val loc = geometry?.location ?: return null
        val lat = loc.lat ?: return null
        val lon = loc.lng ?: return null
        val display = formattedAddress?.takeIf { it.isNotBlank() } ?: "$lat, $lon"
        val isUs = addressComponents.any { "country" in it.types && it.shortName.equals("US", ignoreCase = true) }
        val shortAddress =
            if (isUs && display.endsWith(", USA")) {
                display.removeSuffix(", USA")
            } else {
                display
            }
        return GeocodeResult(
            displayName = display,
            lat = lat,
            lon = lon,
            shortName = buildShortName(),
            shortAddress = shortAddress,
        )
    }

    private fun buildShortName(): String? {
        val place =
            componentLongName("locality")
                ?: componentLongName("sublocality")
                ?: componentLongName("postal_town")
                ?: componentLongName("administrative_area_level_2")
        val region = componentLongName("administrative_area_level_1") ?: componentLongName("country")
        val parts = listOfNotNull(place, region).filter { it.isNotBlank() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun componentLongName(type: String): String? =
        addressComponents.firstOrNull { type in it.types }?.longName?.takeIf { it.isNotBlank() }
}

@Serializable
private data class GoogleGeometry(
    val location: GoogleLatLng? = null,
)

@Serializable
private data class GoogleLatLng(
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
private data class GoogleAddressComponent(
    @SerialName("long_name")
    val longName: String? = null,
    @SerialName("short_name")
    val shortName: String? = null,
    val types: List<String> = emptyList(),
)
