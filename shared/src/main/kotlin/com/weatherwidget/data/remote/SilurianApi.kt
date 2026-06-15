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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val TAG = "SilurianApi"

/**
 * Silurian "API²" client (https://earth.weather.silurian.ai/api/v1).
 *
 * Replaces the retired `api.silurian.ai/v1/forecast` single-endpoint API. The current API splits
 * forecast into separate hourly and daily endpoints, authenticates via the `X-API-KEY` header, and
 * returns numbers in the requested unit system. We request `units=imperial` (°F, matching the rest
 * of the app) and `include_past=true` so the hourly response reaches back to the latest model-run
 * time, giving the graph's actual line its recent past hours. See `/api/v1/openapi.json`.
 *
 * Note: the public docs name `beta.weather.silurian.ai`, but production API keys authenticate against
 * `earth.weather.silurian.ai` (beta rejects them as "Invalid or inactive api key").
 */
class SilurianApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val BASE_URL = "https://earth.weather.silurian.ai/api/v1"
        // Imperial precip/snow accumulation is reported in inches; the app stores mm.
        private const val INCHES_TO_MM = 25.4f
    }

    suspend fun getForecast(
        lat: Double,
        lon: Double,
    ): ForecastResult = coroutineScope {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("SILURIAN_API_KEY is missing.")
        }

        // Two independent endpoints — fetch concurrently.
        val hourlyDeferred = async {
            fetchJson(apiKey, "/forecast/hourly", lat, lon) {
                parameter("include_past", true)
            }
        }
        val dailyDeferred = async {
            fetchJson(apiKey, "/forecast/daily", lat, lon)
        }

        val hourlyRoot = hourlyDeferred.await()
        val dailyRoot = dailyDeferred.await()

        val hourlyOffsetSeconds = (hourlyRoot["utc_offset"]?.jsonPrimitive?.intOrNull ?: 0)

        val hourly = (hourlyRoot["hourly"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val obj = el.jsonObject
            val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val epochMs = parseDateTimeMs(ts, hourlyOffsetSeconds) ?: return@mapNotNull null
            HourlyForecast(
                dateTime = epochMs,
                temperature = obj.floatOrNull("temperature") ?: return@mapNotNull null,
                condition = weatherCodeToCondition(obj["weather_code"]?.jsonPrimitive?.contentOrNull),
                precipProbability = obj.intOrNull("precipitation_probability"),
                precipAmountMm = obj.floatOrNull("precipitation_accumulation")?.let { it * INCHES_TO_MM },
                cloudCover = obj.intOrNull("cloud_cover"),
                source = WeatherSource.SILURIAN.id,
            )
        }

        val daily = (dailyRoot["daily"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val obj = el.jsonObject
            val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            // Daily timestamp is a plain date ("yyyy-MM-dd"); keep the date portion verbatim.
            val date = ts.take(10)
            DailyForecast(
                date = date,
                highTemp = obj.floatOrNull("max_temperature") ?: obj.floatOrNull("temperature") ?: return@mapNotNull null,
                lowTemp = obj.floatOrNull("min_temperature") ?: obj.floatOrNull("temperature") ?: return@mapNotNull null,
                condition = weatherCodeToCondition(obj["weather_code"]?.jsonPrimitive?.contentOrNull),
                precipProbability = obj.intOrNull("precipitation_probability"),
                precipAmountMm = obj.floatOrNull("precipitation_accumulation")?.let { it * INCHES_TO_MM },
            )
        }

        // API² has no separate "current observation" endpoint; current temp is interpolated from the
        // hourly series upstream (CurrentTemperatureResolver), and the actual line is driven by the
        // include_past hours re-filed as observations. So we deliberately leave current* unset.
        ForecastResult(
            daily = daily,
            hourly = hourly,
        )
    }

    /** GETs [path] with the standard params and returns the parsed root object, or throws on non-2xx. */
    private suspend fun fetchJson(
        apiKey: String,
        path: String,
        lat: Double,
        lon: Double,
        extra: HttpRequestBuilder.() -> Unit = {},
    ): JsonObject {
        val response: HttpResponse = httpClient.get("$BASE_URL$path") {
            header("X-API-KEY", apiKey)
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("units", "imperial")
            parameter("timezone", "local")
            extra()
        }
        if (response.status.value !in 200..299) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("No error body")
            throw ApiAccessException(
                source = WeatherSource.SILURIAN,
                statusCode = response.status.value,
                detail = errorBody,
                message = "Silurian fetch failed ($path): status ${response.status.value}. Detail: $errorBody",
            )
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.floatOrNull(key: String): Float? = this[key]?.jsonPrimitive?.floatOrNull
    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    /**
     * Parses an ISO-8601 date-time. The API returns local times (with `timezone=local`); the string
     * may or may not carry an offset, so try offset-aware forms first, then fall back to a naive
     * local time anchored with the response's reported `utc_offset`.
     */
    private fun parseDateTimeMs(ts: String, utcOffsetSeconds: Int): Long? =
        runCatching { OffsetDateTime.parse(ts).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(ts).toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(ts).toInstant(ZoneOffset.ofTotalSeconds(utcOffsetSeconds)).toEpochMilli()
            }
            .onFailure { Log.w(TAG, "Unparseable Silurian timestamp: $ts") }
            .getOrNull()

    /** Maps the API² `weather_code` enum to the app's condition vocabulary (shared with other sources). */
    private fun weatherCodeToCondition(code: String?): String = when (code) {
        "clear-day", "clear-night" -> "Clear"
        "partly-cloudy-day", "partly-cloudy-night" -> "Partly Cloudy"
        "cloudy" -> "Cloudy"
        "rain" -> "Rain"
        "snow" -> "Snow"
        else -> "Unknown"
    }
}
