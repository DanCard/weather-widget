package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.Log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import kotlin.math.roundToInt

private const val TAG = "TomorrowIoApi"

class TomorrowIoApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val TIMELINES_URL = "https://api.tomorrow.io/v4/timelines"
        private const val REALTIME_URL = "https://api.tomorrow.io/v4/weather/realtime"
    }

    suspend fun getForecast(
        lat: Double,
        lon: Double,
    ): RawFetch {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("TOMORROW_IO_API_KEY is missing.")
        }

        val hourlyHttpResponse = httpClient.get(TIMELINES_URL) {
            parameter("location", "$lat,$lon")
            parameter("fields", "temperature,weatherCode,precipitationProbability,precipitationAccumulation,cloudCover")
            parameter("timesteps", "1h")
            parameter("units", "imperial")
            parameter("apikey", apiKey)
            // Reaches back far enough to cover the elapsed part of the local day, so a site being
            // fetched for the FIRST time still gets today's overnight minimum rather than only the
            // hours since it was promoted. Callers persist the elapsed slice with distinct
            // RECENT_HISTORY provenance.
            //
            // This was `nowMinus6h`, annotated "core temperature/cloud fields are available six
            // hours into the past on the free plan". That claim does not hold. Probed 2026-08-22
            // at 37.4168,-122.0890 with this exact field list: `nowMinus23h` returned HTTP 200 and
            // all five fields — temperature, cloudCover, weatherCode, precipitationProbability,
            // precipitationAccumulation — were non-null in all 24 elapsed intervals, earliest
            // 21:00 the previous day. The 6 h window returned nothing before 14:00 local.
            //
            // 6 h was survivable only because a stationary device accumulates coverage across ~12
            // fetches a day; it collapsed the moment a GPS excursion created a fresh site, whose
            // day then began at noon and whose "low" was the noon reading (Samsung 2026-08-22).
            //
            // 23 h, not 24: the plan rejects startTime more than 24 h in the past (403, code
            // 403003).
            parameter("startTime", "nowMinus23h")
        }
        if (hourlyHttpResponse.status.value !in 200..299) {
            val errorBody = runCatching { hourlyHttpResponse.bodyAsText() }.getOrDefault("No error body")
            throw ApiAccessException(
                source = WeatherSource.TOMORROW_IO,
                statusCode = hourlyHttpResponse.status.value,
                detail = errorBody,
                message = "Tomorrow.io hourly fetch failed: status ${hourlyHttpResponse.status.value}. Detail: $errorBody"
            )
        }
        val hourlyResponse: String = hourlyHttpResponse.body()

        val dailyHttpResponse = httpClient.get(TIMELINES_URL) {
            parameter("location", "$lat,$lon")
            parameter("fields", "temperatureMax,temperatureMin,weatherCode,precipitationProbability,precipitationAccumulation")
            parameter("timesteps", "1d")
            parameter("units", "imperial")
            parameter("apikey", apiKey)
        }
        if (dailyHttpResponse.status.value !in 200..299) {
            val errorBody = runCatching { dailyHttpResponse.bodyAsText() }.getOrDefault("No error body")
            throw ApiAccessException(
                source = WeatherSource.TOMORROW_IO,
                statusCode = dailyHttpResponse.status.value,
                detail = errorBody,
                message = "Tomorrow.io daily fetch failed: status ${dailyHttpResponse.status.value}. Detail: $errorBody"
            )
        }
        val dailyResponse: String = dailyHttpResponse.body()

        val hourlyJson = json.parseToJsonElement(hourlyResponse).jsonObject
        val dailyJson = json.parseToJsonElement(dailyResponse).jsonObject

        val hourlyIntervals = hourlyJson["data"]?.jsonObject?.get("timelines")?.jsonArray?.get(0)?.jsonObject?.get("intervals")?.jsonArray ?: JsonArray(emptyList())
        val dailyIntervals = dailyJson["data"]?.jsonObject?.get("timelines")?.jsonArray?.get(0)?.jsonObject?.get("intervals")?.jsonArray ?: JsonArray(emptyList())

        val hourlyForecasts = hourlyIntervals.mapIndexedNotNull { _, element ->
            val obj = element.jsonObject
            val startTime = obj["startTime"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
            val values = obj["values"]?.jsonObject ?: return@mapIndexedNotNull null

            val epochMs = OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
            val temp = values["temperature"]?.jsonPrimitive?.floatOrNull ?: Float.NaN
            val code = values["weatherCode"]?.jsonPrimitive?.intOrNull ?: 1000
            val precipProb = values["precipitationProbability"]?.jsonPrimitive?.intOrNull
            val precipAccumIn = values["precipitationAccumulation"]?.jsonPrimitive?.floatOrNull

            HourlyForecast(
                dateTime = epochMs,
                temperature = temp,
                condition = weatherCodeToCondition(code),
                precipProbability = precipProb,
                precipAmountMm = precipAccumIn?.let { it * 25.4f },
                cloudCover = values["cloudCover"]?.jsonPrimitive?.floatOrNull?.roundToInt()
            )
        }

        val dailyForecasts = dailyIntervals.mapIndexedNotNull { _, element ->
            val obj = element.jsonObject
            val startTime = obj["startTime"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
            val values = obj["values"]?.jsonObject ?: return@mapIndexedNotNull null

            val date = startTime.substring(0, 10)
            val high = values["temperatureMax"]?.jsonPrimitive?.floatOrNull ?: Float.NaN
            val low = values["temperatureMin"]?.jsonPrimitive?.floatOrNull ?: Float.NaN
            val code = values["weatherCode"]?.jsonPrimitive?.intOrNull ?: 1000
            val precipProb = values["precipitationProbability"]?.jsonPrimitive?.intOrNull
            val precipAccumIn = values["precipitationAccumulation"]?.jsonPrimitive?.floatOrNull

            DailyForecast(
                date = date,
                highTemp = high,
                lowTemp = low,
                condition = weatherCodeToCondition(code),
                iconToken = code.toString(),
                precipProbability = precipProb,
                precipAmountMm = precipAccumIn?.let { it * 25.4f }
            )
        }

        return RawFetch(
            daily = dailyForecasts,
            hourly = hourlyForecasts
        )
    }

    /** Current source-native conditions. Callers accumulate these samples as honest actuals. */
    suspend fun getRealtime(lat: Double, lon: Double): TomorrowIoRealtimeReading? {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("TOMORROW_IO_API_KEY is missing.")
        }

        val response = httpClient.get(REALTIME_URL) {
            parameter("location", "$lat,$lon")
            parameter("units", "imperial")
            parameter("apikey", apiKey)
        }
        if (response.status.value !in 200..299) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("No error body")
            throw ApiAccessException(
                source = WeatherSource.TOMORROW_IO,
                statusCode = response.status.value,
                detail = errorBody,
                message = "Tomorrow.io realtime fetch failed: status ${response.status.value}. Detail: $errorBody",
            )
        }

        val root = json.parseToJsonElement(response.body<String>()).jsonObject
        val data = root["data"]?.jsonObject ?: return null
        val values = data["values"]?.jsonObject ?: return null
        val temperature = values["temperature"]?.jsonPrimitive?.floatOrNull
            ?.takeIf { it.isFinite() }
            ?: return null
        val observedAt = data["time"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            ?: return null
        val weatherCode = values["weatherCode"]?.jsonPrimitive?.intOrNull

        return TomorrowIoRealtimeReading(
            temperature = temperature,
            condition = weatherCode?.let(::weatherCodeToCondition) ?: "Unknown",
            observedAt = observedAt,
            cloudCover = values["cloudCover"]?.jsonPrimitive?.floatOrNull
                ?.roundToInt()
                ?.coerceIn(0, 100),
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
}

data class TomorrowIoRealtimeReading(
    val temperature: Float,
    val condition: String,
    val observedAt: Long,
    val cloudCover: Int?,
)
