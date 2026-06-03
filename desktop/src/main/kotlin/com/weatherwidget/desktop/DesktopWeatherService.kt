package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.*
import com.weatherwidget.shared.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

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

        // Resolve the nearest observation station once, then reuse its id for both the latest
        // reading (current temp) and the historical window (actuals / daily_extremes). These three
        // observation fetches are best-effort: if any fails the forecast still renders from the
        // hourly/daily feeds, so we degrade to null/empty (and log) rather than fail the whole fetch.
        val stationDeferred = async {
            bestEffort("observation stations") {
                grid.observationStationsUrl?.let { url -> nwsApi.getObservationStations(url).firstOrNull() }
            }
        }
        val station = stationDeferred.await()

        val currentObsDeferred = async {
            station?.let { st -> bestEffort("latest observation") { nwsApi.getLatestObservationDetailed(st.id) } }
        }
        val historicalObsDeferred = async {
            station?.let { st ->
                bestEffort("historical observations") {
                    // NWS returns ZERO observations when start/end carry fractional seconds (HTTP 200,
                    // empty body — not an error), so truncate to whole seconds before formatting.
                    val end = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                    val start = end.minus(HISTORY_DAYS, ChronoUnit.DAYS)
                    nwsApi.getObservations(st.id, start.toString(), end.toString()).also { obs ->
                        // Empty-but-no-exception is the failure mode to watch (see fractional-seconds
                        // note); log the count so a silently-empty actuals pipeline is visible.
                        Log.i(TAG, "historical observations: station=${st.id} count=${obs.size}")
                    }
                }
            } ?: emptyList()
        }

        val hourlyRaw = hourlyDeferred.await()
        val dailyRaw = dailyDeferred.await()
        val currentObs = currentObsDeferred.await()
        val historicalObs = historicalObsDeferred.await()

        val stationId = station?.id ?: "NWS"
        val stationName = station?.name ?: currentObs?.stationName ?: stationId

        // Latest detailed reading + the historical window, mapped to the pure model type. The DB
        // dedups by (stationId, timestamp), so an overlapping latest reading is harmless.
        val observations = buildList {
            currentObs?.let { add(it.toReading(stationId, stationName)) }
            historicalObs.forEach { add(it.toReading(stationId, stationName)) }
        }

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

    /**
     * Runs a best-effort supplementary fetch. Failures degrade to null (callers fall back to the
     * hourly/daily feeds) and are logged, but [CancellationException] is rethrown so coroutine
     * cancellation and structured concurrency keep working — a bare `catch (Exception)` would
     * swallow it and break cancellation.
     */
    private inline fun <T> bestEffort(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what fetch failed: $e")
            null
        }

    private fun NwsApi.Observation.toReading(stationId: String, stationName: String) = ObservationReading(
        stationId = stationId,
        stationName = this.stationName.ifBlank { stationName },
        timestamp = try { ZonedDateTime.parse(timestamp).toInstant().toEpochMilli() } catch (e: Exception) { System.currentTimeMillis() },
        temperature = (temperatureCelsius * 1.8f) + 32f,
        condition = textDescription,
        locationLat = latitude,
        locationLon = longitude,
        api = "NWS",
        precipAmountMm = precipLastHourMm,
        maxTempLast24h = maxTempLast24hCelsius?.let { (it * 1.8f) + 32f },
        minTempLast24h = minTempLast24hCelsius?.let { (it * 1.8f) + 32f },
    )

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

        // How far back to pull observations for the actuals / accuracy pipeline.
        const val HISTORY_DAYS = 7L

        private const val TAG = "DesktopWeatherService"
    }
}
