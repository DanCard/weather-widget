package com.weatherwidget.desktop

import com.weatherwidget.shared.observations.NwsStationBundles
import com.weatherwidget.shared.observations.NwsStationAttemptLog
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.GoogleWeatherApi
import com.weatherwidget.data.remote.*
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill
import com.weatherwidget.shared.observations.NwsMetarTextWeb
import com.weatherwidget.shared.observations.NwsObservationMapper
import com.weatherwidget.shared.observations.NwsStationHistoryFetcher
import com.weatherwidget.shared.observations.NwsStationLatestFetcher
import com.weatherwidget.shared.observations.ObservationFallbackPolicy
import com.weatherwidget.shared.config.ForecastHorizon
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.observations.NwsBlend
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.call.body
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.*
import com.weatherwidget.shared.util.ActualsFeedPolicy
import com.weatherwidget.shared.observations.MetarObservationFetcher
import com.weatherwidget.shared.observations.SynopticObservationFetcher
import com.weatherwidget.shared.util.SynopticBackoffStore
import com.weatherwidget.shared.util.SynopticFetchGate
import com.weatherwidget.data.remote.AviationWeatherApi

/**
 * Thin desktop-side orchestration over the shared API clients. Supports all major sources
 * used by the Android widget.
 */
class DesktopWeatherService(
    private val latitude: Double,
    private val longitude: Double,
    private val weatherSource: String,
    apiKeys: Map<String, String> = emptyMap(),
    private val weatherDao: DesktopWeatherDao? = null,
    private val isForeground: Boolean = false,
    // Injectable seams for tests; null = construct the real production client/apis.
    private val injectedHttpClient: HttpClient? = null,
    private val injectedNwsApi: NwsApi? = null,
    private val injectedSynopticApi: SynopticApi? = null,
    /** The NWS web leg's transport (NWS's own METAR text file); null = the real one. */
    private val injectedNwsMetarTextApi: NwsMetarTextApi? = null,
    /** Shared Synoptic backoff state; production passes [DesktopSynopticBackoffStore.default]. */
    synopticBackoffStore: SynopticBackoffStore = InMemorySynopticBackoffStore(),
    /**
     * The user's personal-station weight for the NWS current blend; production passes
     * `config.personalStationWeight()`. Defaults to an untouched setting's value.
     */
    private val personalStationWeight: Double = DesktopSettings().personalStationWeight(),
    /** Where an Open-Meteo fetch leaves its 15-minute nowcast; production passes [DesktopNowcast.store]. */
    private val nowcastStore: com.weatherwidget.shared.nowcast.NowcastStore? = null,
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
        if (weatherDao != null) {
            install(ResponseObserver) {
                onResponse { response ->
                    val bodyBytes = runCatching {
                        response.contentLength()
                            ?: response.bodyAsText().toByteArray(Charsets.UTF_8).size.toLong()
                    }.getOrDefault(0L)
                    val totalBytes = bodyBytes + 500L
                    weatherDao.recordNetworkUsage(totalBytes, isForeground)
                    val url = response.call.request.url
                    ApiUsageClassifier.classify(url.host, url.encodedPath, url.encodedQuery)?.let { key ->
                        val day = ApiUsageClassifier.usageDayMs(key.source, java.time.Instant.now(), java.time.ZoneId.systemDefault())
                        runCatching { weatherDao.logApiCall(day, key.source, key.endpoint, response.status.value) }
                    }
                }
            }
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
    private val silurian = SilurianApi(httpClient, json) { effectiveKeys[WeatherSource.SILURIAN.id] }
    private val openWeatherMap = OpenWeatherMapApi(httpClient, json) { effectiveKeys[WeatherSource.OPEN_WEATHER_MAP.id] }

    /** The model feeds' current conditions, shared with Android (plans/261013-shared-current-conditions-fetch.md). */
    private val currentConditions = com.weatherwidget.shared.observations.CurrentConditionsFetcher.of(
        openMeteo, weatherApi, openWeatherMap, tomorrowIo,
    )
    private val googleWeather = GoogleWeatherApi(
        httpClient,
        json,
        onRequest = { weatherDao?.log("GOOGLE_REQUEST", it, "INFO") },
    ) { effectiveKeys[WeatherSource.GOOGLE_WEATHER.id] }
    // MesoTokenApi retrieves and caches weather.gov's public browser token.
    private val synopticApi = injectedSynopticApi
        ?: SynopticApi(
            httpClient,
            json,
            mesoTokenApi = if (injectedHttpClient != null) MesoTokenApi(httpClient) else MesoTokenApi(httpClient, cache = sharedMesoTokenCache),
        )
    private val aviationWeatherApi = AviationWeatherApi(httpClient, json)
    private val nwsMetarTextApi = injectedNwsMetarTextApi ?: NwsMetarTextApi(httpClient)

    /** One station's latest observation + web merge: the step Android runs too (plans/261013-shared-current-conditions-fetch.md). */
    private val latestFetcher = NwsStationLatestFetcher(
        latestObservation = { stationId -> nwsApi.getLatestObservationDetailedResult(stationId) },
        log = { tag, message, level -> weatherDao?.log(tag, message, level) },
    )

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

    // Same backoff state machine and log rows as Android's SynopticObservationRefresher.
    private val synopticGate = SynopticFetchGate(
        store = synopticBackoffStore,
        log = { tag, message, level ->
            Log.i(TAG, "$tag $message")
            weatherDao?.log(tag, message, level)
        },
    )

    constructor(config: DesktopConfig) : this(
        latitude = config.lat,
        longitude = config.lon,
        weatherSource = config.displaySource,
        apiKeys = config.settings.apiKeys
    )

    /**
     * Every fetch requests the maximum horizon ([ForecastHorizon.MAX_DAYS]); only the Open-Meteo
     * path honours the number — the other sources return whatever their API provides, and days
     * past a source's real coverage render climate-normal filler by design.
     */
    override suspend fun fetchForecast(): RawFetch =
        fetchForecast(ForecastParts.BOTH)

    override suspend fun fetchForecast(parts: ForecastParts): RawFetch =
        fetchForecast(parts, refillDay = null)

    override suspend fun fetchForecast(
        parts: ForecastParts,
        refillDay: java.time.LocalDate?,
    ): RawFetch = fetchForecast(
        parts,
        refillDay,
        DesktopForecastHorizon(weatherDao, weatherSource, latitude, longitude).forced(),
    )

    override suspend fun fetchForecast(
        parts: ForecastParts,
        refillDay: java.time.LocalDate?,
        horizon: com.weatherwidget.data.remote.ForecastHorizonCadence.Horizon?,
    ): RawFetch = runCatching {
        // What each source asks for is the `:shared` SourceFetchTable's, as on Android.
        val source = WeatherSource.fromId(weatherSource)
        if (!sourceFetchTable.serves(source)) throw IllegalArgumentException("Unsupported weather source: $weatherSource")
        val request = SourceFetchTable.Request(parts = parts, horizon = horizon, refillDay = refillDay)
        val reply = sourceFetchTable.fetch(source, latitude, longitude, request, System.currentTimeMillis())
        // NWS supplies real station readings and is never backfilled from its own hours.
        if (source == WeatherSource.NWS) reply else withHistoricalActuals(reply, weatherSource)
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
     * one exception: its actuals are real station readings (the observation path) and its forecast
     * hours must not be backfilled as observations.
     */
    private fun withHistoricalActuals(result: RawFetch, sourceId: String): RawFetch =
        result.copy(
            rawObservations = result.rawObservations + HistoricalActualsBackfill.build(
                // The shared input rule: native sub-hour product, else hourly, never Tomorrow.io's
                // forecast hours; forecast-only sources are rejected by the builder.
                hourly = HistoricalActualsBackfill.actualsInput(sourceId, result.subHourly, result.hourly),
                latitude = latitude,
                longitude = longitude,
                sourceId = sourceId,
                nowMs = System.currentTimeMillis(),
            ),
        )

    override suspend fun fetchForecastAhead(hoursAhead: Int): RawFetch =
        if (weatherSource == WeatherSource.GOOGLE_WEATHER.id) {
            val request = SourceFetchTable.Request(onDemand = HourlyOnDemand.Request(WeatherSource.GOOGLE_WEATHER.id, hoursAhead))
            withHistoricalActuals(
                sourceFetchTable
                    .fetch(WeatherSource.GOOGLE_WEATHER, latitude, longitude, request, System.currentTimeMillis()),
                WeatherSource.GOOGLE_WEATHER.id,
            )
        } else {
            fetchForecast()
        }

    /** The shared table over this service's clients, NWS's forecast-only fetch included (F6). */
    private val sourceFetchTable by lazy {
        SourceFetchTable(
            DesktopSourceFetchHost(
                calls = SourceFetchTable.Calls.of(
                    nws = { lat, lon, parts ->
                        NwsForecastReply.fetch(nwsApi, lat, lon, parts, log = { tag, message, level -> weatherDao?.log(tag, message, level) })
                    },
                    openMeteo = openMeteo,
                    silurian = silurian,
                    weatherApi = weatherApi,
                    openWeatherMap = openWeatherMap,
                    tomorrowIo = tomorrowIo,
                    google = googleWeather,
                ),
                weatherDao = weatherDao,
                needsHistory = { refillDay -> googleNeedsHistory(refillDay) },
                nowcastStore = nowcastStore,
            ),
        )
    }

    /** [GoogleHistoryRun.needsHistory] at this site; without a DB, always. */
    private suspend fun googleNeedsHistory(refillDay: java.time.LocalDate?): Boolean {
        val dao = weatherDao ?: return true
        return GoogleHistoryRun(DesktopGoogleHistoryHost(dao, latitude, longitude))
            .needsHistory(refillDay, System.currentTimeMillis())
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
     * the user zooms/pans the hourly graph past the [HISTORY_DAYS] observation window a refresh
     * covers. Resolves the same observation stations as [fetchObservationsOnly] and only widens the
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
                bundle.latest?.let { add(it) }
                addAll(bundle.historical)
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


    /**
     * `:shared` [NwsBlend.fetchReading] on each station's latest reading, logged as on Android. Null
     * when nothing blends — no forecast or stale-station stand-in (user, 2026-10-24,
     * plans/261024-nws-fetch-time-current-reading-shared.md).
     */
    private fun nwsFetchReading(bundles: List<NwsStationBundles.Bundle>, startMs: Long): NwsBlend.FetchReading? {
        val latest = bundles.mapNotNull { it.latest }
        val reading = NwsBlend.fetchReading(latest, latitude, longitude, personalStationWeight)
        val line = NwsBlend.idwLine(reading, latest, nwsBundleRows(bundles).size, System.currentTimeMillis() - startMs)
        weatherDao?.log(NwsBlend.IDW_TAG, line, "INFO")
        Log.d(TAG, "NWS IDW blend: $line")
        return reading
    }

    /** All readings (latest + historical) from all successful stations. */
    private fun nwsBundleRows(bundles: List<NwsStationBundles.Bundle>): List<ObservationReading> =
        bundles.flatMap { bundle ->
            buildList {
                bundle.latest?.let { add(it) }
                addAll(bundle.historical)
                // Preserved API row when the web reading won the temperature merge (Android parity).
                bundle.cloudCarrier?.let { add(it) }
            }
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
    ): List<NwsStationBundles.Bundle> {
        val end = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        // recentOnly narrows the window; it never collapses it to a single reading
        // (plans/260820-observation-loop-recent-window-not-latest-row.md).
        val start = if (recentOnly) {
            end.minus(RECENT_OBSERVATION_WINDOW_MINUTES, ChronoUnit.MINUTES)
        } else {
            end.minus(historyDays, ChronoUnit.DAYS)
        }
        val historyFetcher = NwsStationHistoryFetcher(
            observations = { station, startIso, endIso ->
                withStallRetry(
                    timeoutMs = OBSERVATION_WINDOW_TIMEOUT_MS,
                    onRetry = { attempt, cause ->
                        Log.w(TAG, "observations window ${station.id} attempt $attempt failed, retrying: $cause")
                        weatherDao?.log(
                            "OBS_WINDOW_RETRY",
                            "station=${station.id} attempt=$attempt recentOnly=$recentOnly cause=${cause.javaClass.simpleName}",
                            "INFO",
                        )
                    },
                ) { nwsApi.getObservations(station.id, startIso, endIso) }
                    .also { Log.i(TAG, "observations window: station=${station.id} type=${station.type} recentOnly=$recentOnly count=${it.size}") }
            },
            log = { tag, message, level -> weatherDao?.log(tag, message, level) },
        )
        // The per-station step is :shared NwsStationBundles, the same pass Android's current-temp cycle runs.
        return NwsStationBundles.fetch(
            stations = stations,
            latitude = latitude,
            longitude = longitude,
            latestFetcher = latestFetcher,
            historyFetcher = historyFetcher,
            metarTextApi = nwsMetarTextApi,
            synopticApi = synopticApi,
            windowStart = start,
            windowEnd = end,
            recentOnly = recentOnly,
            host = DesktopNwsStationBundlesHost(weatherDao, latitude, longitude),
        )
    }

    /** Shared hardened parse (see [NwsObservationMapper]); retained as a seam for tests. */
    internal fun parseTimestamp(ts: String): Long = NwsObservationMapper.parseTimestamp(ts)

    private fun NwsApi.Observation.toReading(station: NwsApi.StationInfo, isWebFallback: Boolean = false): ObservationReading =
        // Shared mapping (units, name fallback, hardened timestamp parse, METAR→low cloud rule) —
        // the same rows Android's NwsObservationSource stores.
        com.weatherwidget.shared.observations.NwsObservationMapper.toReading(
            this, station, latitude, longitude, isWebFallback,
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

    override suspend fun fetchObservationsOnly(recentOnly: Boolean, userLocationChange: Boolean): RawFetch {
        // The feed decision is shared with Android (ActualsFeedPolicy); this only performs the fetch.
        val source = WeatherSource.fromId(weatherSource)
        val feed = ActualsFeedPolicy.feedFor(source, latitude, longitude)
        val fetched = fetchFeed(feed, recentOnly, userLocationChange)
        // A borrowed feed contributes its rows only. Its own current-temperature product (NWS's
        // blend, Open-Meteo's current) must not become the borrower's header value: the METAR path
        // never set one, and the header then comes from the shared blend of the rows.
        return if (feed != null && feed != source) RawFetch(rawObservations = fetched.rawObservations) else fetched
    }

    override suspend fun fetchGoogleHistory(): RawFetch = googleWeather.getHistory(latitude, longitude)

    override suspend fun fetchFeedObservations(feed: WeatherSource, recentOnly: Boolean): RawFetch =
        fetchFeed(feed, recentOnly, userLocationChange = false)

    private suspend fun fetchFeed(
        feed: WeatherSource?,
        recentOnly: Boolean,
        userLocationChange: Boolean,
    ): RawFetch {
        return when (feed) {
            null -> {
                Log.i(TAG, "Skipping observations-only refresh for $weatherSource; no actuals feed serves this location")
                RawFetch()
            }
            WeatherSource.NWS -> fetchNwsObservationsOnly(recentOnly)
            WeatherSource.METAR, WeatherSource.SYNOPTIC -> fetchStationNetworkObservations(feed, recentOnly, userLocationChange)
            in com.weatherwidget.shared.observations.CurrentConditionsFetcher.FEEDS -> fetchCurrentConditions(feed)
            else -> {
                Log.i(TAG, "Skipping observations-only refresh for $weatherSource; no current-only desktop path for feed ${feed.id}")
                RawFetch()
            }
        }
    }

    /**
     * A model feed through the shared fetcher (WeatherAPI gained a path here; it had none). Only the
     * forecast-carried currents (OpenWeatherMap, WeatherAPI) name a provider current, as OWM did
     * before the move: Open-Meteo's header resolves from its forecast timeline, Tomorrow.io's from
     * its rows.
     */
    private suspend fun fetchCurrentConditions(feed: WeatherSource): RawFetch {
        val result = currentConditions.fetch(feed, latitude, longitude) ?: return RawFetch()
        val current = result.current?.takeIf { feed == WeatherSource.OPEN_WEATHER_MAP || feed == WeatherSource.WEATHER_API }
        return RawFetch(
            rawObservations = result.readings,
            providerCurrentTemp = current?.temperature,
            providerCurrentCondition = current?.condition,
            providerCurrentObservedAt = current?.observedAt,
        )
    }

    /**
     * Readings from a station network ([ActualsFeedPolicy.STATION_NETWORK_FEEDS]) for a source whose
     * actuals come from it. Which feed to use is decided by [ActualsFeedPolicy.feedFor], shared
     * with Android; NWS, Tomorrow.io, Open-Meteo and OpenWeatherMap feeds have their own fetchers.
     */
    private suspend fun fetchStationNetworkObservations(
        feed: WeatherSource,
        recentOnly: Boolean,
        userLocationChange: Boolean,
    ): RawFetch {
        if (feed == WeatherSource.METAR) {
            val hours = if (recentOnly) RECENT_BORROWED_METAR_HOURS else RECOVERY_BORROWED_METAR_HOURS
            val readings = metarFetcher.fetchObservations(latitude, longitude, hours = hours)
            Log.i(
                TAG,
                "BORROWED_METAR_FETCH source=$weatherSource hours=$hours rows=${readings.size} " +
                    "stations=${readings.map { it.stationId }.distinct().size}",
            )
            return RawFetch(rawObservations = readings)
        } else if (feed == WeatherSource.SYNOPTIC) {
            // Gap window, not a fixed 24 h: observations are stored for days, so a routine refresh
            // only needs the minutes since the newest stored SYNOPTIC row (plus a margin).
            // First run / long gap falls back to the deep window. See SynopticFetchWindow.
            val newestStored = weatherDao?.getNewestObservationTimestampForApi(
                WeatherSource.SYNOPTIC.id,
                latitude,
                longitude,
            )
            val recentMinutes = com.weatherwidget.shared.util.SynopticFetchWindow
                .recentMinutes(newestStored, System.currentTimeMillis())
                .toLong()
            val readings = synopticGate.run(
                context = "reason=borrowed_${if (recentOnly) "recent" else "recovery"}",
                userLocationChange = userLocationChange,
                siteKey = com.weatherwidget.shared.util.SynopticFetchGate.siteKey(latitude, longitude),
            ) {
                synopticFetcher.fetchObservationsResult(
                    latitude,
                    longitude,
                    recentMinutes = recentMinutes,
                )
            }?.valueOrNull().orEmpty()
            Log.i(
                TAG,
                "BORROWED_SYNOPTIC_FETCH source=$weatherSource recentMin=$recentMinutes rows=${readings.size} " +
                    "stations=${readings.map { it.stationId }.distinct().size}",
            )
            return RawFetch(rawObservations = readings)
        } else {
            Log.i(TAG, "Observations-only refresh for $weatherSource: ${feed.id} is not a station network")
            return RawFetch()
        }
    }

    private suspend fun fetchNwsObservationsOnly(recentOnly: Boolean): RawFetch = coroutineScope {
        val startMs = System.currentTimeMillis()
        val grid = nwsApi.getGridPoint(latitude, longitude)
        
        // Resolve candidate observation stations once, then try official stations first.
        val stations = bestEffort("observation stations") {
            grid.observationStationsUrl?.let { url -> getCachedOrFetchStations(url) }
        } ?: emptyList()

        val bundles = fetchObservationBundles(stations, recentOnly = recentOnly)
        // Never stored (see fetchNwsObservations); no reading when nothing blends.
        val reading = nwsFetchReading(bundles, startMs)

        RawFetch(
            providerCurrentTemp = reading?.temperature,
            providerCurrentCondition = reading?.condition,
            providerCurrentObservedAt = reading?.observedAt,
            rawObservations = nwsBundleRows(bundles),
        )
    }

    override suspend fun refreshNowcast(): com.weatherwidget.shared.nowcast.NowcastFetcher.Outcome? =
        nowcastStore?.let { com.weatherwidget.shared.nowcast.NowcastFetcher(httpClient, it).refresh(latitude, longitude) }

    override fun close() = httpClient.close()

    companion object {
        /**
         * One mesoToken for the process, fetched with whichever service's client asks: the services
         * (and their clients) are replaced on every loop restart, the token is not.
         */
        private val sharedMesoTokenCache = MesoTokenApi.Cache()
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
        internal const val RECENT_OBSERVATION_WINDOW_MINUTES = NwsStationBundles.RECENT_WINDOW_MINUTES

        /**
         * Bound on one station's observation-window request, body included. A healthy 7-day pull
         * is ~2 MB in 1–2.5 s; the client's own 30 s timeout is what a post-wake stall used to
         * cost. 15 s still admits ~1 Mbps links. See [withStallRetry].
         */
        internal const val OBSERVATION_WINDOW_TIMEOUT_MS = 15_000L
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
