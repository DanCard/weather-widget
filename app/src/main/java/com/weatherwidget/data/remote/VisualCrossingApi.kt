package com.weatherwidget.data.remote

import com.weatherwidget.BuildConfig
import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class VisualCrossingApi
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
        private val apiKey: String = BuildConfig.VISUAL_CROSSING_API_KEY,
    ) {
        companion object {
            private const val BASE_URL =
                "https://weather.visualcrossing.com/VisualCrossingWebServices/rest/services/timeline"
        }

        class VisualCrossingAccessException(
            statusCode: Int? = null,
            detail: String,
            message: String,
        ) : ApiAccessException(WeatherSource.VISUAL_CROSSING, statusCode, detail, message)

        suspend fun getForecast(
            lat: Double,
            lon: Double,
            days: Int = 14,
        ): ForecastResult {
            requireApiKey()

            val startDate = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val endDate = LocalDate.now().plusDays(days.toLong() - 1).format(DateTimeFormatter.ISO_LOCAL_DATE)

            val response: String =
                httpClient.get("$BASE_URL/$lat,$lon/$startDate/$endDate") {
                    parameter("unitGroup", "us")
                    parameter("include", "current,days,hours")
                    parameter("key", apiKey)
                    parameter("contentType", "json")
                }.body()

            val root = json.parseToJsonElement(response).jsonObject
            throwIfErrorResponse(root)

            val current = root["currentConditions"]?.jsonObject
            val daily =
                root["days"]?.jsonArray
                    ?.mapNotNull { parseDaily(it.jsonObject) }
                    ?.take(days)
                    ?: emptyList()
            val hourly =
                root["days"]?.jsonArray
                    ?.flatMap { day ->
                        day.jsonObject["hours"]?.jsonArray?.mapNotNull { parseHourly(it.jsonObject) } ?: emptyList()
                    } ?: emptyList()

            return ForecastResult(
                currentTemp = current?.get("temp")?.jsonPrimitive?.floatOrNull,
                currentCondition = current?.let { it.primaryCondition() },
                currentObservedAt = current?.toObservedAtMs(),
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
                httpClient.get("$BASE_URL/$lat,$lon/today") {
                    parameter("unitGroup", "us")
                    parameter("include", "current,hours")
                    parameter("key", apiKey)
                    parameter("contentType", "json")
                }.body()

            val root = json.parseToJsonElement(response).jsonObject
            throwIfErrorResponse(root)
            val current = root["currentConditions"]?.jsonObject
            if (current != null) {
                val temperature = current["temp"]?.jsonPrimitive?.floatOrNull ?: return null
                return CurrentReading(
                    temperature = temperature,
                    condition = current.primaryCondition(),
                    observedAt = current.toObservedAtMs(),
                )
            }

            val firstHour =
                root["days"]?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("hours")
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?: return null

            return parseHourly(firstHour)?.let {
                CurrentReading(it.temperature, it.condition, it.dateTime)
            }
        }

        private fun requireApiKey() {
            if (apiKey.isBlank()) {
                throw VisualCrossingAccessException(
                    detail = "Visual Crossing API key missing. Add VISUAL_CROSSING_API_KEY to local.properties or the environment.",
                    message = "Visual Crossing API key missing. Add VISUAL_CROSSING_API_KEY to local.properties or the environment.",
                )
            }
        }

        private fun throwIfErrorResponse(root: JsonObject) {
            val message =
                root["message"]?.jsonPrimitive?.contentOrNull?.replace(Regex("\\s+"), " ")?.trim()
                    ?: return
            val statusCode =
                root["status"]?.jsonPrimitive?.intOrNull
                    ?: root["statusCode"]?.jsonPrimitive?.intOrNull
            val normalized = message.lowercase()
            val summary =
                when {
                    statusCode == 401 || normalized.contains("invalid api key") || normalized.contains("unauthorized") ->
                        "API key invalid or unauthorized."
                    normalized.contains("quota") || normalized.contains("limit") ->
                        "Rate limited."
                    else -> "Request failed."
                }
            val userMessage =
                when (statusCode) {
                    401 -> "${WeatherSource.VISUAL_CROSSING.displayName} 401 error. $summary"
                    429 -> "${WeatherSource.VISUAL_CROSSING.displayName} rate limited. $summary"
                    else -> "${WeatherSource.VISUAL_CROSSING.displayName} request failed. $summary"
                }
            throw VisualCrossingAccessException(statusCode = statusCode, detail = message, message = userMessage)
        }

        private fun parseDaily(day: JsonObject): DailyForecast? {
            val date = day["datetime"]?.jsonPrimitive?.content ?: return null
            val high = day["tempmax"]?.jsonPrimitive?.floatOrNull ?: return null
            val low = day["tempmin"]?.jsonPrimitive?.floatOrNull ?: return null
            return DailyForecast(
                date = date,
                highTemp = high,
                lowTemp = low,
                condition = day.primaryCondition() ?: "Unknown",
                iconToken = day["icon"]?.jsonPrimitive?.contentOrNull,
                precipProbability = day["precipprob"]?.jsonPrimitive?.floatOrNull?.toInt(),
                precipAmountMm = day["precip"]?.jsonPrimitive?.floatOrNull?.times(25.4f),
            )
        }

        private fun parseHourly(hour: JsonObject): HourlyForecast? {
            val dateTime = hour.toObservedAtMs() ?: return null
            val temp = hour["temp"]?.jsonPrimitive?.floatOrNull ?: return null
            return HourlyForecast(
                dateTime = dateTime,
                temperature = temp,
                condition = hour.primaryCondition() ?: "Unknown",
                precipProbability = hour["precipprob"]?.jsonPrimitive?.floatOrNull?.toInt(),
                precipAmountMm = hour["precip"]?.jsonPrimitive?.floatOrNull?.times(25.4f),
                cloudCover = hour["cloudcover"]?.jsonPrimitive?.floatOrNull?.toInt(),
            )
        }

        private fun JsonObject.primaryCondition(): String? =
            this["conditions"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: this["icon"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.replace('-', ' ')
                    ?.split(' ')
                    ?.joinToString(" ") { word ->
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }

        private fun JsonObject.toObservedAtMs(): Long? {
            val epochSeconds = this["datetimeEpoch"]?.jsonPrimitive?.longOrNull
            return epochSeconds?.times(1000)
        }

        data class CurrentReading(
            val temperature: Float,
            val condition: String?,
            val observedAt: Long?,
        )
    }
