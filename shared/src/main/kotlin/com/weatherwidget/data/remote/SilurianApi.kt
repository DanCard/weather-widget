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
 * of the app) and `include_past=true` so the forecast curve can retain the latest model run's
 * elapsed hours. Those values remain forecasts and must not drive an actual line. See
 * `/api/v1/openapi.json`.
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
    ): RawFetch = coroutineScope {
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

        // API² has no separate current-observation or analysis endpoint. Current temp is therefore
        // interpolated from the forecast upstream (CurrentTemperatureResolver), while include_past
        // remains forecast context and is never re-filed as an observation.
        logCloudCoverSummary(hourlyRoot["hourly"]?.jsonArray, "hourly")
        logCloudCoverSummary(dailyRoot["daily"]?.jsonArray, "daily")
        RawFetch(
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
     * One DEBUG row per fetch summarising the API's raw `cloud_cover` payload. The daily bar's
     * cloud shading is driven by the *hourly noon* reading, so a null/absent/0 noon value is the
     * difference between "cloud cover 0%" and a missing percentage. Non-integer values (e.g.
     * floats) are also counted because [JsonObject.intOrNull] silently drops them — the OpenAPI
     * schema says `integer`, but a backend change to a float would surface here, not as an error.
     */
    private fun logCloudCoverSummary(arr: JsonArray?, kind: String) {
        if (arr == null) {
            Log.d(TAG, "cloudCoverSummary kind=$kind array=absent")
            return
        }
        var total = 0
        var present = 0
        var missing = 0
        var zero = 0
        var nonInt = 0
        val noonIssues = mutableListOf<String>()
        for (el in arr) {
            val obj = el.jsonObject
            total++
            val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
            val isNoon = ts != null && ts.length >= 13 && ts.substring(11, 13) == "12"
            val cloud = obj["cloud_cover"]
            when {
                cloud == null || cloud is JsonNull -> {
                    missing++
                    if (isNoon) noonIssues.add("$ts=null")
                }
                else -> {
                    val int = (cloud as? JsonPrimitive)?.intOrNull
                    if (int == null) {
                        nonInt++
                        if (isNoon) noonIssues.add("$ts=nonInt($cloud)")
                    } else {
                        present++
                        if (int == 0) {
                            zero++
                            if (isNoon) noonIssues.add("$ts=0")
                        }
                    }
                }
            }
        }
        val noon = if (noonIssues.isEmpty()) "-" else noonIssues.joinToString(",")
        Log.d(
            TAG,
            "cloudCoverSummary kind=$kind total=$total present=$present missing=$missing zero=$zero nonInt=$nonInt noonIssues=$noon",
        )
    }

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
