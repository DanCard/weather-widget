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
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "WeatherApi"

class WeatherApi
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
        private val widgetStateManager: WidgetStateManager,
    ) {
        companion object {
            private const val BASE_URL = "https://api.weatherapi.com/v1"
        }

        suspend fun getForecast(
            lat: Double,
            lon: Double,
            days: Int = 14,
        ): ForecastResult {
            val apiKey = widgetStateManager.getApiKey(WeatherSource.WEATHER_API) ?: BuildConfig.WEATHER_API_KEY
            if (apiKey.isBlank()) {
                throw IllegalStateException("WEATHER_API_KEY is missing. Add it to local.properties or WEATHER_API_KEY env var.")
            }

            // Fetch history for the previous 3 days to backfill actuals
            val historyHourly = mutableListOf<HourlyForecast>()
            for (i in 1..3) {
                val date = java.time.LocalDate.now().minusDays(i.toLong()).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                try {
                    historyHourly.addAll(getHistory(lat, lon, date))
                } catch (e: Exception) {
                    Log.e(TAG, "History fetch failed for $date: ${e.message}")
                }
            }

            val httpResponse =
                httpClient.get("$BASE_URL/forecast.json") {
                    parameter("key", apiKey)
                    parameter("q", "$lat,$lon")
                    parameter("days", days)
                    parameter("aqi", "no")
                    parameter("alerts", "no")
                }
            if (httpResponse.status.value !in 200..299) {
                val errorBody = runCatching { httpResponse.bodyAsText() }.getOrDefault("No error body")
                throw ApiAccessException(
                    source = WeatherSource.WEATHER_API,
                    statusCode = httpResponse.status.value,
                    detail = errorBody,
                    message = "WeatherAPI forecast fetch failed: status ${httpResponse.status.value}. Detail: $errorBody"
                )
            }
            val response: String = httpResponse.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject

            val current = jsonObj["current"]?.jsonObject
            val forecastDays =
                jsonObj["forecast"]?.jsonObject?.get("forecastday")?.jsonArray ?: emptyList()

            val dailyForecasts =
                forecastDays.mapNotNull { dayElement ->
                    val dayObj = dayElement.jsonObject
                    val date = dayObj["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val dayData = dayObj["day"]?.jsonObject ?: return@mapNotNull null

                    DailyForecast(
                        date = date,
                        highTemp = dayData["maxtemp_f"]?.jsonPrimitive?.content?.toFloatOrNull() ?: Float.NaN,
                        lowTemp = dayData["mintemp_f"]?.jsonPrimitive?.content?.toFloatOrNull() ?: Float.NaN,
                        condition = dayData["condition"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Unknown",
                        iconToken = dayData["condition"]?.jsonObject?.get("icon")?.jsonPrimitive?.content,
                        precipProbability = dayData["daily_chance_of_rain"]?.jsonPrimitive?.content?.toIntOrNull(),
                        precipAmountMm = dayData["totalprecip_mm"]?.jsonPrimitive?.content?.toFloatOrNull(),
                    )
                }

            val forecastHourly =
                forecastDays.flatMap { dayElement ->
                    val hours = dayElement.jsonObject["hour"]?.jsonArray ?: emptyList()
                    hours.mapNotNull { hourElement ->
                        val hourObj = hourElement.jsonObject
                        val rawTime = hourObj["time"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val ts = try {
                            val dt = rawTime.replace(" ", "T")
                            val normalized = if (dt.length >= 13) "${dt.substring(0, 13)}:00" else return@mapNotNull null
                            java.time.LocalDateTime.parse(normalized).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        } catch (e: Exception) {
                            return@mapNotNull null
                        }

                        HourlyForecast(
                            dateTime = ts,
                            temperature = hourObj["temp_f"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@mapNotNull null,
                            condition = hourObj["condition"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Unknown",
                            precipProbability = hourObj["chance_of_rain"]?.jsonPrimitive?.content?.toIntOrNull(),
                            precipAmountMm = hourObj["precip_mm"]?.jsonPrimitive?.content?.toFloatOrNull(),
                            cloudCover = hourObj["cloud"]?.jsonPrimitive?.content?.toIntOrNull(),
                        )
                    }
                }

            val hourlyForecasts = (historyHourly + forecastHourly).distinctBy { it.dateTime }.sortedBy { it.dateTime }

            Log.d(TAG, "getForecast: Parsed ${dailyForecasts.size} daily and ${hourlyForecasts.size} hourly entries (including ${historyHourly.size} history)")

            return ForecastResult(
                currentTemp = current?.get("temp_f")?.jsonPrimitive?.content?.toFloatOrNull(),
                currentObservedAt = current?.get("last_updated_epoch")?.jsonPrimitive?.content?.toLongOrNull()?.times(1000),
                daily = dailyForecasts,
                hourly = hourlyForecasts,
            )
        }

        suspend fun getHistory(
            lat: Double,
            lon: Double,
            date: String,
        ): List<HourlyForecast> {
            val apiKey = BuildConfig.WEATHER_API_KEY
            if (apiKey.isBlank()) return emptyList()

            val httpResponse =
                httpClient.get("$BASE_URL/history.json") {
                    parameter("key", apiKey)
                    parameter("q", "$lat,$lon")
                    parameter("dt", date)
                    parameter("aqi", "no")
                    parameter("alerts", "no")
                }
            if (httpResponse.status.value !in 200..299) {
                val errorBody = runCatching { httpResponse.bodyAsText() }.getOrDefault("No error body")
                throw ApiAccessException(
                    source = WeatherSource.WEATHER_API,
                    statusCode = httpResponse.status.value,
                    detail = errorBody,
                    message = "WeatherAPI history fetch failed: status ${httpResponse.status.value}. Detail: $errorBody"
                )
            }
            val response: String = httpResponse.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val forecastDays =
                jsonObj["forecast"]?.jsonObject?.get("forecastday")?.jsonArray ?: emptyList()

            return forecastDays.flatMap { dayElement ->
                val hours = dayElement.jsonObject["hour"]?.jsonArray ?: emptyList()
                hours.mapNotNull { hourElement ->
                    val hourObj = hourElement.jsonObject
                    val rawTime = hourObj["time"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val ts = try {
                        val dt = rawTime.replace(" ", "T")
                        val normalized = if (dt.length >= 13) "${dt.substring(0, 13)}:00" else return@mapNotNull null
                        java.time.LocalDateTime.parse(normalized).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        return@mapNotNull null
                    }

                    HourlyForecast(
                        dateTime = ts,
                        temperature = hourObj["temp_f"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@mapNotNull null,
                        condition = hourObj["condition"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Unknown",
                        precipProbability = hourObj["chance_of_rain"]?.jsonPrimitive?.content?.toIntOrNull(),
                        precipAmountMm = hourObj["precip_mm"]?.jsonPrimitive?.content?.toFloatOrNull(),
                        cloudCover = hourObj["cloud"]?.jsonPrimitive?.content?.toIntOrNull(),
                    )
                }
            }
        }

        suspend fun getCurrent(
            lat: Double,
            lon: Double,
        ): CurrentReading? {
            val apiKey = BuildConfig.WEATHER_API_KEY
            if (apiKey.isBlank()) {
                throw IllegalStateException("WEATHER_API_KEY is missing. Add it to local.properties or WEATHER_API_KEY env var.")
            }

            val httpResponse =
                httpClient.get("$BASE_URL/current.json") {
                    parameter("key", apiKey)
                    parameter("q", "$lat,$lon")
                }
            if (httpResponse.status.value !in 200..299) {
                val errorBody = runCatching { httpResponse.bodyAsText() }.getOrDefault("No error body")
                throw ApiAccessException(
                    source = WeatherSource.WEATHER_API,
                    statusCode = httpResponse.status.value,
                    detail = errorBody,
                    message = "WeatherAPI current fetch failed: status ${httpResponse.status.value}. Detail: $errorBody"
                )
            }
            val response: String = httpResponse.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val current = jsonObj["current"]?.jsonObject ?: return null
            val temp = current["temp_f"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
            val condition = current["condition"]?.jsonObject?.get("text")?.jsonPrimitive?.content

            return CurrentReading(
                temperature = temp,
                condition = condition,
                observedAt = current["last_updated_epoch"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000),
            )
        }

        data class CurrentReading(
            val temperature: Float,
            val condition: String?,
            val observedAt: Long? = null,
        )
    }
