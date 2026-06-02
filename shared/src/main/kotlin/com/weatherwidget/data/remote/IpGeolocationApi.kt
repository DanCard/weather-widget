package com.weatherwidget.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

class IpGeolocationApi
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
    ) {
        suspend fun locate(): IpLocation? {
            val response: String = httpClient.get(BASE_URL).body()
            return json.decodeFromString<IpApiResponse>(response).toLocation()
        }

        companion object {
            private const val BASE_URL = "https://ipapi.co/json/"
        }
    }

data class IpLocation(
    val lat: Double,
    val lon: Double,
    val city: String?,
    val region: String?,
    val country: String?,
)

@Serializable
private data class IpApiResponse(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
    val region: String? = null,
    @SerialName("country_name")
    val country: String? = null,
) {
    fun toLocation(): IpLocation? {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        return IpLocation(
            lat = lat,
            lon = lon,
            city = city,
            region = region,
            country = country,
        )
    }
}
