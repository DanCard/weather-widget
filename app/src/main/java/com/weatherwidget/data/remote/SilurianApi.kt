package com.weatherwidget.data.remote

import android.util.Log
import com.weatherwidget.BuildConfig
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
                val response = httpClient.get("$BASE_URL/forecast/daily") {
                    header("X-API-Key", apiKey)
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("units", "imperial")
                }
                if (response.status.value !in 200..299) {
                    val errorBody = runCatching { response.bodyAsText() }.getOrDefault("No error body")
                    throw ApiAccessException(
                        source = WeatherSource.SILURIAN,
                        statusCode = response.status.value,
                        detail = errorBody,
                        message = "Silurian daily fetch failed: status ${response.status.value}."
                    )
                }
                response.body<String>()
            } catch (e: ApiAccessException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Daily forecast fetch failed: ${e.message}")
                null
            }
        }

        val hourlyDeferred = async {
            try {
                // Fetch last 3 days of history
                val historyData = mutableListOf<kotlinx.serialization.json.JsonObject>()
                for (i in 1..3) {
                    val date = java.time.LocalDate.now().minusDays(i.toLong()).toString()
                    try {
                        val historyHttpResponse = httpClient.get("$BASE_URL/history/hourly") {
                            header("X-API-Key", apiKey)
                            parameter("latitude", lat)
                            parameter("longitude", lon)
                            parameter("start_date", date)
                            parameter("end_date", date)
                            parameter("units", "imperial")
                        }
                        if (historyHttpResponse.status.value in 200..299) {
                            val historyResponse = historyHttpResponse.body<String>()
                            historyData.addAll(parseTimeseries(historyResponse, "hourly"))
                        } else {
                            Log.w(TAG, "History fetch returned status ${historyHttpResponse.status.value} for $date")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "History fetch failed for $date: ${e.message}")
                    }
                }

                val forecastHttpResponse = httpClient.get("$BASE_URL/forecast/hourly") {
                    header("X-API-Key", apiKey)
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("units", "imperial")
                }
                if (forecastHttpResponse.status.value !in 200..299) {
                    val errorBody = runCatching { forecastHttpResponse.bodyAsText() }.getOrDefault("No error body")
                    throw ApiAccessException(
                        source = WeatherSource.SILURIAN,
                        statusCode = forecastHttpResponse.status.value,
                        detail = errorBody,
                        message = "Silurian hourly fetch failed: status ${forecastHttpResponse.status.value}."
                    )
                }
                val forecastResponse = forecastHttpResponse.body<String>()

                val forecastData = parseTimeseries(forecastResponse, "hourly")
                
                (historyData + forecastData)
            } catch (e: ApiAccessException) {
                throw e
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
                val high = entry["max_temperature"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: Float.NaN
                val low = entry["min_temperature"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: Float.NaN
                val condition = entry["weather_code"]?.jsonPrimitive?.content ?: "Clear"
                val precip = (entry["precipitation_probability"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt()

DailyForecast(
      date = time.take(10),
      highTemp = high,
      lowTemp = low,
      condition = condition,
      precipProbability = precip,
      precipAmountMm = parsePrecipAmountMm(entry),
    )
            }
        } else emptyList()

        val hourly = if (hourlyDataList != null) {
            hourlyDataList.mapNotNull { entry ->
                val time = entry["timestamp"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val temp = entry["temperature"]?.jsonPrimitive?.floatOrNull ?: Float.NaN
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
