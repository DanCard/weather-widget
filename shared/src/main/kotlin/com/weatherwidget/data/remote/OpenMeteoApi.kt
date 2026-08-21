package com.weatherwidget.data.remote

import com.weatherwidget.shared.config.ForecastHorizon
import com.weatherwidget.shared.util.Log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

private const val TAG = "OpenMeteoApi"

class OpenMeteoApi
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
    ) {
        companion object {
            private const val BASE_URL = "https://api.open-meteo.com/v1"
            private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1"
            private const val PREVIOUS_RUNS_URL = "https://previous-runs-api.open-meteo.com/v1"

            /** Verified: `past_days=31` returns the full span with `_previous_day1` fully populated. */
            const val MAX_PREVIOUS_RUNS_PAST_DAYS = 31

            /**
             * The **low**-layer variable, matching what the actual curve stores. Using the total
             * column here would compare a forecast of "any cloud anywhere in the column" against an
             * actual of "cloud you can see", and the two diverge hard: on 2026-08-20 total ran
             * 83-99% all afternoon on thin cirrus while the low layer — and every surface station —
             * read 4-13%.
             */
            const val PREVIOUS_RUNS_VARIABLE = "cloud_cover_low_previous_day1"

                /**
                 * Pure seam: the whole of this endpoint's parsing, reachable without an HTTP engine.
                 */
                internal fun parsePriorDayCloudForecast(response: String, nowMs: Long): Map<Long, Int> {
                val jsonObj = Json.parseToJsonElement(response).jsonObject
                val hourly = jsonObj["hourly"]?.jsonObject ?: return emptyMap()
                val times = hourly["time"]?.jsonArray ?: return emptyMap()
                val covers = hourly[PREVIOUS_RUNS_VARIABLE]?.jsonArray ?: return emptyMap()
                // The response carries its own timezone; parse in it rather than the device default so
                // a phone travelling across zones keeps filing hours under the same keys.
                val zone = jsonObj["timezone"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
                    ?: java.time.ZoneId.systemDefault()

                val out = LinkedHashMap<Long, Int>()
                times.forEachIndexed { index, timeElement ->
                    val cover = covers.getOrNull(index)?.jsonPrimitive?.intOrNull
                        ?: return@forEachIndexed
                    val ms = runCatching {
                        java.time.LocalDateTime.parse(timeElement.jsonPrimitive.content)
                            .atZone(zone).toInstant().toEpochMilli()
                    }.getOrNull() ?: return@forEachIndexed
                    if (ms >= nowMs) return@forEachIndexed
                    out[ms] = cover.coerceIn(0, 100)
                }
                Log.d(TAG, "getPriorDayCloudForecast: hours=${times.size} kept=${out.size} zone=$zone")
                return out
            }

            /**
             * The `minutely_15` block as bare cloud readings.
             *
             * Steps with no cloud value are dropped, not zeroed. Expected to be empty outside CONUS,
             * where the 15-minute product has no model behind it — an empty list, never zeros.
             */
            internal fun parseSubHourlyCloud(
                minutely: kotlinx.serialization.json.JsonObject?,
                zone: java.time.ZoneId,
            ): List<com.weatherwidget.data.model.SubHourlyCloud> {
                val times = minutely?.get("time")?.jsonArray ?: return emptyList()
                val total = minutely["cloud_cover"]?.jsonArray
                val low = minutely["cloud_cover_low"]?.jsonArray
                if (total == null && low == null) return emptyList()

                return times.mapIndexedNotNull { index, timeElement ->
                    val t = total?.getOrNull(index)?.jsonPrimitive?.intOrNull
                    val l = low?.getOrNull(index)?.jsonPrimitive?.intOrNull
                    if (t == null && l == null) return@mapIndexedNotNull null
                    val ms = runCatching {
                        java.time.LocalDateTime.parse(timeElement.jsonPrimitive.content)
                            .atZone(zone).toInstant().toEpochMilli()
                    }.getOrNull() ?: return@mapIndexedNotNull null
                    com.weatherwidget.data.model.SubHourlyCloud(
                        timeMs = ms,
                        cloudCover = t?.coerceIn(0, 100),
                        cloudCoverLow = l?.coerceIn(0, 100),
                    )
                }
            }
        }

        suspend fun getForecast(
            lat: Double,
            lon: Double,
            days: Int = ForecastHorizon.MAX_DAYS,
            historyDays: Int = 0,
        ): RawFetch {
            val response: String =
                httpClient.get("$BASE_URL/forecast") {
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("daily", "temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,precipitation_sum")
                    parameter("hourly", "temperature_2m,weather_code,precipitation_probability,precipitation,cloud_cover,cloud_cover_low")
                    // Sub-hourly cloud, on the same request. The hourly series is a top-of-hour
                    // SUBSAMPLE of this one (verified: `:00` values match exactly), so an hour whose
                    // cloud moves is misrepresented by its own label — 2026-08-20 19:00 stored 10%
                    // for an hour that ran 10 -> 27 -> 51 -> 74 as the marine layer arrived.
                    parameter("minutely_15", "cloud_cover,cloud_cover_low")
                    parameter("current", "temperature_2m,weather_code")
                    parameter("temperature_unit", "fahrenheit")
                    parameter("timezone", "auto")
                    parameter("past_days", historyDays) // Include variable history for actuals
                    parameter("forecast_days", days)
                }.body()

            Log.d(TAG, "getForecast: Raw response length=${response.length}")
            val jsonObj = json.parseToJsonElement(response).jsonObject

            val current = jsonObj["current"]?.jsonObject
            val timezone = jsonObj["timezone"]?.jsonPrimitive?.content
            val daily = jsonObj["daily"]?.jsonObject
            Log.d(TAG, "getForecast: daily object keys=${daily?.keys}")

            val dates = daily?.get("time")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            Log.d(TAG, "getForecast: parsed ${dates.size} dates: $dates")
            val maxTemps =
                daily?.get("temperature_2m_max")?.jsonArray?.map {
                    it.jsonPrimitive.content.toFloatOrNull()
                } ?: emptyList()
            val minTemps =
                daily?.get("temperature_2m_min")?.jsonArray?.map {
                    it.jsonPrimitive.content.toFloatOrNull()
                } ?: emptyList()
            val weatherCodes =
                daily?.get("weather_code")?.jsonArray?.map {
                    it.jsonPrimitive.content.toIntOrNull() ?: 0
                } ?: emptyList()
            val precipProbs =
                daily?.get("precipitation_probability_max")?.jsonArray?.map {
                    it.jsonPrimitive.content.toIntOrNull()
                } ?: emptyList()
            val dailyPrecipAmountsMm =
                daily?.get("precipitation_sum")?.jsonArray?.map {
                    it.jsonPrimitive.content.toFloatOrNull()
                } ?: emptyList()

val dailyForecasts =
dates.mapIndexedNotNull { index, date ->
val code = weatherCodes.getOrNull(index) ?: 0
// Open-Meteo returns null entries at window edges; skip days without a usable
// high/low instead of emitting Float.NaN, which poisons roundToInt() downstream
// (snapshot saving in ForecastRepository) and aborts the whole fetch cycle.
val high = maxTemps.getOrNull(index)?.takeIf { it.isFinite() }
val low = minTemps.getOrNull(index)?.takeIf { it.isFinite() }
if (high == null || low == null) return@mapIndexedNotNull null
DailyForecast(
date = date,
highTemp = high,
lowTemp = low,
condition = weatherCodeToCondition(code),
iconToken = code.toString(),
precipProbability = precipProbs.getOrNull(index),
precipAmountMm = dailyPrecipAmountsMm.getOrNull(index),
)
}

            // Parse hourly data
            val hourly = jsonObj["hourly"]?.jsonObject
            val hourlyTimes = hourly?.get("time")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val hourlyTemps =
                hourly?.get("temperature_2m")?.jsonArray?.map {
                    it.jsonPrimitive.content.toDoubleOrNull()?.toFloat()
                } ?: emptyList()
            val hourlyCodes =
                hourly?.get("weather_code")?.jsonArray?.map {
                    it.jsonPrimitive.content.toIntOrNull() ?: 0
                } ?: emptyList()
            val hourlyPrecipProbs =
                hourly?.get("precipitation_probability")?.jsonArray?.map {
                    it.jsonPrimitive.content.toIntOrNull()
                } ?: emptyList()
            val hourlyPrecipAmountsMm =
                hourly?.get("precipitation")?.jsonArray?.map {
                    it.jsonPrimitive.content.toFloatOrNull()
                } ?: emptyList()
            val hourlyCloudCover =
                hourly?.get("cloud_cover")?.jsonArray?.map {
                    it.jsonPrimitive.content.toIntOrNull()
                } ?: emptyList()
            val hourlyCloudCoverLow =
                hourly?.get("cloud_cover_low")?.jsonArray?.map {
                    it.jsonPrimitive.content.toIntOrNull()
                } ?: emptyList()

            val zone = timezone?.let { java.time.ZoneId.of(it) } ?: java.time.ZoneId.systemDefault()
            val hourlyForecasts =
                hourlyTimes.mapIndexedNotNull { index, time ->
                    val temp = hourlyTemps.getOrNull(index)
                    val code = hourlyCodes.getOrNull(index) ?: 0
                    val ts = try {
                        java.time.LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        null
                    } ?: return@mapIndexedNotNull null

if (temp != null) {
HourlyForecast(
dateTime = ts,
temperature = temp,
condition = weatherCodeToCondition(code),
precipProbability = hourlyPrecipProbs.getOrNull(index),
precipAmountMm = hourlyPrecipAmountsMm.getOrNull(index),
cloudCover = hourlyCloudCover.getOrNull(index),
cloudCoverLow = hourlyCloudCoverLow.getOrNull(index),
)
} else {
                        null
                    }
                }

            Log.d(TAG, "getForecast: parsed ${hourlyForecasts.size} hourly forecasts")

            val subHourly = parseSubHourlyCloud(jsonObj["minutely_15"]?.jsonObject, zone)
            Log.d(TAG, "getForecast: parsed ${subHourly.size} sub-hourly cloud steps")

val currentCondition = current?.get("weather_code")?.jsonPrimitive?.content?.toIntOrNull()?.let { weatherCodeToCondition(it) }
return RawFetch(
providerCurrentTemp = current?.get("temperature_2m")?.jsonPrimitive?.content?.toFloatOrNull(),
providerCurrentCondition = currentCondition,
providerCurrentObservedAt = parseCurrentObservedAt(
timeRaw = current?.get("time")?.jsonPrimitive?.content,
timezone = timezone,
),
daily = dailyForecasts,
hourly = hourlyForecasts,
subHourlyCloud = subHourly,
)
}

        suspend fun getCurrent(
            lat: Double,
            lon: Double,
        ): CurrentReading? {
            val response: String =
                httpClient.get("$BASE_URL/forecast") {
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("current", "temperature_2m,weather_code")
                    parameter("temperature_unit", "fahrenheit")
                    parameter("timezone", "auto")
                    parameter("forecast_days", 1)
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val current = jsonObj["current"]?.jsonObject ?: return null
            val timezone = jsonObj["timezone"]?.jsonPrimitive?.content
            val temp = current["temperature_2m"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
            val weatherCode = current["weather_code"]?.jsonPrimitive?.content?.toIntOrNull()

            return CurrentReading(
                temperature = temp,
                weatherCode = weatherCode,
                observedAt = parseCurrentObservedAt(current["time"]?.jsonPrimitive?.content, timezone),
            )
        }

        private fun parseCurrentObservedAt(
            timeRaw: String?,
            timezone: String?,
        ): Long? {
            if (timeRaw.isNullOrBlank()) return null
            return try {
                ZonedDateTime.parse(timeRaw).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    val local = LocalDateTime.parse(timeRaw)
                    val zone = timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
                    local.atZone(zone).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    // Both parse strategies failed: don't drop the timestamp silently — a current
                    // observation then has no observedAt and we'd have no idea why. Log the raw value.
                    Log.w(TAG, "Could not parse current observation time '$timeRaw': ${e.message}")
                    null
                }
            }
        }

        /**
         * What Open-Meteo predicted for each past hour roughly 24 hours beforehand, from the
         * Previous Runs API (`cloud_cover_previous_day1`). Backs the cloud graph's frozen forecast
         * curve — see [com.weatherwidget.shared.graph.PriorDayCloudForecast] for why the live rows
         * cannot serve that role.
         *
         * Returns one entry per hour that has a value, as `dateTime` (epoch ms) to cover percent.
         * Hours the API reports as null are **omitted, not zeroed**: a missing prediction must stay
         * missing so the render can fall back honestly instead of drawing a clear sky.
         *
         * @param pastDays how far back to ask, capped at [MAX_PREVIOUS_RUNS_PAST_DAYS]. Note the
         *   `_previous_dayN` suffix caps at 7, but that is a limit on forecast *lead time*, not on
         *   lookback: `_day1` is populated across the full past-days span.
         * @param nowMs hours at or after this are dropped — only elapsed hours have a settled
         *   day-ago prediction worth freezing.
         */
        suspend fun getPriorDayCloudForecast(
            lat: Double,
            lon: Double,
            pastDays: Int,
            nowMs: Long,
        ): Map<Long, Int> {
            val response: String =
                httpClient.get("$PREVIOUS_RUNS_URL/forecast") {
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("hourly", PREVIOUS_RUNS_VARIABLE)
                    parameter("past_days", pastDays.coerceIn(1, MAX_PREVIOUS_RUNS_PAST_DAYS))
                    parameter("forecast_days", 1)
                    parameter("timezone", "auto")
                }.body()

            return Companion.parsePriorDayCloudForecast(response, nowMs)
        }

        /**
         * Fetches observed daily high/low temperatures from the Open-Meteo historical
         * archive (ERA5 reanalysis). Used to derive climate normals by averaging several
         * years of real observations — unlike the old climate-projection endpoint, which
         * returned a single model-year's modeled weather and ran several degrees hot.
         */
        suspend fun getHistoricalDailyTemps(
            lat: Double,
            lon: Double,
            startDate: String,
            endDate: String,
        ): List<DailyForecast> {
            val response: String =
                httpClient.get("$ARCHIVE_URL/archive") {
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("start_date", startDate)
                    parameter("end_date", endDate)
                    parameter("daily", "temperature_2m_max,temperature_2m_min")
                    parameter("temperature_unit", "fahrenheit")
                    parameter("timezone", "auto")
                }.body()

            Log.d(TAG, "getHistoricalDailyTemps: Raw response length=${response.length}")
            val jsonObj = json.parseToJsonElement(response).jsonObject
            val daily = jsonObj["daily"]?.jsonObject

            val dates = daily?.get("time")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val maxTemps =
                daily?.get("temperature_2m_max")?.jsonArray?.map {
                    it.jsonPrimitive.content.toFloatOrNull()
                } ?: emptyList()
            val minTemps =
                daily?.get("temperature_2m_min")?.jsonArray?.map {
                    it.jsonPrimitive.content.toFloatOrNull()
                } ?: emptyList()

return dates.mapIndexedNotNull { index, date ->
// Skip days lacking a usable high/low rather than emitting Float.NaN (poisons roundToInt()).
val high = maxTemps.getOrNull(index)?.takeIf { it.isFinite() }
val low = minTemps.getOrNull(index)?.takeIf { it.isFinite() }
if (high == null || low == null) return@mapIndexedNotNull null
DailyForecast(
date = date,
highTemp = high,
lowTemp = low,
condition = weatherCodeToCondition(0),
)
}
        }

        fun weatherCodeToCondition(code: Int): String =
            when (code) {
                0 -> "Clear"
                1 -> "Mostly Clear"
                2 -> "Partly Cloudy"
                3 -> "Overcast"
                45 -> "Light Fog"
                48 -> "Dense Fog"
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

data class CurrentReading(
    val temperature: Float,
    val weatherCode: Int?,
    val observedAt: Long? = null,
  )
}
