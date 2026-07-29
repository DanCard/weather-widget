package com.weatherwidget.data.remote

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

class WeatherApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val FORECAST_URL = "https://api.weatherapi.com/v1/forecast.json"
        private const val HISTORY_URL = "https://api.weatherapi.com/v1/history.json"
    }

    suspend fun getForecast(
        lat: Double,
        lon: Double,
        days: Int = 14,
    ): ForecastResult {
        val apiKey = requireApiKey()

        val response: HttpResponse = httpClient.get(FORECAST_URL) {
            parameter("key", apiKey)
            parameter("q", "$lat,$lon")
            parameter("days", days.coerceIn(1, 14))
            parameter("aqi", "no")
            parameter("alerts", "no")
        }
        return parseResponse(requireSuccess(response, "forecast", apiKey))
    }

    /**
     * Fetches one local calendar day from WeatherAPI's History endpoint.
     *
     * One date per request intentionally works with the bundled Free-plan credential and avoids
     * `end_dt`, which WeatherAPI reserves for paid plans.
     */
    suspend fun getHistory(
        lat: Double,
        lon: Double,
        date: LocalDate,
    ): ForecastResult {
        val apiKey = requireApiKey()
        val response = httpClient.get(HISTORY_URL) {
            parameter("key", apiKey)
            parameter("q", "$lat,$lon")
            parameter("dt", date.toString())
        }
        return parseResponse(requireSuccess(response, "history", apiKey))
    }

    private fun requireApiKey(): String =
        apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("WEATHER_API_KEY is missing.")

    private suspend fun requireSuccess(
        response: HttpResponse,
        operation: String,
        apiKey: String,
    ): String {
        if (response.status.value !in 200..299) {
            val errorBody =
                runCatching { response.bodyAsText() }
                    .getOrDefault("No error body")
                    .replace(apiKey, "[redacted]")
            throw ApiAccessException(
                source = WeatherSource.WEATHER_API,
                statusCode = response.status.value,
                detail = errorBody,
                message =
                    "WeatherAPI $operation failed: status ${response.status.value}. " +
                        "Detail: $errorBody",
            )
        }
        return response.bodyAsText()
    }

    private fun parseResponse(responseBody: String): ForecastResult {
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
