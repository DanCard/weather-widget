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

private const val TAG = "OpenWeatherMapApi"

class OpenWeatherMapApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val BASE_URL = "https://api.openweathermap.org/data/3.0/onecall"
    }

    suspend fun getForecast(
        lat: Double,
        lon: Double,
    ): ForecastResult {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("OPEN_WEATHER_MAP_API_KEY is missing.")
        }

        val response: HttpResponse = httpClient.get(BASE_URL) {
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("appid", apiKey)
            parameter("units", "imperial")
            parameter("exclude", "minutely,alerts")
        }

        if (response.status.value !in 200..299) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("No error body")
            throw ApiAccessException(
                source = WeatherSource.OPEN_WEATHER_MAP,
                statusCode = response.status.value,
                detail = errorBody,
                message = "OpenWeatherMap fetch failed: status ${response.status.value}. Detail: $errorBody"
            )
        }

        val responseBody: String = response.body()
        val root = json.parseToJsonElement(responseBody).jsonObject

        val currentObj = root["current"]?.jsonObject
        val currentTemp = currentObj?.get("temp")?.jsonPrimitive?.floatOrNull
        val currentCondition = currentObj?.get("weather")?.jsonArray?.firstOrNull()?.jsonObject?.get("main")?.jsonPrimitive?.content
        val currentObservedAt = currentObj?.get("dt")?.jsonPrimitive?.longOrNull?.let { it * 1000 }

        val hourlyData = root["hourly"]?.jsonArray ?: emptyList()
        val hourlyForecasts = hourlyData.mapNotNull { element ->
            val obj = element.jsonObject
            val dt = obj["dt"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            
            HourlyForecast(
                dateTime = dt * 1000,
                temperature = obj["temp"]?.jsonPrimitive?.floatOrNull ?: 0f,
                condition = obj["weather"]?.jsonArray?.firstOrNull()?.jsonObject?.get("main")?.jsonPrimitive?.content ?: "",
                precipProbability = obj["pop"]?.jsonPrimitive?.floatOrNull?.let { (it * 100).toInt() },
                precipAmountMm = (obj["rain"]?.jsonObject?.get("1h")?.jsonPrimitive?.floatOrNull ?: 0f) +
                                 (obj["snow"]?.jsonObject?.get("1h")?.jsonPrimitive?.floatOrNull ?: 0f),
                cloudCover = obj["clouds"]?.jsonPrimitive?.intOrNull
            )
        }

        val dailyData = root["daily"]?.jsonArray ?: emptyList()
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        val dailyForecasts = dailyData.mapNotNull { element ->
            val obj = element.jsonObject
            val dt = obj["dt"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val date = LocalDateTime.ofInstant(Instant.ofEpochSecond(dt), ZoneId.systemDefault()).toLocalDate().format(dateFormatter)
            
            DailyForecast(
                date = date,
                highTemp = obj["temp"]?.jsonObject?.get("max")?.jsonPrimitive?.floatOrNull ?: 0f,
                lowTemp = obj["temp"]?.jsonObject?.get("min")?.jsonPrimitive?.floatOrNull ?: 0f,
                condition = obj["weather"]?.jsonArray?.firstOrNull()?.jsonObject?.get("main")?.jsonPrimitive?.content ?: "",
                precipProbability = obj["pop"]?.jsonPrimitive?.floatOrNull?.let { (it * 100).toInt() },
                precipAmountMm = (obj["rain"]?.jsonPrimitive?.floatOrNull ?: 0f) +
                                 (obj["snow"]?.jsonPrimitive?.floatOrNull ?: 0f)
            )
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
