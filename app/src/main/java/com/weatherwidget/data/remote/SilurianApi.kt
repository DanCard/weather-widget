package com.weatherwidget.data.remote

import android.util.Log
import com.weatherwidget.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val TAG = "SilurianApi"

class SilurianApi @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    private var apiKeyOverride: String? = null

    @androidx.annotation.VisibleForTesting
    fun setApiKeyForTesting(apiKey: String) {
        apiKeyOverride = apiKey
    }

    companion object {
        private const val BASE_URL = "https://earth.weather.silurian.ai/api/v1"
    }

    data class SilurianForecast(
        val currentTemp: Float?,
        val currentCondition: String?,
        val currentObservedAt: Long?,
        val daily: List<DailyForecast>,
        val hourly: List<HourlyForecast>
    )

    data class DailyForecast(
        val date: String,
        val highTemp: Int,
        val lowTemp: Int,
        val condition: String,
        val precipProbability: Int,
    )

    data class HourlyForecast(
        val dateTimeString: String,
        val temperature: Float,
        val condition: String,
        val precipProbability: Int,
        val cloudCover: Int? = null,
    )

    suspend fun getForecast(
        lat: Double,
        lon: Double,
        days: Int = 14,
    ): SilurianForecast = coroutineScope {
        val apiKey = apiKeyOverride ?: BuildConfig.SILURIAN_API_KEY
        if (apiKey.isBlank()) {
            throw IllegalStateException("SILURIAN_API_KEY is missing. Add it to local.properties or SILURIAN_API_KEY env var.")
        }

        val dailyDeferred = async {
            try {
                httpClient.get("$BASE_URL/forecast/daily") {
                    header("X-API-Key", apiKey)
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("units", "imperial")
                }.body<String>()
            } catch (e: Exception) {
                Log.e(TAG, "Daily forecast fetch failed: ${e.message}")
                null
            }
        }

        val hourlyDeferred = async {
            try {
                httpClient.get("$BASE_URL/forecast/hourly") {
                    header("X-API-Key", apiKey)
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("units", "imperial")
                }.body<String>()
            } catch (e: Exception) {
                Log.e(TAG, "Hourly forecast fetch failed: ${e.message}")
                null
            }
        }

        val dailyResponse = dailyDeferred.await()
        val hourlyResponse = hourlyDeferred.await()

        val daily = if (dailyResponse != null) {
            parseTimeseries(dailyResponse, "daily").mapNotNull { entry ->
                val time = entry["timestamp"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val high = entry["max_temperature"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
                val low = entry["min_temperature"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
                val condition = entry["weather_code"]?.jsonPrimitive?.content ?: "Clear"
                val precip = (entry["precipitation_probability"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt()

                DailyForecast(time.take(10), high, low, condition, precip)
            }
        } else emptyList()

        val hourly = if (hourlyResponse != null) {
            parseTimeseries(hourlyResponse, "hourly").mapNotNull { entry ->
                val time = entry["timestamp"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val temp = entry["temperature"]?.jsonPrimitive?.floatOrNull ?: 0f
                val condition = entry["weather_code"]?.jsonPrimitive?.content ?: "Clear"
                val precip = (entry["precipitation_probability"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt()
                val cloudCover = entry["cloud_cover"]?.jsonPrimitive?.doubleOrNull?.toInt()

                // Format: 2026-03-03T08:00:00 -> 2026-03-03T08:00
                val formattedTime = time.take(16)
                HourlyForecast(formattedTime, temp, condition, precip, cloudCover)
            }
        } else emptyList()

        val firstHour = hourly.firstOrNull()
        
        SilurianForecast(
            currentTemp = firstHour?.temperature,
            currentCondition = firstHour?.condition,
            currentObservedAt = null,
            daily = daily,
            hourly = hourly
        )
    }

    private fun parseTimeseries(response: String, key: String): List<kotlinx.serialization.json.JsonObject> {
        return try {
            val root = json.parseToJsonElement(response).jsonObject
            root[key]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Silurian timeseries ($key): ${e.message}")
            emptyList()
        }
    }
}
