package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.*
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.shared.observations.LatestObservationMerge
import com.weatherwidget.shared.observations.NwsObservationMapper
import com.weatherwidget.shared.observations.ObservationFallbackPolicy
import com.weatherwidget.shared.config.ForecastHorizon
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.util.SpatialInterpolator
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.*
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.observations.MetarObservationFetcher
import com.weatherwidget.shared.observations.SynopticObservationFetcher
import com.weatherwidget.shared.observations.MetarRawSkyParser
import com.weatherwidget.data.remote.AviationWeatherApi

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
    // Injectable seams for tests; null = construct the real production client/apis.
    private val injectedHttpClient: HttpClient? = null,
    private val injectedNwsApi: NwsApi? = null,
    private val injectedSynopticApi: SynopticApi? = null,
) : WeatherApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient: HttpClient = injectedHttpClient ?: HttpClient(CIO) {
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
    // and a key entered in desktop Settings (config.settings.apiKeys) overrides it — same precedence as
    // Android's `widgetStateManager.getApiKey(...) ?: BuildConfig.<SOURCE>_API_KEY`. Blank config
    // values are ignored so they can't wipe a baked-in key. These keys are never written back to
    // config.json (the store only persists config.settings.apiKeys).
    private val effectiveKeys: Map<String, String> =
        DesktopApiKeys.DEFAULTS + apiKeys.filterValues { it.isNotBlank() }

    private val openMeteo = OpenMeteoApi(httpClient, json)
    private val nwsApi = injectedNwsApi ?: NwsApi(httpClient, json)
    private val tomorrowIo = TomorrowIoApi(httpClient, json) { effectiveKeys[WeatherSource.TOMORROW_IO.id] }
    private val weatherApi = WeatherApi(httpClient, json) { effectiveKeys[WeatherSource.WEATHER_API.id] }
    private val visualCrossing = VisualCrossingApi(httpClient, json) { effectiveKeys[WeatherSource.VISUAL_CROSSING.id] }
    private val silurian = SilurianApi(httpClient, json) { effectiveKeys[WeatherSource.SILURIAN.id] }
    private val openWeatherMap = OpenWeatherMapApi(httpClient, json) { effectiveKeys[WeatherSource.OPEN_WEATHER_MAP.id] }
    // "SYNOPTIC" is not a WeatherSource id — it is the NWS web-fallback transport, keyed by a token
    // minted from the API key. It rides the same baked-keys map purely for the plumbing.
    private val synopticApi = injectedSynopticApi
        ?: SynopticApi(httpClient, json) { effectiveKeys["SYNOPTIC"] }
    private val aviationWeatherApi = AviationWeatherApi(httpClient, json)

    /**
     * METAR fetching, shared verbatim with Android.
     *
     * The station cache is per-process rather than persisted: desktop's only config file is JSON
     * with known write races (`desktop_config_write_races`), and re-running one bbox discovery per
     * process start is cheaper than adding another writer to it. Airports do not move, so the
     * 24-hour TTL inside the fetcher still does the real work within a session.
     */
    private val metarStationCache = object : MetarObservationFetcher.StationCache {
        private val entries = java.util.concurrent.ConcurrentHashMap<String, MetarObservationFetcher.StationCache.Entry>()
        override fun read(key: String) = entries[key]
        override fun write(key: String, encoded: String, savedAtMs: Long) {
            entries[key] = MetarObservationFetcher.StationCache.Entry(encoded, savedAtMs)
        }
    }
    private val metarFetcher = MetarObservationFetcher(aviationWeatherApi, metarStationCache) { tag, message, level ->
        // Desktop has no app_logs DAO on this path; route to the same Log sink the rest of the
        // service uses so METAR_FETCH/METAR_STATIONS stay greppable exactly as on Android.
        when (level) {
            "WARN" -> Log.w(TAG, "$tag $message")
            else -> Log.i(TAG, "$tag $message")
        }
    }
    private val synopticFetcher = SynopticObservationFetcher(synopticApi) { tag, message, level ->
        when (level) {
            "WARN" -> Log.w(TAG, "$tag $message")
            else -> Log.i(TAG, "$tag $message")
        }
    }

    constructor(config: DesktopConfig) : this(
        latitude = config.lat,
        longitude = config.lon,
        weatherSource = config.settings.weatherSource,
        apiKeys = config.settings.apiKeys
    )

    /**
     * Every fetch requests the maximum horizon ([ForecastHorizon.MAX_DAYS]); only the Open-Meteo
     * path honours the number — the other sources return whatever their API provides, and days
     * past a source's real coverage render climate-normal filler by design.
     */
    override suspend fun fetchForecast(): RawFetch = runCatching {
        when (weatherSource) {
            "NWS" -> fetchNwsForecast()
            WeatherSource.TOMORROW_IO.id -> fetchTomorrowIoForecastWithRealtime()
            WeatherSource.WEATHER_API.id -> withHistoricalActuals(weatherApi.getForecast(latitude, longitude), WeatherSource.WEATHER_API.id)
            WeatherSource.VISUAL_CROSSING.id -> withHistoricalActuals(visualCrossing.getForecast(latitude, longitude), WeatherSource.VISUAL_CROSSING.id)
            WeatherSource.SILURIAN.id -> withHistoricalActuals(silurian.getForecast(latitude, longitude), WeatherSource.SILURIAN.id)
            WeatherSource.OPEN_WEATHER_MAP.id -> fetchOpenWeatherMapForecastWithCurrent()
            WeatherSource.OPEN_METEO.id -> fetchOpenMeteoForecast()
            else -> throw IllegalArgumentException("Unsupported weather source: $weatherSource")
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        when (weatherSource) {
            // Open-Meteo itself has nowhere to fall back to.
            WeatherSource.OPEN_METEO.id -> throw e
            // EVERY explicitly-selected source, NWS included: do NOT silently relabel Open-Meteo data
            // as that source (that masks a missing key / outage and shows the wrong provider's
            // numbers). Surface the failure — the refresh loop logs REFRESH_FAIL and updates
            // DataStatus, so the UI shows cached data with a staleness indicator.
            //
            // NWS used to be exempt here, on the rationale that it has no coverage outside the US and
            // Open-Meteo is the intended substitute there. But this `getOrElse` catches EVERY failure,
            // so transient ones ("Channel was closed", request timeouts, truncated chunks) substituted
            // too: 222 SOURCE_FALLBACK events fired at a US location that NWS covers fine. Each one
            // wrote Open-Meteo forecast temperatures into `observations` as NWS_MAIN rows, which then
            // hijacked the actual-temperature blend — see
            // plans/260802-desktop-nws-main-backfill-hijacks-blend.md.
            //
            // Genuine out-of-coverage is a DIFFERENT, already-detected condition:
            // NwsApi throws NwsPointUnavailableException only on a 404 /points response that
            // isUnsupportedPointProblem. Android acts on precisely that distinction at source-selection
            // time (SetupSourceAvailabilityChecker separates UNSUPPORTED from INCONCLUSIVE) rather
            // than substituting at fetch time, and desktop now matches.
            else -> {
                val hasKey = effectiveKeys[weatherSource]?.isNotBlank() == true
                // Keyless sources (NWS, Open-Meteo) must not be blamed on a missing key — NWS reaches
                // this branch now that it is no longer exempt from the no-masquerading rule.
                val hint = if (!hasKey && WeatherSource.fromId(weatherSource).requiresApiKey) {
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
    private fun withHistoricalActuals(result: RawFetch, sourceId: String): RawFetch =
        result.copy(
            rawObservations = result.rawObservations + HistoricalActualsBackfill.build(
                // Prefer a provider's native sub-hour actual product when its source contract
                // permits historical actuals; forecast-only sources are rejected by the builder.
                hourly = result.subHourly.ifEmpty { result.hourly },
                latitude = latitude,
                longitude = longitude,
                sourceId = sourceId,
                nowMs = System.currentTimeMillis(),
            ),
        )

    private suspend fun fetchTomorrowIoForecastWithRealtime(): RawFetch = coroutineScope {
        val forecastDeferred = async { tomorrowIo.getForecast(latitude, longitude) }
        val realtimeDeferred = async {
            try {
                tomorrowIo.getRealtime(latitude, longitude)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Tomorrow.io realtime fetch failed during full refresh: $e")
                null
            }
        }
        val forecast = forecastDeferred.await()
        val realtime = realtimeDeferred.await()
        val withHistory = withHistoricalActuals(forecast, WeatherSource.TOMORROW_IO.id)
        if (realtime == null) {
            withHistory
        } else {
            withHistory.copy(
                rawObservations = withHistory.rawObservations + listOf(
                    TomorrowIoActuals.toObservation(realtime, latitude, longitude),
                ),
                providerCurrentTemp = realtime.temperature,
                providerCurrentCondition = realtime.condition,
                providerCurrentObservedAt = realtime.observedAt,
            )
        }
    }

    private suspend fun fetchOpenWeatherMapForecastWithCurrent(): RawFetch {
        val forecast = openWeatherMap.getForecast(latitude, longitude)
        val withHistory = withHistoricalActuals(forecast, WeatherSource.OPEN_WEATHER_MAP.id)
        val currentTemp = forecast.providerCurrentTemp
        return if (currentTemp == null) {
            withHistory
        } else {
            val observation = ObservationReading(
                stationId = "OPEN_WEATHER_MAP_MAIN",
                stationName = "OWM: Current",
                timestamp = forecast.providerCurrentObservedAt ?: System.currentTimeMillis(),
                temperature = currentTemp,
                condition = forecast.providerCurrentCondition ?: "Unknown",
                locationLat = latitude,
                locationLon = longitude,
                api = WeatherSource.OPEN_WEATHER_MAP.id,
            )
            withHistory.copy(
                rawObservations = withHistory.rawObservations + listOf(observation),
            )
        }
    }

    /** Open-Meteo Forecast API with current observation and historical actuals backfill. */
    private suspend fun fetchOpenMeteoForecast(): RawFetch {
        val forecast = openMeteo.getForecast(
            latitude,
            longitude,
            days = ForecastHorizon.MAX_DAYS,
            historyDays = ACTUALS_HISTORY_DAYS,
        )
        val observation = forecast.providerCurrentTemp?.let { temp ->
            val timestamp = forecast.providerCurrentObservedAt ?: System.currentTimeMillis()
            ObservationReading(
                stationId = "OPEN_METEO_MAIN",
                stationName = "Open-Meteo Current",
                timestamp = timestamp,
                temperature = temp,
                condition = forecast.providerCurrentCondition ?: "Unknown",
                locationLat = latitude,
                locationLon = longitude,
                distanceKm = 0f,
                stationType = "OFFICIAL",
                api = WeatherSource.OPEN_METEO.id,
                cloudCover = forecast.providerCurrentCloudCover,
                cloudCoverLow = forecast.providerCurrentCloudCoverLow,
                cloudCoverMid = forecast.providerCurrentCloudCoverMid,
                cloudCoverHigh = forecast.providerCurrentCloudCoverHigh,
                cloudVerticalKind = if (
                    forecast.providerCurrentCloudCoverLow != null ||
                    forecast.providerCurrentCloudCoverMid != null ||
                    forecast.providerCurrentCloudCoverHigh != null
                ) {
                    CloudVerticalKind.PROVIDER_BANDS
                } else {
                    CloudVerticalKind.NONE
                },
            )
        }
        val withHistory = withHistoricalActuals(forecast, WeatherSource.OPEN_METEO.id)
        return withHistory.copy(
            rawObservations = withHistory.rawObservations + if (observation != null) listOf(observation) else emptyList(),
        )
    }

    /**
     * Open-Meteo's Previous Runs API: what each elapsed hour was forecast to be ~24h beforehand.
     *
     * Only meaningful under Open-Meteo, so it is gated on the display source rather than fetched
     * unconditionally — a call spent on a graph nobody is looking at is a call wasted, and every
     * other source would get an empty map anyway. Best-effort: any failure returns empty and the
     * cloud graph falls back to the live value with `isFrozen = false`.
     */
    override suspend fun fetchPriorDayCloudForecast(pastDays: Int): Map<Long, Int> {
        if (weatherSource != WeatherSource.OPEN_METEO.id) return emptyMap()
        return bestEffort("prior-day cloud forecast") {
            openMeteo.getPriorDayCloudForecast(latitude, longitude, pastDays, System.currentTimeMillis())
        } ?: emptyMap()
    }

    override suspend fun fetchHistory(historyDays: Int): RawFetch {
        return openMeteo.getForecast(latitude, longitude, days = 1, historyDays = historyDays)
    }

    override suspend fun fetchWeatherApiHistory(date: LocalDate): RawFetch =
        withHistoricalActuals(
            weatherApi.getHistory(latitude, longitude, date),
            WeatherSource.WEATHER_API.id,
        )

    /**
     * On-demand deep history of NWS station observations for the graph's pink actual line, used when
     * the user zooms/pans the hourly graph past the [HISTORY_DAYS] window the normal forecast fetch
     * covers. Resolves the same observation stations as [fetchNwsForecast] and only widens the
     * historical window — the station set (≤ [MAX_OBSERVATION_STATIONS]) and the number of API calls
     * are unchanged; each station call simply returns more rows. Returns the flattened station
     * readings (latest + historical) for persistence. Best-effort: empty list on any failure, so a
     * deep zoom while NWS is down degrades to the Open-Meteo fallback curve rather than crashing.
     */
    override suspend fun fetchObservationHistory(historyDays: Long): List<ObservationReading> = coroutineScope {
        val grid = bestEffort("gridpoint for obs history") { nwsApi.getGridPoint(latitude, longitude) }
            ?: return@coroutineScope emptyList<ObservationReading>()
        val stations = bestEffort("observation stations for obs history") {
            grid.observationStationsUrl?.let { url -> getCachedOrFetchStations(url) }
        } ?: emptyList()
        fetchObservationBundles(stations, historyDays).flatMap { bundle ->
            buildList {
                bundle.latest?.let { add(it.toReading(bundle.station, bundle.latestIsWeb)) }
                bundle.historical.forEach { add(it.toReading(bundle.station, bundle.historicalIsWeb)) }
            }
        }
    }

    /**
     * Raw `api.weather.gov/stations/{id}/observations?start=&end=` series for the nearest stations,
     * with no Synoptic substitution. Backs the NWS daily-extreme pull, which must read NWS's own
     * measurements only — [fetchObservationHistory] deliberately mixes in web readings.
     * Best-effort per station: a failed station is skipped, so the nearest-official rule falls
     * through to the next one.
     */
    /** Nearest stations including personal ones — the history blend interpolates across all. */
    override suspend fun nearestStationsForDailyActuals(): List<NwsApi.StationInfo> {
        val grid = bestEffort("gridpoint for daily extremes") { nwsApi.getGridPoint(latitude, longitude) }
            ?: return emptyList()
        val stations = bestEffort("observation stations for daily extremes") {
            grid.observationStationsUrl?.let { url -> getCachedOrFetchStations(url) }
        } ?: emptyList()
        return stations.take(MAX_OBSERVATION_STATIONS)
    }

    /**
     * One station, one calendar day. Returns **null** when the request failed and an empty list
     * when it answered with nothing — the caller keeps those apart so a network blip cannot
     * masquerade as an incomplete day.
     */
    override suspend fun fetchApiObservationDay(
        station: NwsApi.StationInfo,
        startIso: String,
        endIso: String,
    ): List<ObservationReading>? =
        try {
            nwsApi.getObservations(station.id, startIso, endIso).map { it.toReading(station) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "daily-extreme day ${station.id} failed: $e")
            null
        }

    /** Open-Meteo archive (ERA5) daily highs/lows over [startDate, endDate], for climate normals. */
    override suspend fun fetchHistoricalDailyTemps(startDate: String, endDate: String): List<DailyForecast> {
        return openMeteo.getHistoricalDailyTemps(latitude, longitude, startDate, endDate)
    }

    private suspend fun fetchNwsForecast(): RawFetch = coroutineScope {
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
            bundle.latest?.toReading(bundle.station, bundle.latestIsWeb)
        }
        // Subset used to decide whether a fresh observed temp is available for the header.
        val freshLatestReadings = allLatestReadings.filter {
            System.currentTimeMillis() - it.timestamp <= FRESH_OBSERVATION_MS
        }

        val currentTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, allLatestReadings)
            ?: TemperatureInterpolator.getInterpolatedTemperature(hourlyRaw.map { it.toHourlyForecast() })
            ?: hourlyRaw.firstOrNull()?.temperature

        val closestBundle = bundles.minByOrNull { com.weatherwidget.shared.observations.NwsObservationMapper.distanceKm(latitude, longitude, it.station.lat, it.station.lon) }
        val currentCondition = closestBundle?.latest?.textDescription
            ?: hourlyRaw.firstOrNull()?.shortForecast

        // All readings (latest + historical) from all successful stations
        val rawObservations = bundles.flatMap { bundle ->
            buildList {
                bundle.latest?.let { add(it.toReading(bundle.station, bundle.latestIsWeb)) }
                bundle.historical.forEach { add(it.toReading(bundle.station, bundle.historicalIsWeb)) }
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

        RawFetch(
            providerCurrentTemp = currentTemp,
            providerCurrentCondition = currentCondition,
            providerCurrentObservedAt = latestReadings.maxOfOrNull { it.timestamp } ?: observations.firstOrNull()?.timestamp,
            hourly = hourly.map { it.toHourlyForecast() },
            daily = NwsDailyMapper.buildDailyForecasts(dailyRaw, gridpoints.dailyTemperatures, LocalDate.now(), hourly),
            rawObservations = observations,
            nwsDailyExtremes = gridpoints.dailyTemperatures,
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
        recentOnly: Boolean = false,
    ): List<ObservationBundle> = coroutineScope {
        val end = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        // recentOnly narrows the window; it never collapses it to a single reading. A station that
        // publishes faster than the poll interval (KSJC: ~5 min) would otherwise have its
        // intermediate observations discarded — see the dropped 10:30 row in
        // plans/260820-observation-loop-recent-window-not-latest-row.md.
        val start = if (recentOnly) {
            end.minus(RECENT_OBSERVATION_WINDOW_MINUTES, ChronoUnit.MINUTES)
        } else {
            end.minus(historyDays, ChronoUnit.DAYS)
        }

        // Production uses one token-free Aviation Weather batch as the current freshness leg. It
        // starts before the NWS station jobs, so both transports overlap. An explicitly injected
        // Synoptic test double keeps the legacy test seam without putting Synoptic back in the
        // production path.
        val parallelMetarDeferred = if (injectedSynopticApi == null) {
            async {
                metarFetcher.fetchObservationsResult(
                    latitude,
                    longitude,
                    hours = RECENT_BORROWED_METAR_HOURS,
                    limit = MAX_OBSERVATION_STATIONS,
                )
            }
        } else {
            null
        }
        
        val deferreds = stations.take(MAX_OBSERVATION_STATIONS).mapIndexed { index, station ->
            async {
                // Tri-state, not bestEffort: an empty window (station definitively silent) and a
                // failed request (nothing learned) demand opposite fetchedAt handling below.
                // recentOnly shortens the window to RECENT_OBSERVATION_WINDOW_MINUTES — the 7-day
                // series is re-fetched identically by the full forecast pull, so a current-temp
                // cycle should not re-download ~500 rows/station.
                val historicalOutcome: FetchOutcome<List<NwsApi.Observation>> = try {
                    val obs = nwsApi.getObservations(station.id, start.toString(), end.toString())
                    Log.i(TAG, "observations window: station=${station.id} type=${station.type} recentOnly=$recentOnly count=${obs.size}")
                    if (obs.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(obs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "observations window ${station.id} fetch failed: $e")
                    FetchOutcome.failed(e)
                }
                val historical = historicalOutcome.valueOrNull().orEmpty()

                // Latest is fetched regardless of whether the window returned anything (previously
                // gated on historical.isNotEmpty()). It anchors current temp and can be fresher than
                // the window's newest row.
                val latestOutcome = nwsApi.getLatestObservationDetailedResult(station.id)
                val latest = latestOutcome.valueOrNull()

                val newestObservationMs = latest?.let {
                    runCatching { ZonedDateTime.parse(it.timestamp).toInstant().toEpochMilli() }.getOrNull()
                }

                // Fetch-first policy (plan 260721): the nearest WEB_FETCH_STATIONS fetch BOTH sources
                // every cycle and anchor current temp on prefer-newest (web is often fresher than the
                // API); stations up to WEB_METRICS_STATIONS also fetch web, but only to log the
                // freshness metric. Web here uses a modest window (not the wide historyDays one) — the
                // NWS API window still supplies historical actuals, so web only supplements the latest.
                var synopticOutcome: FetchOutcome<List<NwsApi.Observation>>? = null
                var webReadings: List<NwsApi.Observation> = emptyList()
                val fetchWebForUse = ObservationFallbackPolicy.shouldFetchWeb(index)
                val logWebMetrics = ObservationFallbackPolicy.shouldLogWebMetrics(index)
                var bundleLatest = latest
                var latestIsWeb = false
                if (fetchWebForUse || logWebMetrics) {
                    val nowMs = System.currentTimeMillis()
                    val windowMinutes = if (fetchWebForUse) {
                        ObservationFallbackPolicy.webFallbackWindowMinutes(newestObservationMs, nowMs)
                    } else {
                        ObservationFallbackPolicy.METRICS_WINDOW_MINUTES
                    }
                    val transport: String
                    synopticOutcome = if (parallelMetarDeferred != null) {
                        transport = "aviation_weather"
                        when (val batch = parallelMetarDeferred.await()) {
                            is FetchOutcome.Success -> {
                                val stationRows = batch.value
                                    .filter { it.stationId == station.id }
                                    .filter {
                                        it.timestamp <= System.currentTimeMillis() +
                                            ObservationFallbackPolicy.MAX_WEB_FUTURE_SKEW_MS
                                    }
                                    .map { it.toNwsObservation() }
                                if (stationRows.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(stationRows)
                            }
                            is FetchOutcome.NoData -> FetchOutcome.NoData
                            is FetchOutcome.Failed -> batch
                        }
                    } else {
                        transport = "synoptic_test"
                        synopticApi.fetchSynopticObservations(station.id, windowMinutes, station.name)
                    }
                    webReadings = synopticOutcome.valueOrNull().orEmpty()
                    val merge = LatestObservationMerge.preferNewest(
                        apiLatest = latest,
                        apiNewestMs = newestObservationMs,
                        webReadings = webReadings,
                        isQcFailed = { it.qcFailed },
                        observedAtMillis = {
                            runCatching { ZonedDateTime.parse(it.timestamp).toInstant().toEpochMilli() }.getOrNull()
                        },
                    )
                    val apiMs = merge.apiNewestMs
                    val webMs = merge.webNewestMs
                    val deltaMin = if (apiMs != null && webMs != null) (webMs - apiMs) / 60_000L else null
                    val webUsableLatest = webReadings.lastOrNull { !it.qcFailed }
                    val webOutcomeLabel = when (val outcome = requireNotNull(synopticOutcome)) {
                        is FetchOutcome.Success -> "success"
                        is FetchOutcome.NoData -> "no_data"
                        is FetchOutcome.Failed -> "failed:${outcome.reason}"
                    }
                    weatherDao?.log(
                        "OBS_WEB_API_DELTA",
                        "station=${station.id} index=$index tier=${if (fetchWebForUse) "use" else "metrics"} " +
                            "transport=$transport outcome=$webOutcomeLabel " +
                            "apiNewestMs=${merge.apiNewestMs} webNewestMs=${merge.webNewestMs} deltaMin=$deltaMin " +
                            "apiTempC=${latest?.temperatureCelsius} webTempC=${webUsableLatest?.temperatureCelsius} " +
                            "webQcFailed=${webReadings.any { it.qcFailed }} chosen=${if (merge.chosenIsWeb) "web" else "api"}",
                        "INFO",
                    )
                    if (fetchWebForUse) {
                        // Prefer-newest anchors current temp; historical stays the NWS API window.
                        bundleLatest = merge.chosen
                        latestIsWeb = merge.chosenIsWeb
                    }
                }

                if (historical.isNotEmpty()) {
                    ObservationBundle(station, bundleLatest, historical, latestIsWeb = latestIsWeb, historicalIsWeb = false)
                } else if (fetchWebForUse && webReadings.isNotEmpty()) {
                    // NWS returned no historical window; surface the web readings we already fetched so
                    // the station still contributes (mirrors the pre-260721 web-fallback bundle, just a
                    // narrower window). QC-flagged stay in historical for the stations UI; the chosen
                    // usable latest anchors current temp. All-flagged → latest=null (station shows QC).
                    ObservationBundle(
                        station,
                        bundleLatest,
                        webReadings.filter { it !== bundleLatest },
                        latestIsWeb = latestIsWeb,
                        historicalIsWeb = true,
                    )
                } else if (recentOnly && bundleLatest != null) {
                    // Recent-window cycle against a slow station (KPAO can go ~90 min between
                    // reports): an empty window is legitimate, but the latest lookup still holds a
                    // usable reading for the current-temp blend. Emit it rather than dropping the
                    // station for the cycle. Deliberately scoped to recentOnly — on the full 7-day
                    // pull an empty window means a genuinely silent station, which must keep taking
                    // the touch/fail path below.
                    ObservationBundle(station, bundleLatest, emptyList(), latestIsWeb = latestIsWeb, historicalIsWeb = false)
                } else if (shouldTouchObservationFetchedAt(historicalOutcome, synopticOutcome)) {
                    // Attempt completed but the station definitively yielded nothing storable
                    // (e.g. publishing only null-temperature reports). Record the attempt on the
                    // newest stored row so the stations list shows a fresh "Fetched" against an
                    // old "Reported" (silent station) instead of both timestamps frozen.
                    weatherDao?.touchLatestObservationFetchedAt(station.id, System.currentTimeMillis())
                    weatherDao?.log("OBS_ATTEMPT_TOUCH", "station=${station.id} reason=no_valid_observation", "INFO")
                    null
                } else {
                    // Every upstream failed outright — leave fetchedAt frozen (a dead network
                    // must not masquerade as a silent station) and report the failure durably.
                    val nwsReason = (historicalOutcome as? FetchOutcome.Failed)?.reason ?: "unknown"
                    val synopticReason = (synopticOutcome as? FetchOutcome.Failed)?.reason ?: "not_tried"
                    weatherDao?.log("NWS_STATION_FAIL", "station=${station.id} nws=$nwsReason synoptic=$synopticReason", "WARN")
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
        // Origin is per-slot, not per-bundle: under the fetch-first policy (plan 260721) the latest
        // reading can come from web (prefer-newest) while the historical series is still the NWS API
        // window. A single flag would mislabel one of them in the stations UI.
        val latestIsWeb: Boolean = false,
        val historicalIsWeb: Boolean = false,
    )

    /** Shared hardened parse (see [NwsObservationMapper]); retained as a seam for tests. */
    internal fun parseTimestamp(ts: String): Long = NwsObservationMapper.parseTimestamp(ts)

    private fun NwsApi.Observation.toReading(station: NwsApi.StationInfo, isWebFallback: Boolean = false): ObservationReading =
        // Shared mapping (units, name fallback, hardened timestamp parse, METAR→low cloud rule) —
        // the same rows Android's NwsObservationSource stores.
        com.weatherwidget.shared.observations.NwsObservationMapper.toReading(
            this, station, latitude, longitude, isWebFallback,
        )

    /** Presentation copy for the NWS fetch-both merge; standalone METAR rows keep `api=METAR`. */
    private fun ObservationReading.toNwsObservation() = NwsApi.Observation(
        timestamp = Instant.ofEpochMilli(timestamp).toString(),
        temperatureCelsius = (temperature - 32f) / 1.8f,
        textDescription = condition,
        stationName = stationName,
        precipLastHourMm = precipAmountMm,
        qcFailed = qcFailed,
        cloudLayers = MetarRawSkyParser.layersFrom(rawMetar),
        isMetar = isMetar,
        rawMessage = rawMetar,
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

    override suspend fun fetchObservationsOnly(recentOnly: Boolean): RawFetch {
        val source = WeatherSource.fromId(weatherSource)
        val provider = ActualsProviderResolver.providerIdFor(source)
        if (provider != source.id) {
            return when (provider) {
                WeatherSource.METAR.id, WeatherSource.SYNOPTIC.id -> fetchBorrowedObservationsOnly(recentOnly)
                WeatherSource.NWS.id -> fetchNwsObservationsOnly(recentOnly)
                WeatherSource.TOMORROW_IO.id -> fetchTomorrowIoObservationsOnly()
                WeatherSource.OPEN_METEO.id -> fetchOpenMeteoObservationsOnly()
                WeatherSource.OPEN_WEATHER_MAP.id -> fetchOpenWeatherMapObservationsOnly()
                else -> fetchBorrowedObservationsOnly(recentOnly)
            }
        }
        return when (weatherSource) {
            "NWS" -> fetchNwsObservationsOnly(recentOnly)
            WeatherSource.TOMORROW_IO.id -> fetchTomorrowIoObservationsOnly()
            WeatherSource.OPEN_WEATHER_MAP.id -> fetchOpenWeatherMapObservationsOnly()
            WeatherSource.OPEN_METEO.id -> fetchOpenMeteoObservationsOnly()
            WeatherSource.SILURIAN.id -> fetchBorrowedObservationsOnly(recentOnly)
            WeatherSource.WEATHER_API.id,
            WeatherSource.VISUAL_CROSSING.id -> {
                Log.i(TAG, "Skipping observations-only refresh for $weatherSource; no current-only desktop path is defined")
                RawFetch()
            }
            else -> RawFetch()
        }
    }

    private suspend fun fetchOpenMeteoObservationsOnly(): RawFetch {
        val reading = openMeteo.getCurrent(latitude, longitude) ?: return RawFetch()
        val timestamp = reading.observedAt ?: System.currentTimeMillis()
        val condition = reading.weatherCode?.let { openMeteo.weatherCodeToCondition(it) } ?: "Unknown"
        val observation = ObservationReading(
            stationId = "OPEN_METEO_MAIN",
            stationName = "Open-Meteo Current",
            timestamp = timestamp,
            temperature = reading.temperature,
            condition = condition,
            locationLat = latitude,
            locationLon = longitude,
            distanceKm = 0f,
            stationType = "OFFICIAL",
            api = WeatherSource.OPEN_METEO.id,
            cloudCover = reading.cloudCover,
            cloudCoverLow = reading.cloudCoverLow,
            cloudCoverMid = reading.cloudCoverMid,
            cloudCoverHigh = reading.cloudCoverHigh,
            cloudVerticalKind = if (
                reading.cloudCoverLow != null ||
                reading.cloudCoverMid != null ||
                reading.cloudCoverHigh != null
            ) {
                CloudVerticalKind.PROVIDER_BANDS
            } else {
                CloudVerticalKind.NONE
            },
        )
        return RawFetch(rawObservations = listOf(observation))
    }

    /**
     * Actuals for a forecast-only source, from the feed it borrows.
     *
     * This branch used to return an empty [RawFetch] with "no current-only desktop path is defined",
     * which was true when Open-Meteo and Silurian had no actuals at all. Once they began borrowing a
     * measured feed it became the reason desktop drew no mercury line for them: Android fetches METAR
     * from its worker regardless of the displayed source, desktop fetched nothing.
     *
     * Only METAR is wired here. NWS as a borrowed provider would mean running the full station-pull
     * under a non-NWS display source, which is a larger change; a user who picks it gets whatever the
     * NWS path already stored rather than a fresh pull, and never a silently substituted feed
     * (`no_cross_source_fallback`).
     */
    private suspend fun fetchBorrowedObservationsOnly(recentOnly: Boolean): RawFetch {
        val source = WeatherSource.fromId(weatherSource)
        val provider = ActualsProviderResolver.providerIdFor(source)
        if (provider == WeatherSource.METAR.id) {
            val hours = if (recentOnly) RECENT_BORROWED_METAR_HOURS else RECOVERY_BORROWED_METAR_HOURS
            val readings = metarFetcher.fetchObservations(latitude, longitude, hours = hours)
            Log.i(
                TAG,
                "BORROWED_METAR_FETCH source=$weatherSource hours=$hours rows=${readings.size} " +
                    "stations=${readings.map { it.stationId }.distinct().size}",
            )
            return RawFetch(rawObservations = readings)
        } else if (provider == WeatherSource.SYNOPTIC.id) {
            val hours = if (recentOnly) RECENT_BORROWED_METAR_HOURS else RECOVERY_BORROWED_METAR_HOURS
            val readings = synopticFetcher.fetchObservations(latitude, longitude, hours = hours)
            Log.i(
                TAG,
                "BORROWED_SYNOPTIC_FETCH source=$weatherSource hours=$hours rows=${readings.size} " +
                    "stations=${readings.map { it.stationId }.distinct().size}",
            )
            return RawFetch(rawObservations = readings)
        } else {
            Log.i(TAG, "Observations-only refresh for $weatherSource borrows $provider; no desktop fetch path for it")
            return RawFetch()
        }
    }

    private suspend fun fetchTomorrowIoObservationsOnly(): RawFetch {
        val realtime = tomorrowIo.getRealtime(latitude, longitude) ?: return RawFetch()
        return RawFetch(
            rawObservations = listOf(TomorrowIoActuals.toObservation(realtime, latitude, longitude)),
            providerCurrentTemp = realtime.temperature,
            providerCurrentCondition = realtime.condition,
            providerCurrentObservedAt = realtime.observedAt,
        )
    }

    private suspend fun fetchOpenWeatherMapObservationsOnly(): RawFetch {
        val forecast = openWeatherMap.getForecast(latitude, longitude)
        val currentTemp = forecast.providerCurrentTemp ?: return RawFetch()
        val observation = ObservationReading(
            stationId = "OPEN_WEATHER_MAP_MAIN",
            stationName = "OWM: Current",
            timestamp = forecast.providerCurrentObservedAt ?: System.currentTimeMillis(),
            temperature = currentTemp,
            condition = forecast.providerCurrentCondition ?: "Unknown",
            locationLat = latitude,
            locationLon = longitude,
            api = WeatherSource.OPEN_WEATHER_MAP.id,
        )
        return RawFetch(
            rawObservations = listOf(observation),
            providerCurrentTemp = currentTemp,
            providerCurrentCondition = forecast.providerCurrentCondition,
            providerCurrentObservedAt = forecast.providerCurrentObservedAt,
        )
    }

    private suspend fun fetchNwsObservationsOnly(recentOnly: Boolean): RawFetch = coroutineScope {
        val grid = nwsApi.getGridPoint(latitude, longitude)
        
        // Resolve candidate observation stations once, then try official stations first.
        val stations = bestEffort("observation stations") {
            grid.observationStationsUrl?.let { url -> getCachedOrFetchStations(url) }
        } ?: emptyList()

        val bundles = fetchObservationBundles(stations, recentOnly = recentOnly)
        
        val allLatestReadings = bundles.mapNotNull { bundle ->
            bundle.latest?.toReading(bundle.station, bundle.latestIsWeb)
        }
        val freshLatestReadings = allLatestReadings.filter {
            System.currentTimeMillis() - it.timestamp <= FRESH_OBSERVATION_MS
        }

        val currentTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, allLatestReadings)
            ?: bundles.minByOrNull { com.weatherwidget.shared.observations.NwsObservationMapper.distanceKm(latitude, longitude, it.station.lat, it.station.lon) }?.latest?.let {
                (it.temperatureCelsius * 1.8f) + 32f
            }

        val closestBundle = bundles.minByOrNull { com.weatherwidget.shared.observations.NwsObservationMapper.distanceKm(latitude, longitude, it.station.lat, it.station.lon) }
        val currentCondition = closestBundle?.latest?.textDescription

        // All readings (latest + historical) from all successful stations
        val rawObservations = bundles.flatMap { bundle ->
            buildList {
                bundle.latest?.let { add(it.toReading(bundle.station, bundle.latestIsWeb)) }
                bundle.historical.forEach { add(it.toReading(bundle.station, bundle.historicalIsWeb)) }
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

        RawFetch(
            providerCurrentTemp = currentTemp,
            providerCurrentCondition = currentCondition,
            providerCurrentObservedAt = latestReadings.maxOfOrNull { it.timestamp } ?: observations.firstOrNull()?.timestamp,
            rawObservations = observations
        )
    }

    override fun close() = httpClient.close()

    companion object {
        // No fallback coordinates. "No location" is the absence of a config, not a stand-in for one;
        // a null config opens the location picker and must never silently show Google-HQ weather as
        // the user's own (the desktop analog of the Android H1 fix).

        // How far back to pull observations for the actuals / accuracy pipeline.
        const val HISTORY_DAYS = 7L

        // past_days window for the Open-Meteo actuals backfill — matches the graph's 6-day
        // full zoom-out so the actual line spans the same range as the forecast line.
        const val ACTUALS_HISTORY_DAYS = 7

        // Window for the current-temperature observation cycle. Must cover the poll interval plus
        // the endpoint's own publish lag plus one missed cycle: the loop polls every 10 min today
        // (30 min under the proposed screen-off tier), and /observations/latest was measured ~25 min
        // behind the list endpoint. At ~18 rows for a 5-minute station across 5 stations that is
        // ~90 rows/cycle, against ~2500 for the 7-day window — the reduction from 2befc157 survives
        // without discarding readings.
        internal const val RECENT_OBSERVATION_WINDOW_MINUTES = 90L
        internal const val RECENT_BORROWED_METAR_HOURS = 2
        internal const val RECOVERY_BORROWED_METAR_HOURS = 24

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
