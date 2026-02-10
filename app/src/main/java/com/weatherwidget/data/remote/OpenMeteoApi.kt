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
import kotlin.math.roundToInt

private const val TAG = "OpenMeteoApi"

class OpenMeteoApi @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    companion object {
        private const val BASE_URL = "https://api.open-meteo.com/v1"
        private const val CLIMATE_URL = "https://climate-api.open-meteo.com/v1"
    }

    suspend fun getForecast(lat: Double, lon: Double, days: Int = 7): WeatherForecast {
        val response: String = httpClient.get("$BASE_URL/forecast") {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("daily", "temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max")
            parameter("hourly", "temperature_2m,weather_code,precipitation_probability")
            parameter("current", "temperature_2m,weather_code")
            parameter("temperature_unit", "fahrenheit")
            parameter("timezone", "auto")
            parameter("past_days", 7)  // Fetch last 7 days of historical data
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
            it.jsonPrimitive.content.toDoubleOrNull()?.roundToInt() ?: 0
        } ?: emptyList()
        val minTemps = daily?.get("temperature_2m_min")?.jsonArray?.map {
            it.jsonPrimitive.content.toDoubleOrNull()?.roundToInt() ?: 0
        } ?: emptyList()
        val weatherCodes = daily?.get("weather_code")?.jsonArray?.map {
            it.jsonPrimitive.content.toIntOrNull() ?: 0
        } ?: emptyList()
        val precipProbs = daily?.get("precipitation_probability_max")?.jsonArray?.map {
            it.jsonPrimitive.content.toIntOrNull()
        } ?: emptyList()

        val dailyForecasts = dates.mapIndexed { index, date ->
            DailyForecast(
                date = date,
                highTemp = maxTemps.getOrNull(index) ?: 0,
                lowTemp = minTemps.getOrNull(index) ?: 0,
                weatherCode = weatherCodes.getOrNull(index) ?: 0,
                precipProbability = precipProbs.getOrNull(index)
            )
        }

        // Parse hourly data
        val hourly = jsonObj["hourly"]?.jsonObject
        val hourlyTimes = hourly?.get("time")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val hourlyTemps = hourly?.get("temperature_2m")?.jsonArray?.map {
            it.jsonPrimitive.content.toDoubleOrNull()?.toFloat()
        } ?: emptyList()
        val hourlyCodes = hourly?.get("weather_code")?.jsonArray?.map {
            it.jsonPrimitive.content.toIntOrNull() ?: 0
        } ?: emptyList()
        val hourlyPrecipProbs = hourly?.get("precipitation_probability")?.jsonArray?.map {
            it.jsonPrimitive.content.toIntOrNull()
        } ?: emptyList()

        val hourlyForecasts = hourlyTimes.mapIndexedNotNull { index, time ->
            val temp = hourlyTemps.getOrNull(index)
            val code = hourlyCodes.getOrNull(index) ?: 0
            if (temp != null) {
                HourlyForecast(
                    dateTime = time,
                    temperature = temp,
                    weatherCode = code,
                    precipProbability = hourlyPrecipProbs.getOrNull(index)
                )
            } else null
        }

        Log.d(TAG, "getForecast: parsed ${hourlyForecasts.size} hourly forecasts")

        return WeatherForecast(
            currentTemp = current?.get("temperature_2m")?.jsonPrimitive?.content?.toDoubleOrNull()?.roundToInt(),
            currentWeatherCode = current?.get("weather_code")?.jsonPrimitive?.content?.toIntOrNull(),
            daily = dailyForecasts,
            hourly = hourlyForecasts
        )
    }

    suspend fun getClimateForecast(lat: Double, lon: Double, startDate: String, endDate: String): List<DailyForecast> {
        val response: String = httpClient.get("$CLIMATE_URL/climate") {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("start_date", startDate)
            parameter("end_date", endDate)
            parameter("daily", "temperature_2m_max,temperature_2m_min")
            parameter("temperature_unit", "fahrenheit")
        }.body()

        Log.d(TAG, "getClimateForecast: Raw response length=${response.length}")
        val jsonObj = json.parseToJsonElement(response).jsonObject
        val daily = jsonObj["daily"]?.jsonObject

        val dates = daily?.get("time")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val maxTemps = daily?.get("temperature_2m_max")?.jsonArray?.map {
            it.jsonPrimitive.content.toDoubleOrNull()?.roundToInt() ?: 0
        } ?: emptyList()
        val minTemps = daily?.get("temperature_2m_min")?.jsonArray?.map {
            it.jsonPrimitive.content.toDoubleOrNull()?.roundToInt() ?: 0
        } ?: emptyList()

        return dates.mapIndexed { index, date ->
            DailyForecast(
                date = date,
                highTemp = maxTemps.getOrNull(index) ?: 0,
                lowTemp = minTemps.getOrNull(index) ?: 0,
                weatherCode = 0 // Climate API doesn't usually return weather code, default to Clear
            )
        }
    }

    fun weatherCodeToCondition(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Overcast"
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
        val daily: List<DailyForecast>,
        val hourly: List<HourlyForecast> = emptyList()
    )

    data class DailyForecast(
        val date: String,
        val highTemp: Int,
        val lowTemp: Int,
        val weatherCode: Int,
        val precipProbability: Int? = null
    )

    data class HourlyForecast(
        val dateTime: String,  // ISO 8601 format: "2024-01-15T14:00"
        val temperature: Float,
        val weatherCode: Int,
        val precipProbability: Int? = null
    )
}
