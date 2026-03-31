package com.weatherwidget.data.remote

import android.util.Log
import com.weatherwidget.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject

private const val TAG = "OpenWeatherMapApi"

class OpenWeatherMapApi
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
        private val apiKey: String = BuildConfig.OPEN_WEATHER_MAP_API_KEY,
    ) {
        companion object {
            private const val BASE_URL = "https://api.openweathermap.org/data/3.0"
        }

        suspend fun getForecast(
            lat: Double,
            lon: Double,
            days: Int = 8,
        ): WeatherForecast {
            requireApiKey()

            val response: String =
                httpClient.get("$BASE_URL/onecall") {
                    parameter("lat", lat)
                    parameter("lon", lon)
                    parameter("appid", apiKey)
                    parameter("units", "imperial")
                    parameter("exclude", "minutely,alerts")
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val timezoneOffsetSeconds = jsonObj["timezone_offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val zoneOffset = ZoneOffset.ofTotalSeconds(timezoneOffsetSeconds)

            val current = jsonObj["current"]?.jsonObject
            val daily =
                jsonObj["daily"]?.jsonArray
                    ?.mapNotNull { parseDailyForecast(it.jsonObject, zoneOffset) }
                    ?.take(days)
                    ?: emptyList()
            val hourly =
                jsonObj["hourly"]?.jsonArray
                    ?.mapNotNull { parseHourlyForecast(it.jsonObject) }
                    ?: emptyList()

            Log.d(TAG, "getForecast: Parsed ${daily.size} daily and ${hourly.size} hourly entries")

            return WeatherForecast(
                currentTemp = current?.get("temp")?.jsonPrimitive?.content?.toFloatOrNull(),
                currentCondition = current?.primaryWeatherDescription(),
                currentObservedAt = current?.get("dt")?.jsonPrimitive?.content?.toLongOrNull()?.times(1000),
                daily = daily,
                hourly = hourly,
            )
        }

        suspend fun getCurrent(
            lat: Double,
            lon: Double,
        ): CurrentReading? {
            requireApiKey()

            val response: String =
                httpClient.get("$BASE_URL/onecall") {
                    parameter("lat", lat)
                    parameter("lon", lon)
                    parameter("appid", apiKey)
                    parameter("units", "imperial")
                    parameter("exclude", "minutely,hourly,daily,alerts")
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val current = jsonObj["current"]?.jsonObject ?: return null
            val temperature = current["temp"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null

            return CurrentReading(
                temperature = temperature,
                condition = current.primaryWeatherDescription(),
                observedAt = current["dt"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000),
            )
        }

        private fun requireApiKey() {
            if (apiKey.isBlank()) {
                throw IllegalStateException(
                    "OPEN_WEATHER_MAP_API_KEY is missing. Add it to local.properties or OPEN_WEATHER_MAP_API_KEY env var.",
                )
            }
        }

        private fun parseDailyForecast(
            dayObj: JsonObject,
            zoneOffset: ZoneOffset,
        ): DailyForecast? {
            val epochSeconds = dayObj["dt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
            val tempObj = dayObj["temp"]?.jsonObject ?: return null
            val highTemp = tempObj["max"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
            val lowTemp = tempObj["min"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
            val precipitationProbability =
                dayObj["pop"]?.jsonPrimitive?.content?.toFloatOrNull()?.times(100)?.toInt()

            return DailyForecast(
                date = Instant.ofEpochSecond(epochSeconds).atOffset(zoneOffset).toLocalDate().toString(),
                highTemp = highTemp,
                lowTemp = lowTemp,
                condition = dayObj.primaryWeatherDescription() ?: "Unknown",
                precipProbability = precipitationProbability,
                precipAmountMm = dayObj.totalPrecipitationMm(),
                cloudCover = dayObj["clouds"]?.jsonPrimitive?.content?.toIntOrNull(),
            )
        }

        private fun parseHourlyForecast(hourObj: JsonObject): HourlyForecast? {
            val epochSeconds = hourObj["dt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
            val temperature = hourObj["temp"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
            val precipitationProbability =
                hourObj["pop"]?.jsonPrimitive?.content?.toFloatOrNull()?.times(100)?.toInt()

            return HourlyForecast(
                dateTime = epochSeconds * 1000,
                temperature = temperature,
                condition = hourObj.primaryWeatherDescription() ?: "Unknown",
                precipProbability = precipitationProbability,
                precipAmountMm = hourObj.totalPrecipitationMm(),
                cloudCover = hourObj["clouds"]?.jsonPrimitive?.content?.toIntOrNull(),
            )
        }

        private fun JsonObject.primaryWeatherDescription(): String? =
            this["weather"]?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("description")
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.isNotBlank() }
                ?.split(' ')
                ?.joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }

        private fun JsonObject.totalPrecipitationMm(): Float? {
            val rain = this["rain"]?.jsonPrimitive?.content?.toFloatOrNull()
            val snow = this["snow"]?.jsonPrimitive?.content?.toFloatOrNull()
            return listOfNotNull(rain, snow).takeIf { it.isNotEmpty() }?.sum()
        }

        data class WeatherForecast(
            val currentTemp: Float?,
            val currentCondition: String?,
            val currentObservedAt: Long? = null,
            val daily: List<DailyForecast>,
            val hourly: List<HourlyForecast>,
        )

        data class DailyForecast(
            val date: String,
            val highTemp: Float,
            val lowTemp: Float,
            val condition: String,
            val precipProbability: Int? = null,
            val precipAmountMm: Float? = null,
            val cloudCover: Int? = null,
        )

        data class HourlyForecast(
            val dateTime: Long,
            val temperature: Float,
            val condition: String,
            val precipProbability: Int? = null,
            val precipAmountMm: Float? = null,
            val cloudCover: Int? = null,
        )

        data class CurrentReading(
            val temperature: Float,
            val condition: String?,
            val observedAt: Long? = null,
        )
    }
