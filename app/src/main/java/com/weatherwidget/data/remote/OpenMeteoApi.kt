package com.weatherwidget.data.remote

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "OpenMeteoApi"

class OpenMeteoApi @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    companion object {
        private const val BASE_URL = "https://api.open-meteo.com/v1"
    }

    suspend fun getForecast(lat: Double, lon: Double, days: Int = 7): WeatherForecast {
        val response: String = httpClient.get("$BASE_URL/forecast") {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("daily", "temperature_2m_max,temperature_2m_min,weather_code")
            parameter("current", "temperature_2m,weather_code")
            parameter("temperature_unit", "fahrenheit")
            parameter("timezone", "auto")
            parameter("past_days", 1)
            parameter("forecast_days", days)
        }.body()

        Log.d(TAG, "getForecast: Raw response length=${response.length}")
        val jsonObj = json.parseToJsonElement(response).jsonObject

        val current = jsonObj["current"]?.jsonObject
        val daily = jsonObj["daily"]?.jsonObject
        Log.d(TAG, "getForecast: daily object keys=${daily?.keys}")

        val dates = daily?.get("time")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        Log.d(TAG, "getForecast: parsed ${dates.size} dates: $dates")
        val maxTemps = daily?.get("temperature_2m_max")?.jsonArray?.map {
            it.jsonPrimitive.content.toDoubleOrNull()?.toInt() ?: 0
        } ?: emptyList()
        val minTemps = daily?.get("temperature_2m_min")?.jsonArray?.map {
            it.jsonPrimitive.content.toDoubleOrNull()?.toInt() ?: 0
        } ?: emptyList()
        val weatherCodes = daily?.get("weather_code")?.jsonArray?.map {
            it.jsonPrimitive.content.toIntOrNull() ?: 0
        } ?: emptyList()

        val dailyForecasts = dates.mapIndexed { index, date ->
            DailyForecast(
                date = date,
                highTemp = maxTemps.getOrNull(index) ?: 0,
                lowTemp = minTemps.getOrNull(index) ?: 0,
                weatherCode = weatherCodes.getOrNull(index) ?: 0
            )
        }

        return WeatherForecast(
            currentTemp = current?.get("temperature_2m")?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt(),
            currentWeatherCode = current?.get("weather_code")?.jsonPrimitive?.content?.toIntOrNull(),
            daily = dailyForecasts
        )
    }

    fun weatherCodeToCondition(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2, 3 -> "Partly Cloudy"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow Grains"
        80, 81, 82 -> "Rain Showers"
        85, 86 -> "Snow Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unknown"
    }

    data class WeatherForecast(
        val currentTemp: Int?,
        val currentWeatherCode: Int?,
        val daily: List<DailyForecast>
    )

    data class DailyForecast(
        val date: String,
        val highTemp: Int,
        val lowTemp: Int,
        val weatherCode: Int
    )
}
