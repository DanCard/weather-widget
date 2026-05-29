package com.weatherwidget.data.remote

import android.util.Log
import com.weatherwidget.BuildConfig
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
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
        private val widgetStateManager: WidgetStateManager,
    ) {
        private val apiKey: String
            get() = widgetStateManager.getApiKey(WeatherSource.OPEN_WEATHER_MAP) ?: BuildConfig.OPEN_WEATHER_MAP_API_KEY

        companion object {
            private const val BASE_URL = "https://api.openweathermap.org/data/3.0"
        }

        class OpenWeatherMapAccessException(
            val reason: FailureReason,
            statusCode: Int? = null,
            detail: String,
            message: String,
        ) : ApiAccessException(WeatherSource.OPEN_WEATHER_MAP, statusCode, detail, message)

        enum class FailureReason {
            MISSING_KEY,
            INVALID_KEY,
            SUBSCRIPTION_REQUIRED,
            RATE_LIMITED,
            REMOTE_ERROR,
        }

        suspend fun getForecast(
            lat: Double,
            lon: Double,
            days: Int = 8,
        ): ForecastResult {
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
            throwIfErrorResponse(jsonObj)
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

return ForecastResult(
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
            throwIfErrorResponse(jsonObj)
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
                throw OpenWeatherMapAccessException(
                    FailureReason.MISSING_KEY,
                    statusCode = null,
                    detail = "OpenWeatherMap API key missing. Add OPEN_WEATHER_MAP_API_KEY to local.properties or the environment.",
                    message = "OpenWeatherMap API key missing. Add OPEN_WEATHER_MAP_API_KEY to local.properties or the environment.",
                )
            }
        }

        private fun throwIfErrorResponse(jsonObj: JsonObject) {
            val code = jsonObj["cod"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            if (code.isNullOrBlank()) return

            val message = jsonObj["message"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
            val normalizedMessage = message.lowercase()
            val reason =
                when {
                    normalizedMessage.contains("separate subscription") ||
                        normalizedMessage.contains("one call by call plan") ->
                        FailureReason.SUBSCRIPTION_REQUIRED
                    code == "401" || normalizedMessage.contains("invalid api key") || normalizedMessage.contains("unauthorized") ->
                        FailureReason.INVALID_KEY
                    code == "429" || normalizedMessage.contains("limit") ->
                        FailureReason.RATE_LIMITED
                    else -> FailureReason.REMOTE_ERROR
                }
            val summaryDetail =
                when (reason) {
                    FailureReason.SUBSCRIPTION_REQUIRED ->
                        "One Call 3.0 subscription required."
                    FailureReason.INVALID_KEY ->
                        "API key invalid or unauthorized."
                    FailureReason.RATE_LIMITED ->
                        "Rate limited."
                    FailureReason.REMOTE_ERROR ->
                        if (message.isNotBlank()) message else "Request failed."
                    FailureReason.MISSING_KEY ->
                        "API key missing."
                }
            val detail =
                when (reason) {
                    FailureReason.SUBSCRIPTION_REQUIRED,
                    FailureReason.INVALID_KEY,
                    FailureReason.REMOTE_ERROR,
                    FailureReason.RATE_LIMITED -> message.ifBlank { summaryDetail }
                    FailureReason.MISSING_KEY -> summaryDetail
                }
            val statusCode = code.toIntOrNull()
            val userMessage =
                when (statusCode) {
                    401 -> "${WeatherSource.OPEN_WEATHER_MAP.displayName} 401 error. $summaryDetail"
                    429 -> "${WeatherSource.OPEN_WEATHER_MAP.displayName} rate limited. $summaryDetail"
                    else -> "${WeatherSource.OPEN_WEATHER_MAP.displayName} request failed. $summaryDetail"
                }
            throw OpenWeatherMapAccessException(reason, statusCode, detail, userMessage)
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
      iconToken = dayObj.primaryWeatherIconToken(),
      precipProbability = precipitationProbability,
      precipAmountMm = dayObj.totalPrecipitationMm(),
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

        private fun JsonObject.primaryWeatherIconToken(): String? =
            this["weather"]?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("icon")
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.isNotBlank() }

        private fun JsonObject.totalPrecipitationMm(): Float? {
            val rain = this["rain"]?.jsonPrimitive?.content?.toFloatOrNull()
            val snow = this["snow"]?.jsonPrimitive?.content?.toFloatOrNull()
            return listOfNotNull(rain, snow).takeIf { it.isNotEmpty() }?.sum()
        }

data class CurrentReading(
    val temperature: Float,
    val condition: String?,
    val observedAt: Long? = null,
  )
}
