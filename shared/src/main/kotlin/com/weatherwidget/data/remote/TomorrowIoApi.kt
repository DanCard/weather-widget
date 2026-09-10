package com.weatherwidget.data.remote

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private const val TAG = "TomorrowIoApi"

class TomorrowIoApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val TIMELINES_URL = "https://api.tomorrow.io/v4/timelines"
        private const val METERS_PER_MILE = 1_609.344
        const val FULL_ACTUALS_LOOKBACK_HOURS = 23
        const val INCREMENTAL_ACTUALS_LOOKBACK_HOURS = 1
        private const val FIVE_MINUTE_INTERVAL_MS = 5 * 60 * 1_000L

        internal fun imperialDistanceToMeters(value: Double?): Int? = value
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.times(METERS_PER_MILE)
            ?.roundToInt()

        /**
         * Reads a 0-100 percentage that the API documents as an integer but is not obliged to send
         * as one. Parsed as a float and rounded, because `jsonPrimitive.intOrNull` returns **null**
         * for `4.9` — a provider switch to fractional percentages would silently blank the field
         * rather than round it, and a blanked rain chance is indistinguishable from a dry forecast.
         * Every value observed from Tomorrow.io is an integer multiple of 5 (probed 2026-09-02
         * across three continents), so this is insurance, not a live fix.
         */
        internal fun percentOrNull(value: Float?): Int? = value
            ?.takeIf { it.isFinite() }
            ?.roundToInt()
            ?.coerceIn(0, 100)
    }

    suspend fun getForecast(
        lat: Double,
        lon: Double,
    ): RawFetch {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("TOMORROW_IO_API_KEY is missing.")
        }

        val timelineHttpResponse = httpClient.get(TIMELINES_URL) {
            parameter("location", "$lat,$lon")
            parameter(
                "fields",
                "temperature,temperatureMax,temperatureMin,weatherCode,precipitationProbability," +
                    "precipitationAccumulation,cloudCover,cloudBase,cloudCeiling",
            )
            url.parameters.append("timesteps", "1h")
            url.parameters.append("timesteps", "1d")
            parameter("units", "imperial")
            parameter("apikey", apiKey)
            // Reaches back far enough to cover the elapsed part of the local day, so a site being
            // fetched for the FIRST time still receives a complete provider forecast timeline
            // rather than only the hours since it was promoted. Elapsed hourly intervals remain
            // forecast data; Tomorrow.io actuals come only from [getFiveMinuteHistory].
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
        timelineHttpResponse.require2xx(WeatherSource.TOMORROW_IO, "Tomorrow.io timeline fetch failed")
        val timelineJson = json.parseToJsonElement(timelineHttpResponse.body<String>()).jsonObject
        val timelines = timelineJson["data"]?.jsonObject?.get("timelines")?.jsonArray ?: JsonArray(emptyList())

        // The API does not promise response order. Match by timestep so requesting both series in
        // one call cannot accidentally parse daily values as hourly (or vice versa).
        fun intervalsFor(timestep: String): JsonArray = timelines
            .firstOrNull { timeline ->
                timeline.jsonObject["timestep"]?.jsonPrimitive?.contentOrNull == timestep
            }
            ?.jsonObject
            ?.get("intervals")
            ?.jsonArray
            ?: JsonArray(emptyList())

        val hourlyIntervals = intervalsFor("1h")
        val dailyIntervals = intervalsFor("1d")

        val hourlyForecasts = hourlyIntervals.mapIndexedNotNull { _, element ->
            val obj = element.jsonObject
            val startTime = obj["startTime"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
            val values = obj["values"]?.jsonObject ?: return@mapIndexedNotNull null

            val epochMs = OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
            val temp = values["temperature"]?.jsonPrimitive?.floatOrNull ?: Float.NaN
            val code = values["weatherCode"]?.jsonPrimitive?.intOrNull ?: 1000
            val precipProb = percentOrNull(values["precipitationProbability"]?.jsonPrimitive?.floatOrNull)
            val precipAccumIn = values["precipitationAccumulation"]?.jsonPrimitive?.floatOrNull

            HourlyForecast(
                dateTime = epochMs,
                temperature = temp,
                condition = weatherCodeToCondition(code),
                precipProbability = precipProb,
                precipAmountMm = precipAccumIn?.let { it * 25.4f },
                cloudCover = percentOrNull(values["cloudCover"]?.jsonPrimitive?.floatOrNull),
                cloudEnvelopeBaseMeters = imperialDistanceToMeters(values["cloudBase"]?.jsonPrimitive?.doubleOrNull),
                cloudEnvelopeTopMeters = imperialDistanceToMeters(values["cloudCeiling"]?.jsonPrimitive?.doubleOrNull),
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
            val precipProb = percentOrNull(values["precipitationProbability"]?.jsonPrimitive?.floatOrNull)
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

    /**
     * Returns Tomorrow.io's canonical elapsed five-minute analysis window.
     *
     * This is deliberately separate from [getForecast]: forecast resolution remains `1h + 1d`,
     * while temperature actuals use exactly one provider product. Callers persist [RawFetch.subHourly]
     * at the API timestamps and upsert repeated timestamps so later provider revisions replace the
     * earlier value. The realtime endpoint is intentionally not used.
     */
    suspend fun getFiveMinuteHistory(
        lat: Double,
        lon: Double,
        lookbackHours: Int,
    ): RawFetch {
        require(lookbackHours in 1..FULL_ACTUALS_LOOKBACK_HOURS) {
            "Tomorrow.io five-minute lookback must be 1..$FULL_ACTUALS_LOOKBACK_HOURS hours"
        }
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("TOMORROW_IO_API_KEY is missing.")
        }

        // Literal `endTime=now` makes Tomorrow.io anchor the series to the request minute. A call
        // at 12:09 returns :09/:04/... while one at 12:10 returns :10/:05/..., defeating exact-key
        // revision upserts. Anchor the request to a stable five-minute boundary; returned interval
        // timestamps are still parsed and persisted exactly, with no local timestamp rounding.
        val now = clock.instant()
        val endTime = Instant.ofEpochMilli(
            now.toEpochMilli() - Math.floorMod(now.toEpochMilli(), FIVE_MINUTE_INTERVAL_MS),
        )
        val startTime = endTime.minus(lookbackHours.toLong(), ChronoUnit.HOURS)

        val response = httpClient.get(TIMELINES_URL) {
            parameter("location", "$lat,$lon")
            parameter(
                "fields",
                // Tomorrow.io rejects precipitationAccumulation for timestep=5m (400001).
                // Keep this actuals request to fields the provider supports at five-minute
                // resolution; precipitation remains supplied by the unchanged forecast request.
                "temperature,weatherCode,cloudCover,cloudBase,cloudCeiling",
            )
            parameter("timesteps", "5m")
            parameter("units", "imperial")
            parameter("startTime", startTime.toString())
            parameter("endTime", endTime.toString())
            parameter("apikey", apiKey)
        }
        response.require2xx(WeatherSource.TOMORROW_IO, "Tomorrow.io five-minute history fetch failed")

        val root = json.parseToJsonElement(response.body<String>()).jsonObject
        val intervals = root["data"]?.jsonObject
            ?.get("timelines")?.jsonArray
            ?.firstOrNull { it.jsonObject["timestep"]?.jsonPrimitive?.contentOrNull == "5m" }
            ?.jsonObject
            ?.get("intervals")?.jsonArray
            ?: JsonArray(emptyList())
        val history = intervals.mapNotNull { element ->
            val obj = element.jsonObject
            val startTime = obj["startTime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val values = obj["values"]?.jsonObject ?: return@mapNotNull null
            val temperature = values["temperature"]?.jsonPrimitive?.floatOrNull
                ?.takeIf { it.isFinite() }
                ?: return@mapNotNull null
            val timestamp = runCatching { OffsetDateTime.parse(startTime).toInstant().toEpochMilli() }
                .getOrNull()
                ?: return@mapNotNull null
            val code = values["weatherCode"]?.jsonPrimitive?.intOrNull
            HourlyForecast(
                dateTime = timestamp,
                temperature = temperature,
                condition = code?.let(::weatherCodeToCondition) ?: "Unknown",
                cloudCover = percentOrNull(values["cloudCover"]?.jsonPrimitive?.floatOrNull),
                cloudEnvelopeBaseMeters = imperialDistanceToMeters(values["cloudBase"]?.jsonPrimitive?.doubleOrNull),
                cloudEnvelopeTopMeters = imperialDistanceToMeters(values["cloudCeiling"]?.jsonPrimitive?.doubleOrNull),
                source = WeatherSource.TOMORROW_IO.id,
            )
        }.sortedBy { it.dateTime }
        val latest = history.lastOrNull()
        return RawFetch(
            subHourly = history,
            providerCurrentTemp = latest?.temperature,
            providerCurrentCondition = latest?.condition,
            providerCurrentObservedAt = latest?.dateTime,
            providerCurrentCloudCover = latest?.cloudCover,
        )
    }

    fun weatherCodeToCondition(code: Int): String = WeatherCodeMapper.tomorrowIoCodeToCondition(code)
}
