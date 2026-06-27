package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.*
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill
import com.weatherwidget.shared.config.ForecastHorizon
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
        install(HttpRequestRetry) {
            maxRetries = 2
            retryOnExceptionIf { _, cause ->
                cause is io.ktor.client.network.sockets.ConnectTimeoutException ||
                cause is io.ktor.client.network.sockets.SocketTimeoutException ||
                cause is io.ktor.client.plugins.HttpRequestTimeoutException ||
                cause is java.io.IOException
            }
            exponentialDelay(base = 2.0, maxDelayMs = 2_000)
        }
    }

    // Build-time keys (from local.properties / env, baked in via DesktopApiKeys) provide the default,
    // and a key entered in desktop Settings (config.apiKeys) overrides it — same precedence as
    // Android's `widgetStateManager.getApiKey(...) ?: BuildConfig.<SOURCE>_API_KEY`. Blank config
    // values are ignored so they can't wipe a baked-in key. These keys are never written back to
    // config.json (the store only persists config.apiKeys).
    private val effectiveKeys: Map<String, String> =
        DesktopApiKeys.DEFAULTS + apiKeys.filterValues { it.isNotBlank() }

    private val openMeteo = OpenMeteoApi(httpClient, json)
    private val nwsApi = NwsApi(httpClient, json)
    private val tomorrowIo = TomorrowIoApi(httpClient, json) { effectiveKeys[WeatherSource.TOMORROW_IO.id] }
    private val weatherApi = WeatherApi(httpClient, json) { effectiveKeys[WeatherSource.WEATHER_API.id] }
    private val visualCrossing = VisualCrossingApi(httpClient, json) { effectiveKeys[WeatherSource.VISUAL_CROSSING.id] }
    private val silurian = SilurianApi(httpClient, json) { effectiveKeys[WeatherSource.SILURIAN.id] }
    private val openWeatherMap = OpenWeatherMapApi(httpClient, json) { effectiveKeys[WeatherSource.OPEN_WEATHER_MAP.id] }

    constructor(config: DesktopConfig?) : this(
        latitude = config?.lat ?: FALLBACK_LATITUDE,
        longitude = config?.lon ?: FALLBACK_LONGITUDE,
        weatherSource = config?.weatherSource ?: "NWS",
        apiKeys = config?.apiKeys ?: emptyMap()
    )

    /**
     * @param forecastDays how many days of daily forecast to request from Open-Meteo (inclusive of
     *   today). Defaults to [ForecastHorizon.BASELINE_DAYS]; the daily view's on-demand extension
     *   passes a larger value (up to [ForecastHorizon.MAX_DAYS]) when the user navigates past the
     *   baseline edge. Only the Open-Meteo path honours it — NWS and the other sources cap near a
     *   week of their own accord.
     */
    suspend fun fetchForecast(forecastDays: Int = ForecastHorizon.BASELINE_DAYS): ForecastResult = runCatching {
        when (weatherSource) {
            "NWS" -> fetchNwsForecast()
            WeatherSource.TOMORROW_IO.id -> withHistoricalActuals(tomorrowIo.getForecast(latitude, longitude), WeatherSource.TOMORROW_IO.id)
            WeatherSource.WEATHER_API.id -> withHistoricalActuals(weatherApi.getForecast(latitude, longitude), WeatherSource.WEATHER_API.id)
            WeatherSource.VISUAL_CROSSING.id -> withHistoricalActuals(visualCrossing.getForecast(latitude, longitude), WeatherSource.VISUAL_CROSSING.id)
            WeatherSource.SILURIAN.id -> withHistoricalActuals(silurian.getForecast(latitude, longitude), WeatherSource.SILURIAN.id)
            WeatherSource.OPEN_WEATHER_MAP.id -> withHistoricalActuals(openWeatherMap.getForecast(latitude, longitude), WeatherSource.OPEN_WEATHER_MAP.id)
            else -> fetchOpenMeteoForecastWithActuals(forecastDays)
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        when (weatherSource) {
            // NWS has no coverage outside the US, so Open-Meteo is the intended substitute there.
            // This is the one legitimate cross-source fallback — but log it so it is never silent.
            "NWS" -> {
                weatherDao?.log("SOURCE_FALLBACK", "NWS unavailable, substituting Open-Meteo: ${e.message}", "WARN")
                fetchOpenMeteoForecastWithActuals(forecastDays)
            }
            // Open-Meteo itself has nowhere to fall back to.
            WeatherSource.OPEN_METEO.id -> throw e
            // Any other explicitly-selected source: do NOT silently relabel Open-Meteo data as that
            // source (that masks a missing key / outage and shows the wrong provider's numbers).
            // Surface the failure instead — the refresh loop logs REFRESH_FAIL and updates DataStatus.
            else -> {
                val hasKey = effectiveKeys[weatherSource]?.isNotBlank() == true
                val hint = if (!hasKey && WeatherSource.fromId(weatherSource) != WeatherSource.OPEN_METEO) {
                    " (no API key configured for $weatherSource — set it in local.properties or Settings)"
                } else ""
                weatherDao?.log("SOURCE_ERROR", "$weatherSource fetch failed; not masquerading as Open-Meteo$hint: ${e.message}", "WARN")
                throw e
            }
        }
    }

    /**
     * Backfills the historical-actuals observations that drive the graph's pink actual line for any
     * non-NWS source. These sources have no station observations of their own, so without this their
     * past hours are never filed as observations and the actual line stays empty (the bug NWS never
     * had). The shared helper re-files the past slice of the source's hourly list as observation
     * rows — matching Android, which routes every source through saveHistoricalActuals. NWS is the
     * one exception: it supplies real station readings in fetchNwsForecast and must not be backfilled.
     */
    private fun withHistoricalActuals(result: ForecastResult, sourceId: String): ForecastResult =
        result.copy(
            rawObservations = HistoricalActualsBackfill.build(
                hourly = result.hourly,
                latitude = latitude,
                longitude = longitude,
                sourceId = sourceId,
                nowMs = System.currentTimeMillis(),
            ),
        )

    /**
     * Open-Meteo forecast plus the historical-actuals backfill. Open-Meteo additionally needs
     * [ACTUALS_HISTORY_DAYS] of `past_days` so its hourly list actually contains the past hours the
     * backfill re-files (other sources return their recent past inline).
     *
     * The backfill is labeled with the service's display [weatherSource], not OPEN_METEO: this method
     * is also the failure fallback for every other source (see fetchForecast's getOrElse), and
     * refresh() stores the fetched forecast under the display source. Labeling the actuals the same
     * way keeps them matched to the (display-labeled) forecast so the actual line still renders when a
     * source falls back to Open-Meteo. When Open-Meteo is itself the active source the two ids are
     * identical, so this is a no-op there.
     */
    private suspend fun fetchOpenMeteoForecastWithActuals(
        forecastDays: Int = ForecastHorizon.BASELINE_DAYS,
    ): ForecastResult =
        withHistoricalActuals(
            openMeteo.getForecast(latitude, longitude, days = forecastDays, historyDays = ACTUALS_HISTORY_DAYS),
            weatherSource,
        )

    suspend fun fetchHistory(historyDays: Int): ForecastResult {
        return openMeteo.getForecast(latitude, longitude, days = 1, historyDays = historyDays)
    }

    /**
     * On-demand deep history of NWS station observations for the graph's pink actual line, used when
     * the user zooms/pans the hourly graph past the [HISTORY_DAYS] window the normal forecast fetch
     * covers. Resolves the same observation stations as [fetchNwsForecast] and only widens the
     * historical window — the station set (≤ [MAX_OBSERVATION_STATIONS]) and the number of API calls
     * are unchanged; each station call simply returns more rows. Returns the flattened station
     * readings (latest + historical) for persistence. Best-effort: empty list on any failure, so a
     * deep zoom while NWS is down degrades to the Open-Meteo fallback curve rather than crashing.
     */
    suspend fun fetchObservationHistory(historyDays: Long): List<ObservationReading> = coroutineScope {
        val grid = bestEffort("gridpoint for obs history") { nwsApi.getGridPoint(latitude, longitude) }
            ?: return@coroutineScope emptyList<ObservationReading>()
        val stations = bestEffort("observation stations for obs history") {
            grid.observationStationsUrl?.let { url -> getCachedOrFetchStations(url) }
        } ?: emptyList()
        fetchObservationBundles(stations, historyDays).flatMap { bundle ->
            buildList {
                bundle.latest?.let { add(it.toReading(bundle.station)) }
                bundle.historical.forEach { add(it.toReading(bundle.station)) }
            }
        }
    }

    /** Open-Meteo archive (ERA5) daily highs/lows over [startDate, endDate], for climate normals. */
    suspend fun fetchHistoricalDailyTemps(startDate: String, endDate: String): List<DailyForecast> {
        return openMeteo.getHistoricalDailyTemps(latitude, longitude, startDate, endDate)
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

    private suspend fun fetchObservationBundles(
        stations: List<NwsApi.StationInfo>,
        historyDays: Long = HISTORY_DAYS,
    ): List<ObservationBundle> = coroutineScope {
        val end = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val start = end.minus(historyDays, ChronoUnit.DAYS)
        
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

        // past_days window for the Open-Meteo actuals backfill — matches the graph's 6-day
        // full zoom-out so the actual line spans the same range as the forecast line.
        const val ACTUALS_HISTORY_DAYS = 7

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
