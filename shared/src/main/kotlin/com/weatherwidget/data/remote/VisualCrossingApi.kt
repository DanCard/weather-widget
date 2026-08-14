package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.Log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val TAG = "VisualCrossingApi"

class VisualCrossingApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val BASE_URL = "https://weather.visualcrossing.com/VisualCrossingWebServices/rest/services/timeline"
    }

    suspend fun getForecast(
        lat: Double,
        lon: Double,
    ): RawFetch {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("VISUAL_CROSSING_API_KEY is missing.")
        }

        val response: HttpResponse = httpClient.get("$BASE_URL/$lat,$lon") {
            parameter("key", apiKey)
            parameter("unitGroup", "us")
            parameter("include", "days,hours,current")
            parameter("contentType", "json")
        }

        if (response.status.value !in 200..299) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("No error body")
            throw ApiAccessException(
                source = WeatherSource.VISUAL_CROSSING,
                statusCode = response.status.value,
                detail = errorBody,
                message = "Visual Crossing fetch failed: status ${response.status.value}. Detail: $errorBody"
            )
        }

        val responseBody: String = response.body()
        val root = json.parseToJsonElement(responseBody).jsonObject

        val currentObj = root["currentConditions"]?.jsonObject
        val currentTemp = currentObj?.get("temp")?.jsonPrimitive?.floatOrNull
        val currentCondition = currentObj?.get("conditions")?.jsonPrimitive?.content
        val currentObservedAt = currentObj?.get("datetimeEpoch")?.jsonPrimitive?.longOrNull?.let { it * 1000 }

        val days = root["days"]?.jsonArray ?: emptyList()
        val dailyForecasts = mutableListOf<DailyForecast>()
        val hourlyForecasts = mutableListOf<HourlyForecast>()

        for (dayElement in days) {
            val dayObj = dayElement.jsonObject
            val date = dayObj["datetime"]?.jsonPrimitive?.content ?: continue

            dailyForecasts.add(
                DailyForecast(
                    date = date,
                    highTemp = dayObj["tempmax"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    lowTemp = dayObj["tempmin"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    condition = dayObj["conditions"]?.jsonPrimitive?.content ?: "",
                    precipProbability = dayObj["precipprob"]?.jsonPrimitive?.floatOrNull?.toInt(),
                    precipAmountMm = dayObj["precip"]?.jsonPrimitive?.floatOrNull?.let { it * 25.4f }
                )
            )

            val hours = dayObj["hours"]?.jsonArray ?: continue
            for (hourElement in hours) {
                val hourObj = hourElement.jsonObject
                val timeEpoch = hourObj["datetimeEpoch"]?.jsonPrimitive?.longOrNull ?: continue
                
                hourlyForecasts.add(
                    HourlyForecast(
                        dateTime = timeEpoch * 1000,
                        temperature = hourObj["temp"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        condition = hourObj["conditions"]?.jsonPrimitive?.content ?: "",
                        precipProbability = hourObj["precipprob"]?.jsonPrimitive?.floatOrNull?.toInt(),
                        precipAmountMm = hourObj["precip"]?.jsonPrimitive?.floatOrNull?.let { it * 25.4f },
                        cloudCover = hourObj["cloudcover"]?.jsonPrimitive?.floatOrNull?.toInt()
                    )
                )
            }
        }

        return RawFetch(
            providerCurrentTemp = currentTemp,
            providerCurrentCondition = currentCondition,
            providerCurrentObservedAt = currentObservedAt,
            daily = dailyForecasts,
            hourly = hourlyForecasts
        )
    }
}
