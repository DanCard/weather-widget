package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.Log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "WeatherApi"

class WeatherApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val BASE_URL = "https://api.weatherapi.com/v1/forecast.json"
    }

    suspend fun getForecast(
        lat: Double,
        lon: Double,
    ): ForecastResult {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("WEATHER_API_KEY is missing.")
        }

        val response: HttpResponse = httpClient.get(BASE_URL) {
            parameter("key", apiKey)
            parameter("q", "$lat,$lon")
            parameter("days", "14")
            parameter("aqi", "no")
            parameter("alerts", "no")
        }

        if (response.status.value !in 200..299) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("No error body")
            throw ApiAccessException(
                source = WeatherSource.WEATHER_API,
                statusCode = response.status.value,
                detail = errorBody,
                message = "WeatherAPI fetch failed: status ${response.status.value}. Detail: $errorBody"
            )
        }

        val responseBody: String = response.body()
        val root = json.parseToJsonElement(responseBody).jsonObject

        val currentObj = root["current"]?.jsonObject
        val currentTemp = currentObj?.get("temp_f")?.jsonPrimitive?.floatOrNull
        val currentCondition = currentObj?.get("condition")?.jsonObject?.get("text")?.jsonPrimitive?.content
        val currentObservedAt = currentObj?.get("last_updated_epoch")?.jsonPrimitive?.longOrNull?.let { it * 1000 }

        val forecastDays = root["forecast"]?.jsonObject?.get("forecastday")?.jsonArray ?: emptyList()
        val dailyForecasts = mutableListOf<DailyForecast>()
        val hourlyForecasts = mutableListOf<HourlyForecast>()

        for (dayElement in forecastDays) {
            val dayObj = dayElement.jsonObject
            val date = dayObj["date"]?.jsonPrimitive?.content ?: continue
            val dayData = dayObj["day"]?.jsonObject ?: continue

            dailyForecasts.add(
                DailyForecast(
                    date = date,
                    highTemp = dayData["maxtemp_f"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    lowTemp = dayData["mintemp_f"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    condition = dayData["condition"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "",
                    precipProbability = dayData["daily_chance_of_rain"]?.jsonPrimitive?.intOrNull
                        ?: dayData["daily_chance_of_snow"]?.jsonPrimitive?.intOrNull,
                    precipAmountMm = dayData["totalprecip_mm"]?.jsonPrimitive?.floatOrNull
                )
            )

            val hours = dayObj["hour"]?.jsonArray ?: continue
            for (hourElement in hours) {
                val hourObj = hourElement.jsonObject
                val timeEpoch = hourObj["time_epoch"]?.jsonPrimitive?.longOrNull ?: continue
                
                hourlyForecasts.add(
                    HourlyForecast(
                        dateTime = timeEpoch * 1000,
                        temperature = hourObj["temp_f"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        condition = hourObj["condition"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "",
                        precipProbability = hourObj["chance_of_rain"]?.jsonPrimitive?.intOrNull
                            ?: hourObj["chance_of_snow"]?.jsonPrimitive?.intOrNull,
                        precipAmountMm = hourObj["precip_mm"]?.jsonPrimitive?.floatOrNull,
                        cloudCover = hourObj["cloud"]?.jsonPrimitive?.intOrNull
                    )
                )
            }
        }

        return ForecastResult(
            currentTemp = currentTemp,
            currentCondition = currentCondition,
            currentObservedAt = currentObservedAt,
            daily = dailyForecasts,
            hourly = hourlyForecasts
        )
    }
}
