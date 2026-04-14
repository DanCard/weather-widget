package com.weatherwidget.data.remote

import android.util.Log
import com.weatherwidget.BuildConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.roundToInt

private const val TAG = "TomorrowIoApi"

class TomorrowIoApi
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
    ) {
        companion object {
            private const val BASE_URL = "https://api.tomorrow.io/v4/timelines"
        }

        suspend fun getForecast(
            lat: Double,
            lon: Double,
        ): WeatherForecast {
            val apiKey = BuildConfig.TOMORROW_IO_API_KEY
            if (apiKey.isBlank()) {
                throw IllegalStateException("TOMORROW_IO_API_KEY is missing.")
            }

            // Fetch hourly (1h) and daily (1d) in one or two calls. 
            // Tomorrow.io allows multiple timesteps in some plans, but let's stick to two separate requests for clarity if needed, 
            // or one request if we can get both. v4 timelines usually takes one timestep per request.
            
            val hourlyResponse: String = httpClient.get(BASE_URL) {
                parameter("location", "$lat,$lon")
                parameter("fields", "temperature,weatherCode,precipitationProbability,precipitationIntensity,cloudCover")
                parameter("timesteps", "1h")
                parameter("units", "imperial")
                parameter("apikey", apiKey)
            }.body()

            val dailyResponse: String = httpClient.get(BASE_URL) {
                parameter("location", "$lat,$lon")
                parameter("fields", "temperatureMax,temperatureMin,weatherCode,precipitationProbability,precipitationIntensity")
                parameter("timesteps", "1d")
                parameter("units", "imperial")
                parameter("apikey", apiKey)
            }.body()

            val hourlyJson = json.parseToJsonElement(hourlyResponse).jsonObject
            val dailyJson = json.parseToJsonElement(dailyResponse).jsonObject

            val hourlyIntervals = hourlyJson["data"]?.jsonObject?.get("timelines")?.jsonArray?.get(0)?.jsonObject?.get("intervals")?.jsonArray ?: JsonArray(emptyList())
            val dailyIntervals = dailyJson["data"]?.jsonObject?.get("timelines")?.jsonArray?.get(0)?.jsonObject?.get("intervals")?.jsonArray ?: JsonArray(emptyList())

            val hourlyForecasts = hourlyIntervals.mapIndexedNotNull { _, element ->
                val obj = element.jsonObject
                val startTime = obj["startTime"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                val values = obj["values"]?.jsonObject ?: return@mapIndexedNotNull null
                
                val epochMs = OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
                val temp = values["temperature"]?.jsonPrimitive?.floatOrNull ?: 0f
                val code = values["weatherCode"]?.jsonPrimitive?.intOrNull ?: 1000
                val precipProb = values["precipitationProbability"]?.jsonPrimitive?.intOrNull
                val precipIntensity = values["precipitationIntensity"]?.jsonPrimitive?.floatOrNull // Tomorrow.io returns mm/hr by default but we requested imperial? 
                // Wait, Tomorrow.io units=imperial returns temperature in F, but precip intensity in inches/hr.
                
                HourlyForecast(
                    dateTime = epochMs,
                    temperature = temp,
                    weatherCode = code,
                    precipProbability = precipProb,
                    precipAmountMm = precipIntensity?.let { it * 25.4f }, // Convert inches to mm
                    cloudCover = values["cloudCover"]?.jsonPrimitive?.floatOrNull?.roundToInt()
                )
            }

            val dailyForecasts = dailyIntervals.mapIndexedNotNull { _, element ->
                val obj = element.jsonObject
                val startTime = obj["startTime"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                val values = obj["values"]?.jsonObject ?: return@mapIndexedNotNull null
                
                val date = startTime.substring(0, 10) // YYYY-MM-DD
                val high = values["temperatureMax"]?.jsonPrimitive?.floatOrNull ?: 0f
                val low = values["temperatureMin"]?.jsonPrimitive?.floatOrNull ?: 0f
                val code = values["weatherCode"]?.jsonPrimitive?.intOrNull ?: 1000
                val precipProb = values["precipitationProbability"]?.jsonPrimitive?.intOrNull
                val precipIntensity = values["precipitationIntensity"]?.jsonPrimitive?.floatOrNull
                
                DailyForecast(
                    date = date,
                    highTemp = high,
                    lowTemp = low,
                    weatherCode = code,
                    precipProbability = precipProb,
                    precipAmountMm = precipIntensity?.let { it * 25.4f }
                )
            }

            // For current temp, we can use the first hourly interval
            val currentInterval = hourlyIntervals.firstOrNull()?.jsonObject
            val currentValues = currentInterval?.get("values")?.jsonObject
            val currentTemp = currentValues?.get("temperature")?.jsonPrimitive?.floatOrNull
            val currentWeatherCode = currentValues?.get("weatherCode")?.jsonPrimitive?.intOrNull
            val currentObservedAt = currentInterval?.get("startTime")?.jsonPrimitive?.content?.let {
                OffsetDateTime.parse(it).toInstant().toEpochMilli()
            }

            return WeatherForecast(
                currentTemp = currentTemp,
                currentWeatherCode = currentWeatherCode,
                currentObservedAt = currentObservedAt,
                daily = dailyForecasts,
                hourly = hourlyForecasts
            )
        }

        fun weatherCodeToCondition(code: Int): String =
            when (code) {
                1000 -> "Clear"
                1100 -> "Mostly Clear"
                1101 -> "Partly Cloudy"
                1102 -> "Mostly Cloudy"
                1001 -> "Cloudy"
                2000, 2100 -> "Fog"
                4000 -> "Drizzle"
                4001, 4200 -> "Rain"
                4201 -> "Heavy Rain"
                5000, 5001, 5100, 5101 -> "Snow"
                6000, 6001, 6200, 6201 -> "Freezing Rain"
                7000, 7101, 7102 -> "Ice Pellets"
                8000 -> "Thunderstorm"
                else -> "Unknown"
            }

        data class WeatherForecast(
            val currentTemp: Float?,
            val currentWeatherCode: Int?,
            val currentObservedAt: Long? = null,
            val daily: List<DailyForecast>,
            val hourly: List<HourlyForecast> = emptyList(),
        )

        data class DailyForecast(
            val date: String,
            val highTemp: Float,
            val lowTemp: Float,
            val weatherCode: Int,
            val precipProbability: Int? = null,
            val precipAmountMm: Float? = null,
        )

        data class HourlyForecast(
            val dateTime: Long, // Epoch ms
            val temperature: Float,
            val weatherCode: Int,
            val precipProbability: Int? = null,
            val precipAmountMm: Float? = null,
            val cloudCover: Int? = null,
        )

        data class CurrentReading(
            val temperature: Float,
            val weatherCode: Int?,
            val observedAt: Long? = null,
        )
    }
