package com.weatherwidget.data.remote

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "OpenWeatherMapApi"

class OpenWeatherMapApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val CURRENT_URL = "https://api.openweathermap.org/data/2.5/weather"
        private const val FORECAST_URL = "https://api.openweathermap.org/data/2.5/forecast"
    }

    suspend fun getForecast(
        lat: Double,
        lon: Double,
    ): RawFetch = coroutineScope {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("OPEN_WEATHER_MAP_API_KEY is missing.")
        }

        val currentWeatherDeferred = async {
            httpClient.get(CURRENT_URL) {
                parameter("lat", lat)
                parameter("lon", lon)
                parameter("appid", apiKey)
                parameter("units", "imperial")
            }
        }
        val forecastDeferred = async {
            httpClient.get(FORECAST_URL) {
                parameter("lat", lat)
                parameter("lon", lon)
                parameter("appid", apiKey)
                parameter("units", "imperial")
            }
        }

        val currentResponse = currentWeatherDeferred.await()
        val forecastResponse = forecastDeferred.await()

        checkResponseStatus(currentResponse)
        checkResponseStatus(forecastResponse)

        val currentBody: String = currentResponse.body()
        val forecastBody: String = forecastResponse.body()

        parseResponses(currentBody, forecastBody)
    }

    private suspend fun checkResponseStatus(response: HttpResponse) {
        response.require2xx(WeatherSource.OPEN_WEATHER_MAP, "OpenWeatherMap fetch failed")
    }

    private fun parseResponses(currentBody: String, forecastBody: String): RawFetch {
        val currentRoot = json.parseToJsonElement(currentBody).jsonObject
        val currentMain = currentRoot["main"]?.jsonObject
        val currentTemp = currentMain?.get("temp")?.jsonPrimitive?.floatOrNull
        val currentWeatherList = currentRoot["weather"]?.jsonArray
        val currentCondition = currentWeatherList?.firstOrNull()?.jsonObject?.get("main")?.jsonPrimitive?.content
        val currentObservedAt = currentRoot["dt"]?.jsonPrimitive?.longOrNull?.let { it * 1000L }

        val forecastRoot = json.parseToJsonElement(forecastBody).jsonObject
        val cityObj = forecastRoot["city"]?.jsonObject
        val timezoneOffsetSeconds = cityObj?.get("timezone")?.jsonPrimitive?.longOrNull ?: 0L
        val zoneOffset = ZoneOffset.ofTotalSeconds(timezoneOffsetSeconds.toInt())

        val listArray = forecastRoot["list"]?.jsonArray ?: emptyList()

        val periods = listArray.mapNotNull { element ->
            val obj = element.jsonObject
            val dtSec = obj["dt"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val dtMs = dtSec * 1000L
            val zonedDateTime = Instant.ofEpochMilli(dtMs).atOffset(zoneOffset)
            val localDate = zonedDateTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val hourOfDay = zonedDateTime.hour

            val mainObj = obj["main"]?.jsonObject
            // A period with no temperature is dropped, not stored as 0°: the hours either side of the
            // gap are then left uninterpolated, like any other gap longer than three hours.
            val temp = mainObj?.get("temp")?.jsonPrimitive?.floatOrNull
            if (temp == null) {
                Log.w(TAG, "OWM_PERIOD_NO_TEMP: dropped period dt=$dtSec")
                return@mapNotNull null
            }
            val weatherObj = obj["weather"]?.jsonArray?.firstOrNull()?.jsonObject
            val condition = weatherObj?.get("main")?.jsonPrimitive?.content.orEmpty()
            val icon = weatherObj?.get("icon")?.jsonPrimitive?.content
            val pop = obj["pop"]?.jsonPrimitive?.floatOrNull?.let { (it * 100).roundToInt() }
            val rain3h = obj["rain"]?.jsonObject?.get("3h")?.jsonPrimitive?.floatOrNull ?: 0f
            val snow3h = obj["snow"]?.jsonObject?.get("3h")?.jsonPrimitive?.floatOrNull ?: 0f
            val cloudCover = obj["clouds"]?.jsonObject?.get("all")?.jsonPrimitive?.intOrNull
            // Metres, capped by OWM at 10 km.
            val visibilityMi = com.weatherwidget.shared.fog.VisibilityUnits.metersToMiles(obj["visibility"]?.jsonPrimitive?.intOrNull)

            OwmPeriod(
                dtMs = dtMs,
                temp = temp,
                condition = condition,
                icon = icon,
                popPercent = pop,
                precipMm = rain3h + snow3h,
                cloudCover = cloudCover,
                visibilityMi = visibilityMi,
                localDate = localDate,
                hourOfDay = hourOfDay,
            )
        }

        val hourlyForecasts = buildHourlyForecasts(periods)
        val dailyForecasts = buildDailyForecasts(periods)

        return RawFetch(
            providerCurrentTemp = currentTemp,
            providerCurrentCondition = currentCondition,
            providerCurrentObservedAt = currentObservedAt,
            daily = dailyForecasts,
            hourly = hourlyForecasts,
        )
    }

    private fun buildHourlyForecasts(periods: List<OwmPeriod>): List<HourlyForecast> {
        if (periods.isEmpty()) return emptyList()
        val result = mutableListOf<HourlyForecast>()

        for (i in periods.indices) {
            val current = periods[i]
            val next = periods.getOrNull(i + 1)

            result.add(
                HourlyForecast(
                    dateTime = current.dtMs,
                    temperature = current.temp,
                    condition = current.condition,
                    precipProbability = current.popPercent,
                    precipAmountMm = current.precipMm / 3f,
                    cloudCover = current.cloudCover,
                    source = WeatherSource.OPEN_WEATHER_MAP.id,
                    visibilityMi = current.visibilityMi,
                )
            )

            // The two hours between are a third and two thirds of the way to the next period; rain
            // chance and cloud are rounded to the nearest percent.
            if (next != null && next.dtMs - current.dtMs == 3 * 3600_000L) {
                val tempDiff = next.temp - current.temp
                val popDiff = if (current.popPercent != null && next.popPercent != null) {
                    next.popPercent - current.popPercent
                } else null
                val cloudDiff = if (current.cloudCover != null && next.cloudCover != null) {
                    next.cloudCover - current.cloudCover
                } else null

                result.add(
                    HourlyForecast(
                        dateTime = current.dtMs + 3600_000L,
                        temperature = current.temp + tempDiff * (1f / 3f),
                        condition = current.condition,
                        precipProbability = popDiff?.let { current.popPercent!! + (it * (1f / 3f)).roundToInt() } ?: current.popPercent,
                        precipAmountMm = current.precipMm / 3f,
                        cloudCover = cloudDiff?.let { current.cloudCover!! + (it * (1f / 3f)).roundToInt() } ?: current.cloudCover,
                        source = WeatherSource.OPEN_WEATHER_MAP.id,
                        visibilityMi = current.visibilityMi,
                    )
                )

                result.add(
                    HourlyForecast(
                        dateTime = current.dtMs + 2 * 3600_000L,
                        temperature = current.temp + tempDiff * (2f / 3f),
                        condition = if (current.condition == "Clear" && next.condition != "Clear") next.condition else current.condition,
                        precipProbability = popDiff?.let { current.popPercent!! + (it * (2f / 3f)).roundToInt() } ?: next.popPercent ?: current.popPercent,
                        precipAmountMm = current.precipMm / 3f,
                        cloudCover = cloudDiff?.let { current.cloudCover!! + (it * (2f / 3f)).roundToInt() } ?: next.cloudCover ?: current.cloudCover,
                        source = WeatherSource.OPEN_WEATHER_MAP.id,
                        // The lower of its neighbours: interpolating could invent clear air between fog.
                        visibilityMi = listOfNotNull(current.visibilityMi, next.visibilityMi).minOrNull(),
                    )
                )
            }
        }
        return result
    }

    private fun buildDailyForecasts(periods: List<OwmPeriod>): List<DailyForecast> {
        val groupedByDate = periods.groupBy { it.localDate }
        return groupedByDate.map { (date, dayPeriods) ->
            val highTemp = dayPeriods.maxOf { it.temp }
            val lowTemp = dayPeriods.minOf { it.temp }
            val precipProbability = dayPeriods.mapNotNull { it.popPercent }.maxOrNull()
            val precipAmountMm = dayPeriods.sumOf { it.precipMm.toDouble() }.toFloat()

            val representative = dayPeriods.minByOrNull { abs(it.hourOfDay - 13) } ?: dayPeriods.first()
            val condition = representative.condition
            val iconToken = representative.icon ?: representative.condition

            DailyForecast(
                date = date,
                highTemp = highTemp,
                lowTemp = lowTemp,
                condition = condition,
                iconToken = iconToken,
                precipProbability = precipProbability,
                precipAmountMm = precipAmountMm,
                source = WeatherSource.OPEN_WEATHER_MAP.id,
            )
        }
    }

    private data class OwmPeriod(
        val dtMs: Long,
        val temp: Float,
        val condition: String,
        val icon: String?,
        val popPercent: Int?,
        val precipMm: Float,
        val cloudCover: Int?,
        val visibilityMi: Float? = null,
        val localDate: String,
        val hourOfDay: Int,
    )
}
