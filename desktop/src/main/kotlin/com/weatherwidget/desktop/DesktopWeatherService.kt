package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

/**
 * Thin desktop-side orchestration over the shared API clients. Supports all major sources
 * used by the Android widget.
 */
class DesktopWeatherService(
    private val latitude: Double,
    private val longitude: Double,
    private val weatherSource: String = "NWS",
    private val apiKeys: Map<String, String> = emptyMap()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    private val openMeteo = OpenMeteoApi(httpClient, json)
    private val nwsApi = NwsApi(httpClient, json)
    private val tomorrowIo = TomorrowIoApi(httpClient, json) { apiKeys[WeatherSource.TOMORROW_IO.id] }
    private val weatherApi = WeatherApi(httpClient, json) { apiKeys[WeatherSource.WEATHER_API.id] }
    private val visualCrossing = VisualCrossingApi(httpClient, json) { apiKeys[WeatherSource.VISUAL_CROSSING.id] }
    private val silurian = SilurianApi(httpClient, json) { apiKeys[WeatherSource.SILURIAN.id] }
    private val openWeatherMap = OpenWeatherMapApi(httpClient, json) { apiKeys[WeatherSource.OPEN_WEATHER_MAP.id] }

    constructor(config: DesktopConfig?) : this(
        latitude = config?.lat ?: FALLBACK_LATITUDE,
        longitude = config?.lon ?: FALLBACK_LONGITUDE,
        weatherSource = config?.weatherSource ?: "NWS",
        apiKeys = config?.apiKeys ?: emptyMap()
    )

    suspend fun fetchForecast(): ForecastResult = runCatching {
        when (weatherSource) {
            "NWS" -> fetchNwsForecast()
            WeatherSource.TOMORROW_IO.id -> tomorrowIo.getForecast(latitude, longitude)
            WeatherSource.WEATHER_API.id -> weatherApi.getForecast(latitude, longitude)
            WeatherSource.VISUAL_CROSSING.id -> visualCrossing.getForecast(latitude, longitude)
            WeatherSource.SILURIAN.id -> silurian.getForecast(latitude, longitude)
            WeatherSource.OPEN_WEATHER_MAP.id -> openWeatherMap.getForecast(latitude, longitude)
            else -> openMeteo.getForecast(latitude, longitude)
        }
    }.getOrElse { e ->
        // If NWS fails (e.g. out of US) or any source fails, fall back to Open-Meteo.
        if (weatherSource != WeatherSource.OPEN_METEO.id) {
            openMeteo.getForecast(latitude, longitude)
        } else {
            throw e
        }
    }

    private suspend fun fetchNwsForecast(): ForecastResult = coroutineScope {
        val grid = nwsApi.getGridPoint(latitude, longitude)
        val hourlyDeferred = async { nwsApi.getHourlyForecast(grid) }
        val dailyDeferred = async { nwsApi.getForecast(grid) }
        
        val currentObsDeferred = async {
            try {
                grid.observationStationsUrl?.let { url ->
                    val stations = nwsApi.getObservationStations(url)
                    stations.firstOrNull()?.let { nwsApi.getLatestObservationDetailed(it.id) }
                }
            } catch (e: Exception) {
                null
            }
        }

        val hourlyRaw = hourlyDeferred.await()
        val dailyRaw = dailyDeferred.await()
        val currentObs = currentObsDeferred.await()

        val observations = currentObs?.let { obs ->
            listOf(
                com.weatherwidget.data.local.ObservationEntity(
                    stationId = grid.observationStationsUrl?.substringAfterLast("/") ?: "NWS",
                    stationName = obs.stationName,
                    timestamp = try { java.time.ZonedDateTime.parse(obs.timestamp).toInstant().toEpochMilli() } catch (e: Exception) { System.currentTimeMillis() },
                    temperature = (obs.temperatureCelsius * 1.8f) + 32f,
                    condition = obs.textDescription,
                    locationLat = latitude,
                    locationLon = longitude,
                    api = "NWS",
                    precipAmountMm = obs.precipLastHourMm,
                    maxTempLast24h = obs.maxTempLast24hCelsius?.let { (it * 1.8f) + 32f },
                    minTempLast24h = obs.minTempLast24hCelsius?.let { (it * 1.8f) + 32f }
                )
            )
        } ?: emptyList()

        ForecastResult(
            currentTemp = currentObs?.temperatureCelsius?.let { (it * 1.8f) + 32f } 
                ?: hourlyRaw.firstOrNull()?.temperature,
            currentCondition = currentObs?.textDescription 
                ?: hourlyRaw.firstOrNull()?.shortForecast,
            currentObservedAt = observations.firstOrNull()?.timestamp,
            hourly = hourlyRaw.map { it.toHourlyForecast() },
            daily = mapNwsToDaily(dailyRaw),
            rawObservations = observations
        )
    }

    private fun NwsApi.HourlyForecastPeriod.toHourlyForecast() = HourlyForecast(
        dateTime = startTime,
        temperature = temperature,
        condition = shortForecast,
        precipProbability = precipProbability,
        precipAmountMm = precipAmountMm,
        cloudCover = cloudCover
    )

    private fun mapNwsToDaily(periods: List<NwsApi.ForecastPeriod>): List<DailyForecast> {
        val dailyMap = mutableMapOf<String, MutableList<NwsApi.ForecastPeriod>>()
        for (period in periods) {
            val date = period.startTime.take(10)
            dailyMap.getOrPut(date) { mutableListOf() }.add(period)
        }

        return dailyMap.map { (date, dayPeriods) ->
            val high = dayPeriods.filter { it.isDaytime }.maxOfOrNull { it.temperature.toFloat() }
                ?: dayPeriods.maxOfOrNull { it.temperature.toFloat() } ?: 0f
            val low = dayPeriods.filter { !it.isDaytime }.minOfOrNull { it.temperature.toFloat() }
                ?: dayPeriods.minOfOrNull { it.temperature.toFloat() } ?: 0f
            val condition = dayPeriods.firstOrNull { it.isDaytime }?.shortForecast 
                ?: dayPeriods.firstOrNull()?.shortForecast ?: ""
            
            DailyForecast(
                date = date,
                highTemp = high,
                lowTemp = low,
                condition = condition,
                precipProbability = dayPeriods.mapNotNull { it.precipProbability }.maxOrNull()
            )
        }.sortedBy { it.date }
    }

    fun close() = httpClient.close()

    companion object {
        const val FALLBACK_LATITUDE = 37.4220
        const val FALLBACK_LONGITUDE = -122.0841
    }
}
