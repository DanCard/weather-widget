package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.*
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.util.SpatialInterpolator
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
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.*

/**
 * Thin desktop-side orchestration over the shared API clients. Supports all major sources
 * used by the Android widget.
 */
class DesktopWeatherService(
    private val latitude: Double,
    private val longitude: Double,
    private val weatherSource: String = "NWS",
    private val apiKeys: Map<String, String> = emptyMap(),
    private val weatherDao: DesktopWeatherDao? = null,
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
        // Raw gridpoints supply per-date min/max extremes that backstop the day/night periods —
        // notably the final forecast day, whose overnight low is otherwise absent. Best-effort:
        // on failure the daily mapping falls back to whatever the periods provide.
        val gridpointsDeferred = async {
            bestEffort("gridpoints") { nwsApi.getGridpointsBundle(grid) }
                ?: NwsApi.GridpointsBundle(
                    skyCoverByHour = emptyMap(),
                    qpfIntervals = emptyList(),
                    dailyTemperatures = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap()),
                )
        }

        // Resolve candidate observation stations once, then try official stations first for the
        // historical window that drives actuals. Observation fetches are best-effort: if they fail
        // the forecast still renders from hourly/daily feeds.
        val stationsDeferred = async {
            bestEffort("observation stations") {
                grid.observationStationsUrl?.let { url -> getCachedOrFetchStations(url) }
            } ?: emptyList()
        }

        val hourlyRaw = hourlyDeferred.await()
        val dailyRaw = dailyDeferred.await()
        val gridpoints = gridpointsDeferred.await()
        // The hourly endpoint omits sky cover + grid QPF; merge them on via the shared helper so
        // the cloud-cover graph (and grid precip) match Android. Without this, every NWS hourly
        // row has cloudCover=null and the cloud graph collapses to a flat zero line.
        val hourly = NwsHourlyGridMerge.applyGridpointData(
            hourlyRaw, gridpoints.skyCoverByHour, gridpoints.qpfIntervals,
        )
        val bundles = fetchObservationBundles(stationsDeferred.await())

        // All station latest readings, including moderately stale ones — IDW applies its own
        // time-decay weighting (1 - age/3h) so older stations reduce their own contribution
        // automatically. Matching Android which uses a 3-hour staleness window rather than 30 min.
        val allLatestReadings = bundles.mapNotNull { bundle ->
            bundle.latest?.toReading(bundle.station)
        }
        // Subset used to decide whether a fresh observed temp is available for the header.
        val freshLatestReadings = allLatestReadings.filter {
            System.currentTimeMillis() - it.timestamp <= FRESH_OBSERVATION_MS
        }

        val currentTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, allLatestReadings)
            ?: TemperatureInterpolator.getInterpolatedTemperature(hourlyRaw.map { it.toHourlyForecast() })
            ?: hourlyRaw.firstOrNull()?.temperature

        val closestBundle = bundles.minByOrNull { distanceKm(latitude, longitude, it.station.lat, it.station.lon) }
        val currentCondition = closestBundle?.latest?.textDescription
            ?: hourlyRaw.firstOrNull()?.shortForecast

        // All readings (latest + historical) from all successful stations
        val rawObservations = bundles.flatMap { bundle ->
            buildList {
                bundle.latest?.let { add(it.toReading(bundle.station)) }
                bundle.historical.forEach { add(it.toReading(bundle.station)) }
            }
        }

        // Add NWS_BLEND synthetic reading if we successfully blended. Matches Android parity
        // and ensures the graph and header use the same weighted truth.
        // Timestamp anchored to freshLatestReadings so the header freshness gate works correctly.
        val latestReadings = freshLatestReadings.ifEmpty { allLatestReadings }
        val observations = if (currentTemp != null && latestReadings.isNotEmpty()) {
            val newestMs = latestReadings.maxOf { it.timestamp }
            rawObservations + ObservationReading(
                stationId = "NWS_BLEND",
                stationName = "NWS Blended",
                timestamp = newestMs,
                temperature = currentTemp,
                condition = currentCondition ?: "none",
                locationLat = latitude,
                locationLon = longitude,
                distanceKm = 0f,
                stationType = "VIRTUAL",
                api = "NWS"
            )
        } else {
            rawObservations
        }

        ForecastResult(
            currentTemp = currentTemp,
            currentCondition = currentCondition,
            currentObservedAt = latestReadings.maxOfOrNull { it.timestamp } ?: observations.firstOrNull()?.timestamp,
            hourly = hourly.map { it.toHourlyForecast() },
            daily = NwsDailyMapper.buildDailyForecasts(dailyRaw, gridpoints.dailyTemperatures, LocalDate.now()),
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

    private suspend fun getCachedOrFetchStations(stationsUrl: String): List<NwsApi.StationInfo> {
        val cacheKey = "nws_stations_${stationsUrl.hashCode()}"
        weatherDao?.getCachedStations(cacheKey, STATION_CACHE_MS)?.let { cached ->
            if (cached.isNotEmpty()) return cached
        }

        val fetched = nwsApi.getObservationStations(stationsUrl)
        if (fetched.isNotEmpty()) {
            weatherDao?.upsertStationCache(cacheKey, fetched)
        }
        return fetched
    }

    private suspend fun fetchObservationBundles(stations: List<NwsApi.StationInfo>): List<ObservationBundle> = coroutineScope {
        val end = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val start = end.minus(HISTORY_DAYS, ChronoUnit.DAYS)
        
        val deferreds = stations.take(MAX_OBSERVATION_STATIONS).map { station ->
            async {
                val historical = bestEffort("historical observations ${station.id}") {
                    nwsApi.getObservations(station.id, start.toString(), end.toString()).also { obs ->
                        Log.i(TAG, "historical observations: station=${station.id} type=${station.type} count=${obs.size}")
                    }
                }.orEmpty()
                
                if (historical.isNotEmpty()) {
                    val latest = bestEffort("latest observation ${station.id}") {
                        nwsApi.getLatestObservationDetailed(station.id)
                    }
                    Log.i(TAG, "selected observation station=${station.id} type=${station.type} historical=${historical.size} latest=${latest != null}")
                    ObservationBundle(station, latest, historical)
                } else {
                    null
                }
            }
        }
        deferreds.mapNotNull { it.await() }
    }

    private data class ObservationBundle(
        val station: NwsApi.StationInfo,
        val latest: NwsApi.Observation?,
        val historical: List<NwsApi.Observation>,
    )

    private fun NwsApi.Observation.toReading(station: NwsApi.StationInfo) = ObservationReading(
        stationId = station.id,
        stationName = this.stationName.ifBlank { station.name },
        timestamp = try { ZonedDateTime.parse(timestamp).toInstant().toEpochMilli() } catch (e: Exception) { System.currentTimeMillis() },
        temperature = (temperatureCelsius * 1.8f) + 32f,
        condition = textDescription,
        locationLat = latitude,
        locationLon = longitude,
        distanceKm = distanceKm(latitude, longitude, station.lat, station.lon).toFloat(),
        stationType = station.type.name,
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
        cloudCover = cloudCover,
        source = "NWS",
    )

    suspend fun fetchObservationsOnly(): ForecastResult = runCatching {
        when (weatherSource) {
            "NWS" -> fetchNwsObservationsOnly()
            WeatherSource.OPEN_METEO.id -> fetchOpenMeteoObservationsOnly()
            WeatherSource.TOMORROW_IO.id,
            WeatherSource.WEATHER_API.id,
            WeatherSource.VISUAL_CROSSING.id,
            WeatherSource.SILURIAN.id,
            WeatherSource.OPEN_WEATHER_MAP.id -> {
                Log.i(TAG, "Skipping observations-only refresh for $weatherSource; no current-only desktop path is defined")
                ForecastResult()
            }
            else -> fetchOpenMeteoObservationsOnly()
        }
    }.getOrElse { e ->
        if (weatherSource == "NWS") {
            fetchOpenMeteoObservationsOnly()
        } else {
            throw e
        }
    }

    private suspend fun fetchNwsObservationsOnly(): ForecastResult = coroutineScope {
        val grid = nwsApi.getGridPoint(latitude, longitude)
        
        // Resolve candidate observation stations once, then try official stations first.
        val stations = bestEffort("observation stations") {
            grid.observationStationsUrl?.let { url -> getCachedOrFetchStations(url) }
        } ?: emptyList()

        val bundles = fetchObservationBundles(stations)
        
        val allLatestReadings = bundles.mapNotNull { bundle ->
            bundle.latest?.toReading(bundle.station)
        }
        val freshLatestReadings = allLatestReadings.filter {
            System.currentTimeMillis() - it.timestamp <= FRESH_OBSERVATION_MS
        }

        val currentTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, allLatestReadings)
            ?: bundles.minByOrNull { distanceKm(latitude, longitude, it.station.lat, it.station.lon) }?.latest?.let {
                (it.temperatureCelsius * 1.8f) + 32f
            }

        val closestBundle = bundles.minByOrNull { distanceKm(latitude, longitude, it.station.lat, it.station.lon) }
        val currentCondition = closestBundle?.latest?.textDescription

        // All readings (latest + historical) from all successful stations
        val rawObservations = bundles.flatMap { bundle ->
            buildList {
                bundle.latest?.let { add(it.toReading(bundle.station)) }
                bundle.historical.forEach { add(it.toReading(bundle.station)) }
            }
        }

        val latestReadings = freshLatestReadings.ifEmpty { allLatestReadings }
        val observations = if (currentTemp != null && latestReadings.isNotEmpty()) {
            val newestMs = latestReadings.maxOf { it.timestamp }
            rawObservations + ObservationReading(
                stationId = "NWS_BLEND",
                stationName = "NWS Blended",
                timestamp = newestMs,
                temperature = currentTemp,
                condition = currentCondition ?: "none",
                locationLat = latitude,
                locationLon = longitude,
                distanceKm = 0f,
                stationType = "VIRTUAL",
                api = "NWS"
            )
        } else {
            rawObservations
        }

        ForecastResult(
            currentTemp = currentTemp,
            currentCondition = currentCondition,
            currentObservedAt = latestReadings.maxOfOrNull { it.timestamp } ?: observations.firstOrNull()?.timestamp,
            rawObservations = observations
        )
    }

    private suspend fun fetchOpenMeteoObservationsOnly(): ForecastResult = coroutineScope {
        val reading = openMeteo.getCurrent(latitude, longitude)
            ?: throw Exception("Open-Meteo current reading is null")
        val condition = reading.weatherCode?.let { openMeteo.weatherCodeToCondition(it) } ?: "Unknown"
        val obsReading = ObservationReading(
            stationId = "OPEN_METEO_MAIN",
            stationName = "Meteo: Current",
            timestamp = reading.observedAt ?: System.currentTimeMillis(),
            temperature = reading.temperature,
            condition = condition,
            locationLat = latitude,
            locationLon = longitude,
            api = WeatherSource.OPEN_METEO.id,
        )
        ForecastResult(
            currentTemp = reading.temperature,
            currentCondition = condition,
            currentObservedAt = reading.observedAt,
            rawObservations = listOf(obsReading)
        )
    }

    fun close() = httpClient.close()

    companion object {
        const val FALLBACK_LATITUDE = 37.4220
        const val FALLBACK_LONGITUDE = -122.0841

        // How far back to pull observations for the actuals / accuracy pipeline.
        const val HISTORY_DAYS = 7L

        private const val TAG = "DesktopWeatherService"
        private const val MAX_OBSERVATION_STATIONS = 5
        private const val STATION_CACHE_MS = 24 * 60 * 60 * 1000L
        internal const val FRESH_OBSERVATION_MS = 30 * 60 * 1000L
    }
}



private fun NwsApi.Observation.isFreshObservation(nowMs: Long = System.currentTimeMillis()): Boolean {
    val observedAt = runCatching { ZonedDateTime.parse(timestamp).toInstant().toEpochMilli() }.getOrNull()
        ?: return false
    return nowMs - observedAt <= DesktopWeatherService.FRESH_OBSERVATION_MS
}

private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
}
