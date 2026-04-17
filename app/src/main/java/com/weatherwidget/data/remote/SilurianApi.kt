package com.weatherwidget.data.remote

import android.util.Log
import com.weatherwidget.BuildConfig
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

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

suspend fun getForecast(
        lat: Double,
        lon: Double,
        days: Int = 14,
    ): ForecastResult = coroutineScope {
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
                // Fetch yesterday's history
                val yesterday = java.time.LocalDate.now().minusDays(1).toString()
                val historyResponse = try {
                    httpClient.get("$BASE_URL/history/hourly") {
                        header("X-API-Key", apiKey)
                        parameter("latitude", lat)
                        parameter("longitude", lon)
                        parameter("start_date", yesterday)
                        parameter("end_date", yesterday)
                        parameter("units", "imperial")
                    }.body<String>()
                } catch (e: Exception) {
                    null
                }

                val forecastResponse = httpClient.get("$BASE_URL/forecast/hourly") {
                    header("X-API-Key", apiKey)
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("units", "imperial")
                }.body<String>()

                val historyData = historyResponse?.let { parseTimeseries(it, "hourly") } ?: emptyList()
                val forecastData = parseTimeseries(forecastResponse, "hourly")
                
                (historyData + forecastData)
            } catch (e: Exception) {
                Log.e(TAG, "Hourly data fetch failed: ${e.message}")
                null
            }
        }

        val dailyResponse = dailyDeferred.await()
        val hourlyDataList = hourlyDeferred.await()

        val daily = if (dailyResponse != null) {
            parseTimeseries(dailyResponse, "daily").mapNotNull { entry ->
                val time = entry["timestamp"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val high = entry["max_temperature"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
                val low = entry["min_temperature"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
                val condition = entry["weather_code"]?.jsonPrimitive?.content ?: "Clear"
                val precip = (entry["precipitation_probability"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt()

DailyForecast(
      date = time.take(10),
      highTemp = high.toFloat(),
      lowTemp = low.toFloat(),
      condition = condition,
      precipProbability = precip,
      precipAmountMm = parsePrecipAmountMm(entry),
    )
            }
        } else emptyList()

        val hourly = if (hourlyDataList != null) {
            hourlyDataList.mapNotNull { entry ->
                val time = entry["timestamp"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val temp = entry["temperature"]?.jsonPrimitive?.floatOrNull ?: 0f
                val condition = entry["weather_code"]?.jsonPrimitive?.content ?: "Clear"
                val precip = (entry["precipitation_probability"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt()
                val cloudCover = entry["cloud_cover"]?.jsonPrimitive?.doubleOrNull?.toInt()

                val ts = try {
                    java.time.LocalDateTime.parse(time.take(19)).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    return@mapNotNull null
                }

                HourlyForecast(
                    dateTime = ts,
                    temperature = temp,
                    condition = condition,
                    precipProbability = precip,
                    precipAmountMm = parsePrecipAmountMm(entry),
                    cloudCover = cloudCover,
                )
            }.distinctBy { it.dateTime }.sortedBy { it.dateTime }
        } else emptyList()

        val firstHour = hourly.firstOrNull()
        
ForecastResult(
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

    private fun parsePrecipAmountMm(entry: kotlinx.serialization.json.JsonObject): Float? {
        return entry["precipitation_mm"]?.jsonPrimitive?.floatOrNull
            ?: entry["precipitation_amount_mm"]?.jsonPrimitive?.floatOrNull
    }
}
