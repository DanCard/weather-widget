package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.remote.OpenMeteoApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Thin desktop-side orchestration over the shared API clients. Deliberately does NOT reuse the
 * Android repositories (they are bound to Room + Context); it just fetches via the shared
 * [OpenMeteoApi] and returns the plain model [ForecastResult].
 *
 * For the MVP this fetches live on demand. SQLDelight persistence + NWS support are layered on later.
 */
class DesktopWeatherService(
    private val latitude: Double = DEFAULT_LATITUDE,
    private val longitude: Double = DEFAULT_LONGITUDE,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // CIO engine = the desktop counterpart to the Android engine used in :app's AppModule.
    // Timeout config mirrors AppModule.provideHttpClient.
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    private val openMeteo = OpenMeteoApi(httpClient, json)

    suspend fun fetchForecast(): ForecastResult = openMeteo.getForecast(latitude, longitude)

    fun close() = httpClient.close()

    companion object {
        // Default location matches the Android app (Google HQ) until config/zip entry is added.
        const val DEFAULT_LATITUDE = 37.4220
        const val DEFAULT_LONGITUDE = -122.0841
    }
}
