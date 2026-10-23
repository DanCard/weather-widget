package com.weatherwidget.desktop

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
import com.weatherwidget.shared.util.TemperatureInterpolator
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
    // Synoptic: only where the user picked it as a source's actuals provider — never the NWS web leg.
    // Its token rides the same baked-keys map under "SYNOPTIC" purely for the plumbing.
    private val synopticApi = injectedSynopticApi
        ?: SynopticApi(httpClient, json) { effectiveKeys["SYNOPTIC"] }
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
    override suspend fun fetchForecast(recentObservationsOnly: Boolean): RawFetch =
        fetchForecast(recentObservationsOnly, ForecastParts.BOTH)

    override suspend fun fetchForecast(recentObservationsOnly: Boolean, parts: ForecastParts): RawFetch =
        fetchForecast(recentObservationsOnly, parts, refillDay = null)

    override suspend fun fetchForecast(
        recentObservationsOnly: Boolean,
        parts: ForecastParts,
        refillDay: java.time.LocalDate?,
    ): RawFetch = fetchForecast(
        recentObservationsOnly,
        parts,
        refillDay,
        DesktopForecastHorizon(weatherDao, weatherSource, latitude, longitude).forced(System.currentTimeMillis()),
    )

    override suspend fun fetchForecast(
        recentObservationsOnly: Boolean,
        parts: ForecastParts,
        refillDay: java.time.LocalDate?,
        horizon: com.weatherwidget.data.remote.ForecastHorizonCadence.Horizon?,
    ): RawFetch = runCatching {
        // What the request asks for: a source that cannot split always asks for everything.
        val requested = ForecastParts.requested(WeatherSource.fromId(weatherSource), parts)
        when (weatherSource) {
            "NWS" -> fetchNwsForecast(recentObservationsOnly, requested)
            WeatherSource.TOMORROW_IO.id -> fetchTomorrowIoForecastWithFiveMinuteHistory(requested)
            WeatherSource.WEATHER_API.id -> withHistoricalActuals(weatherApi.getForecast(latitude, longitude), WeatherSource.WEATHER_API.id)
            WeatherSource.SILURIAN.id -> withHistoricalActuals(silurian.getForecast(latitude, longitude, requested), WeatherSource.SILURIAN.id)
            // Forecast-only like Silurian: withHistoricalActuals files nothing for it (the builder
            // rejects sources without a historical-actuals contract); actuals are borrowed.
            WeatherSource.GOOGLE_WEATHER.id -> withHistoricalActuals(
                googleWeather.getForecast(
                    latitude,
                    longitude,
                    includeHistory = googleNeedsHistory(refillDay),
                    storedHours = googleStoredHours(),
                    parts = requested,
                    hoursAhead = HourlyOnDemand.askHours(WeatherSource.GOOGLE_WEATHER.id, null, horizon),
                ).also { weatherDao?.log("GOOGLE_HOURS_PAGES", googleWeather.lastHoursPaging.orEmpty(), "INFO") },
                WeatherSource.GOOGLE_WEATHER.id,
            )
            WeatherSource.OPEN_WEATHER_MAP.id -> fetchOpenWeatherMapForecastWithCurrent()
            WeatherSource.OPEN_METEO.id -> fetchOpenMeteoForecast(requested)
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

    /**
     * Google's stored hours at this site, for the one-page `forecast/hours` check
     * ([com.weatherwidget.data.remote.GoogleHourPaging]). Null without a DB, which fetches all pages.
     */
    private fun googleStoredHours(): List<com.weatherwidget.data.model.HourlyForecast>? {
        val dao = weatherDao ?: return null
        val now = System.currentTimeMillis()
        val lat = LocationMatch.quantize(latitude)
        val lon = LocationMatch.quantize(longitude)
        return dao.getHourlyForecasts(
            lat,
            lon,
            WeatherSource.GOOGLE_WEATHER.id,
            now - 3_600_000L,
            now + (GoogleWeatherApi.FORECAST_HOURS + 2) * 3_600_000L,
        ).filter { row ->
            val rowLat = row.locationLat
            val rowLon = row.locationLon
            rowLat == null || rowLon == null || LocationMatch.sameSite(lat, lon, rowLat, rowLon)
        }
    }

    override suspend fun fetchForecastAhead(hoursAhead: Int): RawFetch =
        if (weatherSource == WeatherSource.GOOGLE_WEATHER.id) {
            withHistoricalActuals(
                googleWeather.getForecast(latitude, longitude, includeHistory = false, hoursAhead = hoursAhead)
                    .also { weatherDao?.log("GOOGLE_HOURS_PAGES", "${googleWeather.lastHoursPaging.orEmpty()} trigger=on_demand_day", "INFO") },
                WeatherSource.GOOGLE_WEATHER.id,
            )
        } else {
            fetchForecast(recentObservationsOnly = true)
        }

    /** [GoogleHistoryRun.needsHistory] at this site; without a DB, always. */
    private suspend fun googleNeedsHistory(refillDay: java.time.LocalDate?): Boolean {
        val dao = weatherDao ?: return true
        return GoogleHistoryRun(DesktopGoogleHistoryHost(dao, latitude, longitude))
            .needsHistory(refillDay, System.currentTimeMillis())
    }

    private suspend fun fetchTomorrowIoForecastWithFiveMinuteHistory(
        parts: ForecastParts = ForecastParts.BOTH,
    ): RawFetch = coroutineScope {
        val forecastDeferred = async { tomorrowIo.getForecast(latitude, longitude, parts) }
        val historyDeferred = async {
            // Actuals: on the daily cadence, not the hourly one.
            if (!parts.daily) return@async null
            try {
                tomorrowIo.getFiveMinuteHistory(
                    latitude,
                    longitude,
                    TomorrowIoApi.FULL_ACTUALS_LOOKBACK_HOURS,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Tomorrow.io five-minute history failed during full refresh: $e")
                null
            }
        }
        val forecast = forecastDeferred.await()
        val history = historyDeferred.await() ?: return@coroutineScope forecast
        val historyWithObservations = withHistoricalActuals(history, WeatherSource.TOMORROW_IO.id)
        forecast.copy(
            subHourly = history.subHourly,
            rawObservations = historyWithObservations.rawObservations,
            providerCurrentTemp = history.providerCurrentTemp,
            providerCurrentCondition = history.providerCurrentCondition,
            providerCurrentObservedAt = history.providerCurrentObservedAt,
            providerCurrentCloudCover = history.providerCurrentCloudCover,
        )
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
    private suspend fun fetchOpenMeteoForecast(parts: ForecastParts = ForecastParts.BOTH): RawFetch {
        val forecast = openMeteo.getForecast(
            latitude,
            longitude,
            days = ForecastHorizon.MAX_DAYS,
            historyDays = ACTUALS_HISTORY_DAYS,
            parts = parts,
        )
        forecast.nowcast?.let { nowcastStore?.saveSeries(latitude, longitude, it) }
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
                stationType = StationType.OFFICIAL,
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
     * [parts]: daily-only skips `/forecast/hourly` (the current-temp fallback then interpolates the
     * stored hours); hourly-only skips `/forecast` and the station observations, which keep the daily
     * cadence.
     */
    private suspend fun fetchNwsForecast(
        recentObservationsOnly: Boolean = false,
        parts: ForecastParts = ForecastParts.BOTH,
    ): RawFetch = coroutineScope {
        val grid = nwsApi.getGridPoint(latitude, longitude)
        if (!parts.daily) {
            val bundle = NwsForecastFetch.fetch(nwsApi, grid, parts)
            bundle.gridpointsFailure?.let { Log.w(TAG, "gridpoints fetch failed: $it") }
            return@coroutineScope RawFetch(
                hourly = bundle.hourlyPeriods.map { it.toHourlyForecast() },
                elapsedHourly = bundle.elapsedHourlyPeriods.map { it.toHourlyForecast() },
            )
        }

        // Resolve candidate observation stations concurrently with the forecast fetch, then try
        // official stations first for the historical window that drives actuals. Observation
        // fetches are best-effort: if they fail the forecast still renders from hourly/daily feeds.
        val stationsDeferred = async {
            bestEffort("observation stations") {
                grid.observationStationsUrl?.let { url -> getCachedOrFetchStations(url) }
            } ?: emptyList()
        }

        val bundle = NwsForecastFetch.fetch(nwsApi, grid, parts)
        // Preserves the previous bestEffort("gridpoints") diagnostic.
        bundle.gridpointsFailure?.let { Log.w(TAG, "gridpoints fetch failed: $it") }

        val observations = fetchNwsObservations(
            stationsDeferred.await(),
            // A daily-only fetch has no fresh hours; the header's forecast fallback reads the stored ones.
            bundle.rawHourlyPeriods.ifEmpty { storedNwsHourlyPeriods() },
            recentOnly = recentObservationsOnly,
        )

        RawFetch(
            providerCurrentTemp = observations.currentTemp,
            providerCurrentCondition = observations.currentCondition,
            providerCurrentObservedAt = observations.currentObservedAt,
            hourly = bundle.hourlyPeriods.map { it.toHourlyForecast() },
            elapsedHourly = bundle.elapsedHourlyPeriods.map { it.toHourlyForecast() },
            daily = NwsDailyMapper.buildDailyForecasts(
                bundle.forecastPeriods,
                bundle.gridpoints.dailyTemperatures,
                LocalDate.now(),
                bundle.hourlyPeriods,
            ),
            rawObservations = observations.observations,
            nwsDailyExtremes = bundle.gridpoints.dailyTemperatures,
        )
    }

    /** The stored NWS hours around now, as periods, for the header fallback of a daily-only fetch. */
    private fun storedNwsHourlyPeriods(): List<NwsApi.HourlyForecastPeriod> {
        val dao = weatherDao ?: return emptyList()
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        return dao.getHourlyForecasts(
            LocationMatch.quantize(latitude),
            LocationMatch.quantize(longitude),
            WeatherSource.NWS.id,
            now - 3 * 3_600_000L,
            now + 3 * 3_600_000L,
        ).map { row ->
            val local = java.time.Instant.ofEpochMilli(row.dateTime).atZone(zone)
            NwsApi.HourlyForecastPeriod(
                startTime = row.dateTime,
                localDate = local.toLocalDate().toString(),
                localHour = local.hour,
                temperature = row.temperature,
                shortForecast = row.condition,
                precipProbability = row.precipProbability,
                cloudCover = row.cloudCover,
            )
        }
    }

    /**
     * NWS current-observation fetch: per-station bundles → IDW current temp → stored readings.
     *
     * Split out of [fetchNwsForecast] (Phase 3a of
     * plans/260909-nws-fetch-unification.md) so the desktop forecast path mirrors Android's
     * `NwsForecastMapper` (forecast only) and the observation path mirrors `NwsCurrentObservationUpdater`.
     * Behavior is unchanged.
     */
    private suspend fun fetchNwsObservations(
        stations: List<NwsApi.StationInfo>,
        hourlyRaw: List<NwsApi.HourlyForecastPeriod>,
        historyDays: Long = HISTORY_DAYS,
        recentOnly: Boolean = false,
    ): NwsObservationFetch {
        val bundles = fetchObservationBundles(stations, historyDays, recentOnly)

        // All station latest readings, including moderately stale ones — IDW applies its own
        // time-decay weighting (1 - age/3h) so older stations reduce their own contribution
        // automatically. Matching Android which uses a 3-hour staleness window rather than 30 min.
        val allLatestReadings = bundles.mapNotNull { bundle ->
            bundle.latest
        }
        // Subset used to decide whether a fresh observed temp is available for the header.
        val freshLatestReadings = allLatestReadings.filter {
            System.currentTimeMillis() - it.timestamp <= FRESH_OBSERVATION_MS
        }

        // Real-or-nothing blend (shared with Android) for the header value; the forecast fallback
        // below is header-only too.
        val blend = NwsBlend.build(allLatestReadings, latitude, longitude, personalStationWeight)
        val currentTemp = blend?.temperature
            ?: TemperatureInterpolator.getInterpolatedTemperature(hourlyRaw.map { it.toHourlyForecast() })
            ?: hourlyRaw.firstOrNull()?.temperature

        val closestBundle = bundles.minByOrNull {
            com.weatherwidget.shared.observations.NwsObservationMapper.distanceKm(
                latitude, longitude, it.station.lat, it.station.lon,
            )
        }
        val currentCondition = closestBundle?.latest?.condition
            ?: hourlyRaw.firstOrNull()?.shortForecast

        // All readings (latest + historical) from all successful stations
        val rawObservations = bundles.flatMap { bundle ->
            buildList {
                bundle.latest?.let { add(it) }
                addAll(bundle.historical)
                // Preserved API row when the web reading won the temperature merge (Android parity).
                bundle.cloudCarrier?.let { add(it) }
            }
        }

        // The blend is NOT stored: like Android, the repository computes it from the station rows
        // on read, so its 3h IDW decay is weighted at render time rather than frozen at fetch time
        // (plans/261009-desktop-stops-storing-nws-blend.md). Here it only feeds the header value.
        // The header's observed-at anchors on the freshest readings.
        val latestReadings = freshLatestReadings.ifEmpty { allLatestReadings }
        val observations = rawObservations

        return NwsObservationFetch(
            currentTemp = currentTemp,
            currentCondition = currentCondition,
            currentObservedAt = latestReadings.maxOfOrNull { it.timestamp }
                ?: observations.firstOrNull()?.timestamp,
            observations = observations,
        )
    }

    /** Result of [fetchNwsObservations]: the current-reading anchor plus the rows to persist. */
    private data class NwsObservationFetch(
        val currentTemp: Float?,
        val currentCondition: String?,
        val currentObservedAt: Long?,
        val observations: List<ObservationReading>,
    )

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

        // The web leg: NWS's own latest-METAR file per station, started before the NWS station jobs
        // so both transports overlap. No token and no third party — not Synoptic, not
        // aviationweather.gov (plans/261015-nws-web-leg-from-nws-tgftp-metar-text.md).
        val web = NwsMetarTextWeb.start(
            this,
            nwsMetarTextApi,
            stations.take(MAX_OBSERVATION_STATIONS),
            latitude,
            longitude,
        )

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

        val deferreds = stations.take(MAX_OBSERVATION_STATIONS).mapIndexed { index, station ->
            async {
                // Latest is fetched regardless of whether the window returned anything (previously
                // gated on historical.isNotEmpty()). It anchors current temp and can be fresher than
                // the window's newest row. The latest + web merge is the step Android runs too.
                val latestResult = latestFetcher.fetch(station, index, latitude, longitude, web)
                val bundleLatest = latestResult.chosen
                val webReadings = latestResult.webRows
                latestResult.cloudCarrier?.let { carrier ->
                    NwsStationAttemptLog.cloudCarrier(station.id, carrier.timestamp, carrier.cloudCoverLow)
                        .let { weatherDao?.log(it.tag, it.message, it.level) }
                }

                // The history window with its web fallback: the step Android runs too (shared
                // NwsStationHistoryFetcher). Tri-state outcome, not bestEffort: an empty window
                // (station definitively silent) and a failed request (nothing learned) demand
                // opposite fetchedAt handling below. recentOnly shortens the window to
                // RECENT_OBSERVATION_WINDOW_MINUTES — the 7-day series is re-fetched identically by
                // the full forecast pull, so a current-temp cycle should not re-download ~500
                // rows/station. The fallback reuses this station's web rows, "use" tier only.
                val history = historyFetcher.fetch(
                    station, index, latitude, longitude, start.toString(), end.toString(),
                    web = if (ObservationFallbackPolicy.shouldFetchWeb(index)) ({ webReadings }) else null,
                )
                val historicalOutcome = history.apiOutcome
                val historical = history.rows
                val useWebForHistory = history.usedWebFallback

                if (historical.isNotEmpty() && !useWebForHistory) {
                    ObservationBundle(station, bundleLatest, historical, latestResult.cloudCarrier)
                } else if (useWebForHistory) {
                    // NWS history is empty or stale; surface the web readings we already fetched so
                    // the station still contributes. QC-flagged stay in historical for the stations
                    // UI; the chosen usable latest anchors current temp. All-flagged → latest=null.
                    ObservationBundle(station, bundleLatest, historical.filter { it != bundleLatest }, latestResult.cloudCarrier)
                } else if (recentOnly && bundleLatest != null) {
                    // Recent-window cycle against a slow station (KPAO can go ~90 min between
                    // reports): an empty window is legitimate, but the latest lookup still holds a
                    // usable reading for the current-temp blend. Emit it rather than dropping the
                    // station for the cycle. Deliberately scoped to recentOnly — on the full 7-day
                    // pull an empty window means a genuinely silent station, which must keep taking
                    // the touch/fail path below.
                    ObservationBundle(station, bundleLatest, emptyList())
                } else if (shouldTouchObservationFetchedAt(historicalOutcome, latestResult.webOutcome)) {
                    // Attempt completed but the station definitively yielded nothing storable
                    // (e.g. publishing only null-temperature reports). Record the attempt on the
                    // newest stored row so the stations list shows a fresh "Fetched" against an
                    // old "Reported" (silent station) instead of both timestamps frozen.
                    // This site's rows only, as Android: after a move the station's newest row may be
                    // a previous site's.
                    weatherDao?.touchLatestObservationFetchedAt(station.id, latitude, longitude, System.currentTimeMillis())
                    NwsStationAttemptLog.touched(station.id).let { weatherDao?.log(it.tag, it.message, it.level) }
                    null
                } else {
                    // Every upstream failed outright — leave fetchedAt frozen (a dead network
                    // must not masquerade as a silent station) and report the failure durably.
                    NwsStationAttemptLog.failed(station.id, (historicalOutcome as? FetchOutcome.Failed)?.reason, latestResult.webFailureReason)
                        .let { weatherDao?.log(it.tag, it.message, it.level) }
                    null
                }
            }
        }
        deferreds.mapNotNull { it.await() }
    }

    /**
     * One station's rows, already as stored readings: [latest] is the merged current reading (web
     * rows stamped `isWebFallback`), [historical] the API window or the web fallback.
     */
    private data class ObservationBundle(
        val station: NwsApi.StationInfo,
        val latest: ObservationReading?,
        val historical: List<ObservationReading>,
        /**
         * The API observation to store alongside a web-won latest (see
         * [NwsStationLatestFetcher.StationLatest.cloudCarrier]).
         */
        val cloudCarrier: ObservationReading? = null,
    )

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
        val grid = nwsApi.getGridPoint(latitude, longitude)
        
        // Resolve candidate observation stations once, then try official stations first.
        val stations = bestEffort("observation stations") {
            grid.observationStationsUrl?.let { url -> getCachedOrFetchStations(url) }
        } ?: emptyList()

        val bundles = fetchObservationBundles(stations, recentOnly = recentOnly)
        
        val allLatestReadings = bundles.mapNotNull { bundle ->
            bundle.latest
        }
        val freshLatestReadings = allLatestReadings.filter {
            System.currentTimeMillis() - it.timestamp <= FRESH_OBSERVATION_MS
        }

        // Real-or-nothing blend (shared with Android) for the header value; the nearest-station
        // fallback below is header-only too.
        val blend = NwsBlend.build(allLatestReadings, latitude, longitude, personalStationWeight)
        val currentTemp = blend?.temperature
            ?: bundles.minByOrNull { com.weatherwidget.shared.observations.NwsObservationMapper.distanceKm(latitude, longitude, it.station.lat, it.station.lon) }?.latest?.temperature

        val closestBundle = bundles.minByOrNull { com.weatherwidget.shared.observations.NwsObservationMapper.distanceKm(latitude, longitude, it.station.lat, it.station.lon) }
        val currentCondition = closestBundle?.latest?.condition

        // All readings (latest + historical) from all successful stations
        val rawObservations = bundles.flatMap { bundle ->
            buildList {
                bundle.latest?.let { add(it) }
                addAll(bundle.historical)
                // Preserved API row when the web reading won the temperature merge (Android parity).
                bundle.cloudCarrier?.let { add(it) }
            }
        }

        // Blend feeds the header only; never stored (see fetchNwsObservations).
        val latestReadings = freshLatestReadings.ifEmpty { allLatestReadings }
        val observations = rawObservations

        RawFetch(
            providerCurrentTemp = currentTemp,
            providerCurrentCondition = currentCondition,
            providerCurrentObservedAt = latestReadings.maxOfOrNull { it.timestamp } ?: observations.firstOrNull()?.timestamp,
            rawObservations = observations
        )
    }

    override suspend fun refreshNowcast(): com.weatherwidget.shared.nowcast.NowcastFetcher.Outcome? =
        nowcastStore?.let { com.weatherwidget.shared.nowcast.NowcastFetcher(httpClient, it).refresh(latitude, longitude) }

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
