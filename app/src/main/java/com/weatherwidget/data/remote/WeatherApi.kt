package com.weatherwidget.data.remote

import android.util.Log
import com.weatherwidget.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
    ) {
        companion object {
            private const val BASE_URL = "https://api.weatherapi.com/v1"
        }

        suspend fun getForecast(
            lat: Double,
            lon: Double,
            days: Int = 14,
        ): WeatherForecast {
            val apiKey = BuildConfig.WEATHER_API_KEY
            if (apiKey.isBlank()) {
                throw IllegalStateException("WEATHER_API_KEY is missing. Add it to local.properties or WEATHER_API_KEY env var.")
            }

            val response: String =
                httpClient.get("$BASE_URL/forecast.json") {
                    parameter("key", apiKey)
                    parameter("q", "$lat,$lon")
                    parameter("days", days)
                    parameter("aqi", "no")
                    parameter("alerts", "no")
                }.body()

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
                        highTemp = dayData["maxtemp_f"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                        lowTemp = dayData["mintemp_f"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                        condition = dayData["condition"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Unknown",
                        precipProbability = dayData["daily_chance_of_rain"]?.jsonPrimitive?.content?.toIntOrNull(),
                    )
                }

            val hourlyForecasts =
                forecastDays.flatMap { dayElement ->
                    val hours = dayElement.jsonObject["hour"]?.jsonArray ?: emptyList()
                    hours.mapNotNull { hourElement ->
                        val hourObj = hourElement.jsonObject
                        val rawTime = hourObj["time"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val dateTime = rawTime.replace(" ", "T")
                        val normalizedDateTime = if (dateTime.length >= 13) "${dateTime.substring(0, 13)}:00" else return@mapNotNull null

                        HourlyForecast(
                            dateTime = normalizedDateTime,
                            temperature = hourObj["temp_f"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@mapNotNull null,
                            condition = hourObj["condition"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Unknown",
                            precipProbability = hourObj["chance_of_rain"]?.jsonPrimitive?.content?.toIntOrNull(),
                        )
                    }
                }

            Log.d(TAG, "getForecast: Parsed ${dailyForecasts.size} daily and ${hourlyForecasts.size} hourly entries")

            return WeatherForecast(
                currentTemp = current?.get("temp_f")?.jsonPrimitive?.content?.toFloatOrNull(),
                daily = dailyForecasts,
                hourly = hourlyForecasts,
            )
        }

        data class WeatherForecast(
            val currentTemp: Float?,
            val daily: List<DailyForecast>,
            val hourly: List<HourlyForecast>,
        )

        data class DailyForecast(
            val date: String,
            val highTemp: Float,
            val lowTemp: Float,
            val condition: String,
            val precipProbability: Int? = null,
        )

        data class HourlyForecast(
            val dateTime: String, // ISO 8601 format: "2026-02-24T14:00"
            val temperature: Float,
            val condition: String,
            val precipProbability: Int? = null,
        )
    }
