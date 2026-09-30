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
                    header("User-Agent", HttpUserAgent.VALUE)
                    header("Accept", "application/json")
                    parameter("q", query)
                    parameter("format", "jsonv2")
                    parameter("limit", "5")
                    parameter("addressdetails", "1")
                    parameter("accept-language", LANGUAGE)
                }.body()

            return json.decodeFromString<List<NominatimPlace>>(response).mapNotNull { it.toResult() }
        }

        suspend fun reverse(
            lat: Double,
            lon: Double,
        ): GeocodeResult? {
            val response: String =
                httpClient.get("$BASE_URL/reverse") {
                    header("User-Agent", HttpUserAgent.VALUE)
                    header("Accept", "application/json")
                    parameter("lat", lat)
                    parameter("lon", lon)
                    parameter("format", "jsonv2")
                    parameter("addressdetails", "1")
                    parameter("accept-language", LANGUAGE)
                }.body()

            return json.decodeFromString<NominatimPlace>(response).toResult()
        }

        companion object {
            private const val BASE_URL = "https://nominatim.openstreetmap.org"

            /**
             * Without this Nominatim names places in the local script ("Львів, Львівська область,
             * Україна" for Lviv), which the UI shows verbatim. English with a wildcard fallback
             * for names OSM has no English label for.
             */
            private const val LANGUAGE = "en,*"
        }
    }

data class GeocodeResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    /** Compact "place, region" name from the structured address. */
    val shortName: String? = null,
    /**
     * Postal-style one-liner from the structured address ("860 Avery Drive, Mountain View, CA 94043");
     * null when Nominatim returned no address. [displayName] runs to county and country
     * ("860, Avery Drive, Mountain View, Santa Clara County, California, 94043, United States").
     */
    val shortAddress: String? = null,
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
            shortAddress = address?.toShortAddress(),
        )
    }
}

@Serializable
private data class NominatimAddress(
    @SerialName("house_number")
    val houseNumber: String? = null,
    val road: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val hamlet: String? = null,
    val municipality: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
    val postcode: String? = null,
    @SerialName("country_code")
    val countryCode: String? = null,
    /** "US-CA": the state's postal abbreviation, without a name table. */
    @SerialName("ISO3166-2-lvl4")
    val isoRegion: String? = null,
) {
    /** "Mountain View, California" — most specific settlement plus region, whatever exists. */
    fun toShortName(): String? {
        val place = city ?: town ?: village ?: hamlet ?: municipality ?: county
        val region = state ?: country
        val parts = listOfNotNull(place, region).filter { it.isNotBlank() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * US: "860 Avery Drive, Mountain View, CA 94043" (a ZIP is what people type, so it stays).
     * Elsewhere: "34A Skierniewicka, Warsaw, Poland" — the country reads better than a
     * voivodeship or oblast name, and foreign postcodes add length without recognition.
     * Street and place are each optional; a city search gives "Kyiv, Ukraine".
     */
    fun toShortAddress(): String? {
        val street = listOfNotNull(houseNumber, road).filter { it.isNotBlank() }.joinToString(" ")
        val place = city ?: town ?: village ?: hamlet ?: municipality ?: county
        val tail = if (countryCode.equals("us", ignoreCase = true)) {
            val stateCode = isoRegion?.takeIf { it.startsWith("US-") }?.removePrefix("US-") ?: state
            listOfNotNull(stateCode, postcode).filter { it.isNotBlank() }.joinToString(" ")
        } else {
            country
        }
        val parts = listOf(street, place ?: state, tail).filterNotNull().filter { it.isNotBlank() }
        // "Poland" searched alone would otherwise repeat as place and country.
        return parts.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
