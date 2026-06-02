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
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "SilurianApi"

class SilurianApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val BASE_URL = "https://api.silurian.ai/v1/forecast"
    }

    suspend fun getForecast(
        lat: Double,
        lon: Double,
    ): ForecastResult {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("SILURIAN_API_KEY is missing.")
        }

        val response: HttpResponse = httpClient.get(BASE_URL) {
            header("Authorization", "Bearer $apiKey")
            parameter("lat", lat)
            parameter("lon", lon)
        }

        if (response.status.value !in 200..299) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("No error body")
            throw ApiAccessException(
                source = WeatherSource.SILURIAN,
                statusCode = response.status.value,
                detail = errorBody,
                message = "Silurian fetch failed: status ${response.status.value}. Detail: $errorBody"
            )
        }

        val responseBody: String = response.body()
        val root = json.parseToJsonElement(responseBody).jsonObject

        val hourlyData = root["hourly"]?.jsonArray ?: emptyList()
        val hourlyForecasts = hourlyData.mapNotNull { element ->
            val obj = element.jsonObject
            val time = obj["time"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val epochMs = OffsetDateTime.parse(time).toInstant().toEpochMilli()
            
            HourlyForecast(
                dateTime = epochMs,
                temperature = obj["temp"]?.jsonPrimitive?.floatOrNull ?: 0f,
                condition = obj["condition"]?.jsonPrimitive?.content ?: "",
                precipProbability = obj["precip_prob"]?.jsonPrimitive?.intOrNull,
                precipAmountMm = obj["precip_amount"]?.jsonPrimitive?.floatOrNull,
                cloudCover = obj["cloud_cover"]?.jsonPrimitive?.intOrNull
            )
        }

        val dailyData = root["daily"]?.jsonArray ?: emptyList()
        val dailyForecasts = dailyData.mapNotNull { element ->
            val obj = element.jsonObject
            val date = obj["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
            
            DailyForecast(
                date = date,
                highTemp = obj["temp_max"]?.jsonPrimitive?.floatOrNull ?: 0f,
                lowTemp = obj["temp_min"]?.jsonPrimitive?.floatOrNull ?: 0f,
                condition = obj["condition"]?.jsonPrimitive?.content ?: "",
                precipProbability = obj["precip_prob"]?.jsonPrimitive?.intOrNull,
                precipAmountMm = obj["precip_amount"]?.jsonPrimitive?.floatOrNull
            )
        }

        val currentObj = root["current"]?.jsonObject
        val currentTemp = currentObj?.get("temp")?.jsonPrimitive?.floatOrNull
        val currentCondition = currentObj?.get("condition")?.jsonPrimitive?.content
        val currentObservedAt = currentObj?.get("time")?.jsonPrimitive?.content?.let {
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
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
