package com.weatherwidget.desktop

import com.weatherwidget.shared.observations.NwsGapFillRun
import com.weatherwidget.shared.util.ClimateNormalsRun
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import com.weatherwidget.shared.history.WeatherApiHistoryRun
import com.weatherwidget.shared.history.HourlySnapshotRun
import com.weatherwidget.shared.actuals.NwsStationActualsRun
import com.weatherwidget.shared.graph.PriorDayCloudRun
import com.weatherwidget.shared.observations.CurrentConditionsRun
import com.weatherwidget.data.remote.ApiKeyRedaction
import com.weatherwidget.shared.util.DailyForecastWrite
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.data.local.desktop.*
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.*
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.GoogleHistoryRefill
import com.weatherwidget.data.remote.GoogleHistoryRun
import com.weatherwidget.data.remote.GoogleWeatherApi
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.data.remote.SourceFetchTable
import com.weatherwidget.data.remote.HourlyOnDemandRun
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.shared.actuals.DailyHistoryMaintenance
import com.weatherwidget.shared.actuals.DailyHistoryRun
import com.weatherwidget.shared.actuals.YesterdayDeltaCalculator
import com.weatherwidget.shared.util.ActualsFeedPolicy
import com.weatherwidget.shared.util.DailyColumnSource
import com.weatherwidget.shared.util.DailyHistoryFreeze
import com.weatherwidget.shared.util.DailyNoonCloudCover
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.actuals.BlendContribution
import com.weatherwidget.shared.actuals.DailyActualsAssembler
import com.weatherwidget.shared.util.PartialForecastDays
import com.weatherwidget.shared.actuals.DailyActualsSource
import com.weatherwidget.shared.actuals.StoredBlendGuard
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill
import com.weatherwidget.shared.actuals.NwsDailyExtremesFetch
import com.weatherwidget.shared.actuals.NwsStationActualsMaintenance
import com.weatherwidget.shared.actuals.StationDailyExtremes
import com.weatherwidget.shared.observations.NwsBlend
import com.weatherwidget.shared.observations.ObservationSourceMatcher
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.observations.ObservationWriteRun
import com.weatherwidget.shared.observations.ObservationTimelineNormalizer
import com.weatherwidget.shared.history.ProviderHistoryDecision
import com.weatherwidget.shared.history.ProviderHistoryFailureClass
import com.weatherwidget.shared.history.ProviderHistoryPolicy
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.nowcast.NowcastFetcher
import com.weatherwidget.shared.nowcast.NowcastRefreshPolicy
import com.weatherwidget.shared.nowcast.NowcastStore
import com.weatherwidget.shared.util.SpatialInterpolator
import com.weatherwidget.widget.CurrentTemperatureResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.QuotaState


class DesktopWeatherRepository(
    private val weatherService: WeatherApiClient,
    private val weatherDao: DesktopWeatherDao,
    // Readable so a caller keyed on the repository can confirm which site it was built for.
    val latitude: Double,
    val longitude: Double,
    private val weatherSource: String,
    private val personalStationWeight: Double = 1.0,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    /**
     * Whether housekeeping may run now: on AC power with the screen off (user, 2026-10-09; Android's
     * `HistoryPruneWorker` waits for charging + device idle). A machine without a battery counts as
     * on AC; an undetectable screen state counts as on, so the prune waits.
     */
    private val housekeepingAllowed: () -> Boolean = {
        PowerDetector.getPowerState().isCharging && !ScreenStateDetector.isScreenOn()
    },
    /** On a charger (no battery counts as charging): the daily-history upkeep's gate ([DailyHistoryRun.upkeepIfDue]). */
    private val isCharging: () -> Boolean = { PowerDetector.getPowerState().isCharging },
    /**
     * The config the hourly snapshot rule ([HourlySnapshotRun]) reads at write time — the displayed
     * source and the 8-day view gate. Null saves every fetch (tests); production reads the config file.
     */
    private val snapshotConfig: () -> DesktopConfig? = { null },
) {
    data class RefreshOutcome(
        val snapshot: ForecastSnapshot,
        val suppliedObservations: Boolean,
    )
    /** The [WeatherSource] resolved once from [weatherSource]; every method previously re-derived this. */
    private val displaySource: WeatherSource = WeatherSource.fromDisplaySource(weatherSource)

    /** Result of the current-temperature resolution: display temp, forecast delta, yesterday delta. */
    data class ResolvedCurrentTemp(
        val displayTemp: Float?,
        val appliedDelta: Float?,
        val deltaFromYesterday: Float?,
        /** The blended observation the display is anchored on, and when it was observed (diagnostics). */
        val observedTemp: Float? = null,
        val observedAt: Long? = null,
        /** Inputs the resolve saw inside its window (diagnostics). */
        val windowObservations: Int = 0,
        val windowHourly: Int = 0,
    )

    /** Epoch bounds + zone/nowLocal context for the current-temp resolution window. */
    private data class CurrentTempWindow(
        val minEpoch: Long,
        val maxEpoch: Long,
        val zoneId: ZoneId,
        val nowLocal: LocalDateTime,
    )

    /**
     * Builds the [CurrentTempWindow] shared by [resolveForForecastResult] and
     * [resolveDominantContribution] so the popup and the notification derive the current reading
     * from the same slice of observations + hourly forecasts.
     */
    private fun currentTempWindowFor(now: Long): CurrentTempWindow {
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        val window = CurrentTemperatureResolver.buildCurrentTempResolutionWindow(nowLocal)
        val zoneId = ZoneId.systemDefault()
        val minEpoch = window.start.atZone(zoneId).toInstant().toEpochMilli()
        val maxEpoch = window.end.atZone(zoneId).toInstant().toEpochMilli()
        return CurrentTempWindow(minEpoch, maxEpoch, zoneId, nowLocal)
    }

    private fun resolveForForecastResult(
        hourly: List<HourlyForecast>,
        observations: List<ObservationReading>,
        now: Long,
        resultLogLevel: String = "DEBUG",
    ): ResolvedCurrentTemp {
        val run = com.weatherwidget.widget.CurrentTempRun.run(
            hourly = hourly,
            observations = observations,
            displaySource = displaySource,
            latitude = latitude,
            longitude = longitude,
            nowMs = now,
            personalStationWeight = personalStationWeight,
            resultLogLevel = resultLogLevel,
        )
        return ResolvedCurrentTemp(
            run.displayTemp,
            run.appliedDelta,
            run.deltaFromYesterday,
            observedTemp = run.observedTemp,
            observedAt = run.observedAt,
            windowObservations = run.windowObservations,
            windowHourly = run.windowHourly,
        )
    }

    /**
     * The station holding the largest blend weight behind the temperature currently on screen, or
     * null when the blend produced none.
     *
     * Deliberately reuses [CurrentTemperatureResolver.buildCurrentTempResolutionWindow] and the same
     * lookback/lookahead as [resolveForForecastResult]: a different window selects a different
     * "latest" reading, and a notification derived from one window while the popup shows another
     * would name a station the user cannot find on their own graph.
     */
    fun resolveDominantContribution(raw: RawFetch, now: Long): BlendContribution? {
        val win = currentTempWindowFor(now)

        return ActualsAggregator.resolveCurrentObservationDetails(
            observations = raw.rawObservations.filter { it.timestamp in win.minEpoch..win.maxEpoch },
            hourlyForecasts = raw.hourly.filter { it.dateTime in win.minEpoch..win.maxEpoch },
            displaySourceId = displaySource.id,
            userLat = latitude,
            userLon = longitude,
            nowMs = now,
            lookbackHours = CurrentTemperatureResolver.RESOLUTION_LOOKBACK_HOURS,
            lookaheadHours = CurrentTemperatureResolver.RESOLUTION_LOOKAHEAD_HOURS,
            personalStationWeight = personalStationWeight,
        )?.dominantContribution?.contribution
    }

    fun resolveCurrentTempInMemory(raw: RawFetch, now: Long): ResolvedCurrentTemp {
        // High-frequency path: runs on every genmon panel connect and every UI minute tick.
        // VERBOSE keeps the CURR_TEMP_RESULT row out of app_logs (the sparse fetch-cycle
        // resolves in loadCached/refreshObservations stay DEBUG and remain queryable).
        return resolveForForecastResult(raw.hourly, raw.rawObservations, now, resultLogLevel = "VERBOSE")
    }

    /**
     * Null-cache fallback while the just-fetched rows are becoming visible to the cache read: the
     * cached path's rule ([resolveForForecastResult], `:shared` CurrentTempRun) on the fetched rows —
     * the blend if one is there, else the bare forecast. A provider-current field is never the
     * temperature here: NWS's was a forecast or a stale station's reading when nothing blended
     * (plans/261024-nws-fetch-time-current-reading-shared.md).
     */
    private fun rawFetchToSnapshot(raw: RawFetch): ForecastSnapshot {
        val observations = if (displaySource.supportsTemperatureActuals) raw.rawObservations else emptyList()
        val resolved = resolveForForecastResult(raw.hourly, observations, currentTimeMillis())
        return ForecastSnapshot(
            raw = raw,
            resolved = ResolvedView(
                currentTemp = resolved.displayTemp,
                currentCondition = raw.providerCurrentCondition,
                currentObservedAt = raw.providerCurrentObservedAt.takeIf {
                    displaySource.supportsTemperatureActuals
                },
                appliedDelta = resolved.appliedDelta,
                deltaFromYesterday = resolved.deltaFromYesterday,
            ),
        )
    }

    suspend fun loadCached(now: Long = currentTimeMillis()): ForecastSnapshot? = withContext(Dispatchers.IO) {
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours for cache
        // Cover the widest zoom-out (6 days back) so the continuous-zoom graph never truncates history.
        val stitchedStart = now - (DesktopGraphUtils.MAX_BACK_HOURS * 3600 * 1000L)
        val hourly = weatherDao.getHourlyWithHistory(latitude, longitude, weatherSource, stitchedStart, now + HourlyOnDemand.REACH_HOURS * 3_600_000L, maxAgeMs)
        val daily = weatherDao.getDailyForecasts(latitude, longitude, weatherSource)
        // Same window as the hourly read so the frozen forecast curve reaches as far back as the
        // curve it annotates; empty for every source but Open-Meteo, which is the only one with a
        // previous-runs product.
        val priorCloud = weatherDao.getPriorDayCloudForecast(latitude, longitude, stitchedStart, now)
        // The bands' frozen forecast, from our own snapshots under the REAL source id — the
        // Previous Runs API serves no band data (see PriorDayBandForecast).
        val priorCloudBands = weatherDao.getPriorDayBandForecast(latitude, longitude, weatherSource, stitchedStart, now)
        val retroCloudResult = weatherDao.getCloudActuals(latitude, longitude, stitchedStart, now, weatherSource)
        val retroCloud = retroCloudResult.hours
        val dominantCloudContribution = retroCloudResult.dominantContribution
        
        // Cover the widest zoom-out (6 days back) so the actual line spans the whole window,
        // matching the hourly read above. Observations exist ~7-14 days back (NWS HISTORY_DAYS=7,
        // 18-month retention); only the read window was capping the actual line at 2 days.
        val obsStart = now - (DesktopGraphUtils.MAX_BACK_HOURS * 3600 * 1000L)
        val obsEnd = now + (2 * 3600 * 1000L) // Include some cushion
        val observations = weatherDao.getObservationsInRange(obsStart, obsEnd, latitude, longitude)
            .map { it.toReading() }

        // The range query returns every API's observations; the displayed current condition /
        // "observed at" must come ONLY from the displayed source's actuals provider. Without this
        // filter a non-NWS view would show an NWS station's timestamp/condition.
        val matchedSourceObs = observations.filter {
            ObservationSourceMatcher.matchesActualSource(
                stationId = it.stationId,
                api = it.api,
                source = displaySource,
            )
        }
        val sourceObs = ObservationTimelineNormalizer.normalize(
            matchedSourceObs,
            ActualsProviderResolver.providerIdFor(displaySource),
        )

        // Prefer the NWS blend — the IDW-weighted truth across all stations. Raw station rows can
        // have newer timestamps (from historical fetches) but those are single-station readings,
        // not the calibrated blend. Computed here from the station rows, at `now`, exactly as
        // Android's CurrentObservationReader does; desktop no longer stores it, so its 3h decay is
        // weighted at render time instead of frozen at the last fetch
        // (plans/261009-desktop-stops-storing-nws-blend.md).
        val nwsBlend = if (ActualsProviderResolver.providerIdFor(displaySource) == WeatherSource.NWS.id) {
            NwsBlend.current(observations, latitude, longitude, personalStationWeight, now)
        } else {
            null
        }
        val newestObs = nwsBlend ?: sourceObs.maxByOrNull { it.timestamp }

        // Freshness gate only governs whether the *current condition* is shown as observed vs forecast.
        val latestObs = newestObs?.takeIf { now - it.timestamp < FRESH_OBSERVATION_MS }

        val resolved = resolveForForecastResult(hourly, observations, now)
        val actualsLoad = loadDailyActuals(daily, hourly, observations, now)
        val actuals = actualsLoad.actuals
        val snapshots = loadDailySnapshots(daily)

        if (hourly.isEmpty() && daily.isEmpty()) {
            return@withContext null
        }

        ForecastSnapshot(
            raw = RawFetch(
                hourly = hourly,
                daily = appendClimateNormalGaps(daily, now),
                rawObservations = observations,
                dailyActuals = actuals,
                dailySnapshots = snapshots,
                climateNormals = cachedNormalsByMonthDay(),
                historyDepths = actualsLoad.historyDepths,
            ),
            resolved = ResolvedView(
                currentTemp = resolved.displayTemp,
                currentCondition = latestObs?.condition ?: hourly.firstOrNull()?.condition,
                currentObservedAt = newestObs?.timestamp,
                appliedDelta = resolved.appliedDelta,
                deltaFromYesterday = resolved.deltaFromYesterday,
            ),
            priorDayCloudForecast = priorCloud,
            retroCloudActual = retroCloud,
            priorDayBandForecast = priorCloudBands,
            retroCloudBands = retroCloudResult.bands,
            dominantCloudContribution = dominantCloudContribution,
            // The header reserves room for the widest source's rain % (HeaderContent.precipLabels).
            allSourcesHourly = weatherDao.getHourlyForecastsAllSources(
                latitude, longitude, stitchedStart, now + HourlyOnDemand.REACH_HOURS * 3_600_000L,
            ),
        )
    }

    // Deepest history (in days back) already pulled this session. Seeded at the ~7 days the launch
    // backfill / normal refresh cover, so a default-zoom popup never triggers a fetch. Reset for free
    // on location/source change — the repository is re-`remember`ed with new keys in Main.kt.
    @Volatile private var deepestHistoryDaysFetched = BASELINE_HISTORY_DAYS
    private val historyFetchMutex = Mutex()

    /** Days of `past_days` needed to cover [neededBackHours] of visible history, plus a day of margin. */
    private fun neededHistoryDays(neededBackHours: Int): Int =
        (kotlin.math.ceil(neededBackHours / 24.0).toInt() + 1).coerceIn(1, MAX_HISTORY_DAYS)

    /**
     * Cheap, no-network check of whether [ensureHistory] would actually fetch at this depth. Lets the
     * UI show the "fetching" toast only when a real pull is about to happen, not on every zoom tick.
     * NWS can extend deep station history; WeatherAPI can repair its bounded previous-day archive.
     */
    fun needsDeeperHistory(neededBackHours: Int): Boolean =
        when (weatherSource) {
            WeatherSource.NWS.id,
            WeatherSource.OPEN_METEO.id ->
                neededHistoryDays(neededBackHours) > deepestHistoryDaysFetched
            WeatherSource.WEATHER_API.id ->
                neededBackHours >= 24 && currentTimeMillis().let { now ->
                    val w = WeatherApiHistoryRun.window(latitude, longitude, now)
                    WeatherApiHistoryRun.decide(
                        latitude, longitude,
                        stored = weatherDao.getObservationsInRange(w.startMs, w.endMs, latitude, longitude).map { it.toReading() },
                        latestResult = weatherDao.getLatestLogByTagAndMessagePrefix(WeatherApiHistoryRun.RESULT_TAG, w.logPrefix)?.message,
                        nowMs = now,
                    ) is ProviderHistoryDecision.Fetch
                }
            else -> false
        }

    /**
     * Pulls supported provider history when the graph reaches a locally missing window. Idempotent
     * and guarded so rapid wheel events do not spam the network; returns true only when new data was
     * persisted (caller then reloads the cache).
     *
     * Deliberately does NOT backfill an Open-Meteo forecast curve: GENERIC_GAP is future-only and must
     * never fill history (its Open-Meteo decimals would masquerade as the real, whole-degree NWS
     * forecast). Provider history is stored only as observations; past forecast curves continue to
     * come solely from real accumulated snapshots.
     */
    suspend fun ensureHistory(neededBackHours: Int): Boolean = withContext(Dispatchers.IO) {
        if (weatherSource == WeatherSource.WEATHER_API.id) {
            if (neededBackHours < 24) return@withContext false
            return@withContext historyFetchMutex.withLock {
                val stored = backfillWeatherApiHistoryIfNeeded(currentTimeMillis())
                if (stored > 0) recomputeDailyExtremes(currentTimeMillis())
                stored > 0
            }
        }
        val neededDays = neededHistoryDays(neededBackHours)
        if (weatherSource == WeatherSource.OPEN_METEO.id) {
            return@withContext runHistoryFetch(neededDays, "Open-Meteo") {
                val rawFetch = weatherService.fetchHistory(neededDays)
                val backfill = HistoricalActualsBackfill.build(
                    hourly = rawFetch.subHourly.ifEmpty { rawFetch.hourly },
                    latitude = latitude,
                    longitude = longitude,
                    sourceId = WeatherSource.OPEN_METEO.id,
                    nowMs = currentTimeMillis(),
                )
                if (backfill.isNotEmpty()) {
                    persistObservations(backfill, currentTimeMillis())
                    recomputeDailyExtremes(currentTimeMillis())
                    true
                } else {
                    false
                }
            }
        }
        if (weatherSource != WeatherSource.NWS.id) return@withContext false
        runHistoryFetch(neededDays, "NWS") {
            val obs = weatherService.fetchObservationHistory(neededDays.toLong())
            if (obs.isNotEmpty()) {
                persistObservations(obs, currentTimeMillis())
                true
            } else {
                false
            }
        }
    }

    private val hourlyExtendMutex = Mutex()

    /**
     * A tapped or panned-to day the displayed source's stored hourly does not cover
     * ([HourlyOnDemand], any source): its normal fetch — deep enough for Google — saved as a refresh
     * saves it (live rows, daily rows, this 4 h bucket's history snapshot), without the rest of a
     * refresh (`plans/261009-on-demand-hourly-shared-single-source-fetch.md`). The run and its log are
     * `:shared` [HourlyOnDemandRun], as Android's worker. Serialized and re-checked under the lock, so a second tap on the same day waits for the first and then fetches
     * nothing. Returns true when it stored hours.
     */
    suspend fun extendHourlyFor(date: LocalDate, now: Long = currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        hourlyExtendMutex.withLock {
            val result = HourlyOnDemandRun.run(
                DesktopHourlyOnDemandHost(weatherDao, weatherSource, latitude, longitude, now) { request ->
                    persistForecastResult(
                        weatherService.fetchForecastAhead(request.hours),
                        now,
                        keep = HourlyOnDemand.keep(weatherSource, request),
                    ).liveHours > 0
                },
                weatherSource,
                date,
                ZoneId.systemDefault(),
                now,
            )
            weatherDao.log(HourlyOnDemandRun.TAG, HourlyOnDemandRun.line(weatherSource, date, result), HourlyOnDemandRun.level(result))
            result.stored
        }
    }

    /**
     * Shared scaffold for [ensureHistory]'s NWS / Open-Meteo branches: the depth check +
     * double-check under [historyFetchMutex], the try/catch + error log, the
     * [deepestHistoryDaysFetched] bump, and the "deepened" log line. The [fetcher] lambda owns
     * the provider-specific fetch + upsert (and any recompute); it returns true when new rows
     * were persisted.
     */
    private suspend fun runHistoryFetch(
        neededDays: Int,
        errorLabel: String,
        fetcher: suspend () -> Boolean,
    ): Boolean {
        if (neededDays <= deepestHistoryDaysFetched) return false
        return historyFetchMutex.withLock {
            // Re-check under the lock: a concurrent call may have already deepened coverage.
            if (neededDays <= deepestHistoryDaysFetched) return@withLock false
            var fetchedAny = false
            try {
                fetchedAny = fetcher()
            } catch (e: Exception) {
                Log.e("DesktopWeatherRepository", "On-demand $errorLabel observation history fetch failed: $e")
            }
            if (fetchedAny) {
                deepestHistoryDaysFetched = neededDays
                Log.i("DesktopWeatherRepository", "ensureHistory deepened to ${neededDays}d back (source=$weatherSource)")
            }
            fetchedAny
        }
    }

    /** The last prior-day cloud attempt ([PriorDayCloudRun]). */
    private val lastPriorCloudFetch = PriorDayCloudRun.LastAttempt()

    /** The cloud graph's frozen forecast curve: `:shared` [PriorDayCloudRun] owns the rule; this stores rows. */
    private suspend fun maybeFetchPriorDayCloudForecast(now: Long) = PriorDayCloudRun.run(
        DesktopPriorDayCloudHost(weatherDao, latitude, longitude, lastPriorCloudFetch) { pastDays, _ ->
            weatherService.fetchPriorDayCloudForecast(pastDays)
        },
        sourceId = weatherSource,
        nowMs = now,
    )

    /**
     * The observation pull a full refresh makes for a source whose actuals come from another feed
     * (a borrowing source, or one redirected by the user's actuals-provider choice), and for NWS
     * itself, whose forecast fetch is the forecast only (F6). Logged `BORROWED_NWS_RECOVERY` either
     * way, `source=` telling the two apart. Which feed, and
     * whether to narrow it to the recent window, is [ActualsFeedPolicy.fullRefreshFetch], shared
     * with Android. The frequent observations loop fetches only the recent window; this bounded
     * recovery pull makes sure a suspend/restart gap, or a provider switch, doesn't stay visible once
     * the upstream reports are available. Failures are best-effort and leave the cached gap intact.
     */
    private suspend fun fetchBorrowedRecovery(
        plan: ActualsFeedPolicy.ObservationFetch?,
        userLocationChange: Boolean,
    ): RawFetch {
        if (plan == null) return RawFetch()
        return try {
            weatherService.fetchObservationsOnly(recentOnly = plan.recentOnly, userLocationChange = userLocationChange)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "BORROWED_${plan.feed.id}_RECOVERY failed source=${displaySource.id}: $e")
            RawFetch()
        }
    }

    suspend fun refresh(
        now: Long = currentTimeMillis(),
        /** The UI's picker-save refresh; see [WeatherApiClient.fetchObservationsOnly]. */
        userLocationChange: Boolean = false,
        /** Why this refresh runs, for `REFRESH_ENTER` (each full Google refresh spends quota). */
        reason: String = "unspecified",
        /**
         * A previous day refreshed on the Forecast History screen: Google may refill its stale
         * elapsed hours from `history/hours` ([GoogleHistoryRefill]). Null on every other refresh.
         */
        refillDay: LocalDate? = null,
        /** The parts a scheduled refresh planned ([ForecastParts.unblocked]); null = a forced refresh (both). */
        parts: ForecastParts? = null,
        /** The horizon a scheduled refresh planned ([DesktopForecastPlanner]); null = judge it now. */
        horizon: ForecastHorizonCadence.Horizon? = null,
        /** The user's Refresh button: an unchanged daily row is written anyway ([DailyForecastWrite]). */
        userRefresh: Boolean = false,
    ): ForecastSnapshot = refreshWithOutcome(
        now,
        userLocationChange,
        reason = reason,
        refillDay = refillDay,
        parts = parts,
        horizon = horizon,
        userRefresh = userRefresh,
    ).snapshot

    /**
     * Runs a full refresh and reports whether it already supplied observation data. Schedulers can
     * use this provider-neutral signal to avoid an immediately redundant observations-only call.
     */
    suspend fun refreshWithOutcome(
        now: Long = currentTimeMillis(),
        userLocationChange: Boolean = false,
        /**
         * Publish first, then fill history: NWS pulls only the recent observation window here, and
         * the caller is expected to follow with [refreshObservationWindow] after it has published.
         * Used by the daemon's launch/wake catch-up, where the 7-day pull once held the forecast
         * back ~30 s (performance/261004-desktop-wake-refresh-stalls-on-7day-obs-window.md).
         */
        deferObservationWindow: Boolean = false,
        reason: String = "unspecified",
        refillDay: LocalDate? = null,
        parts: ForecastParts? = null,
        horizon: ForecastHorizonCadence.Horizon? = null,
        userRefresh: Boolean = false,
    ): RefreshOutcome = withContext(Dispatchers.IO) {
        Log.i(TAG, "refresh() started source=$weatherSource")
        val fetchParts = parts ?: ForecastParts.BOTH
        // LONG, like every fetch (ForecastHorizonCadence); null only without a DB.
        val fetchHorizon = horizon
            ?: DesktopForecastHorizon(weatherDao, weatherSource, latitude, longitude).forced()
        if (!fetchParts.daily) return@withContext refreshHourlyOnly(now, reason, fetchHorizon)
        // Entry marker. The terminal REFRESH row below only lands on success, so without this an
        // aborted fetch is invisible unless it also throws (the catch logs a WARN); a hang or a
        // cancellation leaves no trace at all.
        weatherDao.log(
            tag = "REFRESH_ENTER",
            message = "reason=$reason parts=$fetchParts horizon=$fetchHorizon " +
                "source=$weatherSource lat=$latitude lon=$longitude",
            level = "INFO",
        )
        // For the failure path: a failed fetch still runs the follow-ups that do not need its reply.
        var stored = false
        var followUpsRan = false
        try {
            // NOTE: no Open-Meteo history backfill here. GENERIC_GAP is future-only; past forecast history
            // must come only from real accumulated NWS snapshots (a fresh install simply starts sparse and
            // fills in as it runs), so we never seed Open-Meteo decimals into the past.
            val borrowedPlan = ActualsFeedPolicy.fullRefreshFetch(
                displaySource = displaySource,
                latitude = latitude,
                longitude = longitude,
                deferObservationWindow = deferObservationWindow,
                // Stored rows are per site, not per displayed source: reuse what is already here.
                newestStoredFeedMs = ActualsFeedPolicy.feedFor(displaySource, latitude, longitude)
                    ?.let { weatherDao.getNewestObservationTimestampForApi(it.id, latitude, longitude) },
                nowMs = now,
            )
            val (forecastResult, borrowedRecovery) = coroutineScope {
                val forecast = async { when {
                        // The one horizon the service cannot derive from the markers itself.
                        userRefresh -> weatherService.fetchForecast(
                            parts = fetchParts,
                            refillDay = refillDay,
                            horizon = fetchHorizon,
                        )
                        refillDay != null -> weatherService.fetchForecast(
                            parts = fetchParts,
                            refillDay = refillDay,
                        )
                        fetchParts != ForecastParts.BOTH ->
                            weatherService.fetchForecast(parts = fetchParts)
                        else -> weatherService.fetchForecast()
                    } }
                val recovery = async { fetchBorrowedRecovery(borrowedPlan, userLocationChange) }
                forecast.await() to recovery.await()
            }
            val result = if (borrowedRecovery.rawObservations.isEmpty()) {
                forecastResult
            } else {
                forecastResult.copy(
                    rawObservations = forecastResult.rawObservations + borrowedRecovery.rawObservations,
                )
            }

            persistForecastResult(
                result,
                now,
                keep = HourlyOnDemand.keep(weatherSource, null, fetchHorizon),
                userRefresh = userRefresh,
                borrowedFeed = borrowedPlan?.feed?.takeIf { borrowedRecovery.rawObservations.isNotEmpty() },
            )
            stored = true
            markHorizon(fetchHorizon, result)
            if (refillDay != null) refillStaleGoogleHours(result, now, refillDay)

            if (borrowedPlan != null) {
                val feedId = borrowedPlan.feed.id
                weatherDao.log(
                    tag = "BORROWED_${feedId}_RECOVERY",
                    message = "source=${displaySource.id} " +
                        // Synoptic recovery asks for the gap since the newest stored row, not 24 h;
                        // its window is in the BORROWED_SYNOPTIC_FETCH line.
                        (if (feedId == WeatherSource.METAR.id) "hours=${DesktopWeatherService.RECOVERY_BORROWED_METAR_HOURS} " else "") +
                        "recentOnly=${borrowedPlan.recentOnly} " +
                        "rows=${borrowedRecovery.rawObservations.size} " +
                        "stored=${borrowedRecovery.rawObservations.size} " +
                        "stations=${borrowedRecovery.rawObservations.map { it.stationId }.distinct().size}",
                    level = "INFO",
                )
            }
            // What rides this source's fetch is the `:shared` rule, as on Android. Before the extremes
            // recompute, so WeatherAPI's history hours count in this refresh's actuals.
            followUpsRan = true
            val historyObsCount = runFollowUps(fetchParts, stored = true, now)

            val extremesCount = runPostFetchBackfills(now)

            // Best-effort: ensure climate normals are cached for the future-day fallback. One network
            // fetch per location, then served from cache; never fails the main refresh.
            ensureClimateNormals()

            // Same retention policy as Android (RetentionPolicy); the permanent backfill markers stay.
            weatherDao.applyRetention(now, protectedLogTags = PERMANENT_LOG_MARKERS)
            scrubApiKeysFromAppLogsIfNeeded()
            pruneHistorySnapshotsIfDue(now)

            // Persistent pipeline-health summary
            weatherDao.log(
                tag = "REFRESH",
                message = "source=$weatherSource hourly=${result.hourly.size} daily=${result.daily.size} " +
                    "obs=${result.rawObservations.size} historyObs=$historyObsCount extremes=$extremesCount" +
                    (if (deferObservationWindow && ActualsFeedPolicy.hasDeferredHistoryWindow(displaySource, latitude, longitude)) " obsWindow=deferred" else ""),
            )
            weatherDao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok(displaySource.id), "INFO")

            RefreshOutcome(
                snapshot = loadCached(now) ?: rawFetchToSnapshot(result),
                suppliedObservations = result.rawObservations.isNotEmpty(),
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                // Cancellation is not a pipeline failure, so it stays out of CURRENT_TEMP_STATUS
                // (which drives the staleness badge) — but it must not be silent either. A refresh
                // cancelled mid-flight by its caller's scope being torn down previously left no row
                // at all, which is why a dropped manual refresh was indistinguishable from a click
                // that never happened.
                weatherDao.log(
                    tag = "REFRESH_CANCELLED",
                    message = "reason=$reason source=$weatherSource lat=$latitude lon=$longitude",
                    level = "WARN",
                )
            } else {
                weatherDao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.failure(displaySource.id, e), "WARN")
                if (!followUpsRan) runFollowUps(fetchParts, stored, now)
            }
            throw e
        }
    }

    /**
     * `:shared` [SourceFetchTable.runFollowUps] for this repository's source (none for a source with no
     * fetch, such as GENERIC_GAP). Returns the WeatherAPI history hours stored.
     */
    private suspend fun runFollowUps(parts: ForecastParts, stored: Boolean, now: Long): Int {
        val source = WeatherSource.entries.firstOrNull { it.id == weatherSource } ?: return 0
        return SourceFetchTable.runFollowUps(
            source,
            parts,
            stored,
            SourceFetchTable.FollowUpJobs(
                priorDayCloud = { maybeFetchPriorDayCloudForecast(now) },
                weatherApiHistory = { backfillWeatherApiHistoryIfNeeded(now) },
                nwsStationActuals = { fillNwsStationActualsIfNeeded(now) },
                log = { tag, message, level -> weatherDao.log(tag, message, level) },
            ),
        )
    }

    /** A judged fetch that answered restarts the cadence ([DesktopForecastHorizon]). */
    private suspend fun markHorizon(horizon: ForecastHorizonCadence.Horizon?, result: RawFetch) {
        if (result.daily.isEmpty() && result.hourly.isEmpty()) return
        DesktopForecastHorizon(weatherDao, weatherSource, latitude, longitude).mark(horizon)
    }

    /**
     * An hourly-only refresh ([ForecastParts.HOURLY], when a quota refuses the daily product): the
     * hourly rows and their history snapshot, nothing else — no daily rows, observations, actuals
     * backfills or `REFRESH` row (`getLastSuccessfulFetch` reads that row as the daily forecast's age).
     * Logged `REFRESH_HOURLY`.
     */
    private suspend fun refreshHourlyOnly(now: Long, reason: String, horizon: ForecastHorizonCadence.Horizon?): RefreshOutcome {
        weatherDao.log(
            tag = "REFRESH_ENTER",
            message = "reason=$reason parts=HOURLY horizon=$horizon source=$weatherSource lat=$latitude lon=$longitude",
            level = "INFO",
        )
        val result = weatherService.fetchForecast(parts = ForecastParts.HOURLY)
        persistForecastResult(
            result.copy(rawObservations = emptyList()),
            now,
            keep = HourlyOnDemand.keep(weatherSource, null, horizon),
            daily = false,
        )
        markHorizon(horizon, result)
        runFollowUps(ForecastParts.HOURLY, stored = true, now)
        weatherDao.log(tag = "REFRESH_HOURLY", message = "source=$weatherSource hourly=${result.hourly.size} reason=$reason")
        return RefreshOutcome(
            snapshot = loadCached(now) ?: rawFetchToSnapshot(result),
            suppliedObservations = false,
        )
    }

    /**
     * Second half of a [refreshWithOutcome] run with `deferObservationWindow = true`: the daily pass of
     * the shared [NwsGapFillRun] (as Android's full sync) — when day−2, yesterday or today lacks NWS
     * history, the last 3 days per station, nearest first, stored as a refresh stores them and the
     * extremes recomputed; nothing at all when every day is complete. It pulled 7 days on every wake
     * (plans/261025-shared-nws-gap-fill.md). Best-effort, because the forecast and current reading were
     * already published: returns the readings stored (0 when nothing was needed or no source in
     * [sourcesServed] takes its actuals from NWS — [NwsGapFillRun.dailyForSources] owns that gate and the
     * `NWS_DAILY_BACKFILL_RESULT` line), [OBS_WINDOW_UNREACHABLE] when NWS never answered (the daemon
     * retries it on the shared schedule, [ObservationWindowRetry]), or [OBS_WINDOW_FAILED] on any other
     * failure. Also run by the Settings Refresh, after its fetch.
     */
    suspend fun refreshObservationWindow(
        sourcesServed: List<WeatherSource>,
        reason: String,
        now: Long = currentTimeMillis(),
    ): Int = withContext(Dispatchers.IO) {
        try {
            val result = NwsGapFillRun.dailyForSources(
                nwsGapFillHost(now), latitude, longitude, sourcesServed, reason, now, ZoneId.systemDefault(),
            ) ?: return@withContext 0
            if (result.unreachable) OBS_WINDOW_UNREACHABLE else result.rows
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            weatherDao.log(
                tag = "OBS_WINDOW_REFRESH",
                message = "source=$weatherSource reason=$reason failed: $e",
                level = "WARN",
            )
            OBS_WINDOW_FAILED
        }
    }

    /** The fill's Host: stores and recomputes exactly as a refresh does. Also run by `DesktopNwsGapFillHostContractTest`. */
    internal fun nwsGapFillHost(now: Long) = DesktopNwsGapFillHost(
        weatherService = weatherService,
        weatherDao = weatherDao,
        store = { persistObservations(it, now) },
        recompute = { days -> recomputeDailyExtremesForDays(days, now) },
    )

    /**
     * The desktop Observations Refresh on a previous day: the [GoogleHistoryRefill] of [day] alone —
     * one `history/hours` request, no forecast — and only when the displayed source is Google and
     * the day has stale hours, so nothing is billed when there is nothing to fix
     * (plans/261014-observations-refresh-fetches-observations-only.md).
     */
    suspend fun refillGoogleHistory(day: LocalDate) = withContext(Dispatchers.IO) {
        if (weatherSource != WeatherSource.GOOGLE_WEATHER.id) return@withContext
        if (googleHistory.staleHours(day, currentTimeMillis()).isEmpty()) {
            weatherDao.log(GoogleHistoryRefill.LOG_TAG, "day=$day stale=0 reason=nothing_stale", "INFO")
            return@withContext
        }
        val result = weatherService.fetchGoogleHistory()
        if (result.providerHistoryHourly.isEmpty()) {
            weatherDao.log(GoogleHistoryRefill.LOG_TAG, "day=$day reason=history_unavailable", "INFO")
            return@withContext
        }
        refillStaleGoogleHours(result, currentTimeMillis(), day)
    }

    private val googleHistory get() = GoogleHistoryRun(DesktopGoogleHistoryHost(weatherDao, latitude, longitude))

    /** A previous day's Forecast History refresh: [GoogleHistoryRun.refill], Google only. */
    private suspend fun refillStaleGoogleHours(result: RawFetch, now: Long, day: LocalDate) {
        if (weatherSource != WeatherSource.GOOGLE_WEATHER.id) return
        googleHistory.refill(result.providerHistoryHourly, day, now)
    }

    /**
     * Files the elapsed hours [persistForecastResult] dropped from the live write into history —
     * only for hours this source has no history row for at this site. A fresh install or a new
     * location has no earlier fetch to have snapshotted them, so the graph's past forecast line
     * is blank for a day while every payload carries the values (`ElapsedForecastBackfill`).
     * Same source only; existing rows are never touched, so in steady state this stores nothing.
     */
    /** This fetch's snapshot label from the shared rule, or null to save none ([HourlySnapshotRun]). */
    private suspend fun snapshotLabel(now: Long): Long? =
        HourlySnapshotRun.run(
            DesktopHourlySnapshotHost(weatherDao, weatherSource, latitude, longitude, snapshotConfig(), now),
            now,
        ).labelMs

    /** The popup's quota banners: the shared [QuotaState.record] rule over the `app_logs` contract rows. */
    private suspend fun recordProductQuotas(result: RawFetch, now: Long) =
        QuotaState.record(DesktopQuotaState(weatherDao), weatherSource, result, now)

    /**
     * Persists the just-fetched [result]: the `:shared` [ForecastPersistRun] (same as Android) writes
     * the live hours, the fetch's snapshot and elapsed-hour backfill, and the daily rows; then the
     * observations (with the `BACKFILL_CLOUD` diagnostic that separates "backfill produced no cloud"
     * from "the write dropped it").
     */
    private suspend fun persistForecastResult(
        result: RawFetch,
        now: Long,
        /** Live and snapshot horizons: a LONG fetch keeps 8 days live, 72 h snapshotted. */
        keep: HourlyOnDemand.Keep = HourlyOnDemand.keep(weatherSource, null),
        /** The user's Refresh: an unchanged daily row is written anyway ([DailyForecastWrite]). */
        userRefresh: Boolean = false,
        /** False for an hourly-only fetch: daily rows a non-splitting source sent anyway are dropped. */
        daily: Boolean = true,
        /** The actuals feed whose recovery rows [result] carries, when [refresh] merged one in. */
        borrowedFeed: WeatherSource? = null,
    ): ForecastPersistRun.Result {
        recordProductQuotas(result, now)
        // Tomorrow's Timeline response also contains a revised six-hour lookback; that slice belongs
        // only in observations and never reaches the forecast tables (it is `subHourly`, not `hourly`).
        val persisted = ForecastPersistRun(
            DesktopForecastPersistHost(weatherDao, weatherSource, latitude, longitude) { snapshotLabel(it) },
        ).persist(
            ForecastPersistRun.Fetch(
                hourly = result.hourly,
                elapsedHourly = result.elapsedHourly,
                daily = result.daily.takeIf { daily },
                keep = keep.untilMs(now),
                userRefresh = userRefresh,
            ),
            now,
        )

        // NWS api actuals are NOT written here. The gridpoint maxTemperature/minTemperature
        // this used to store are the raw NDFD *forecast* grid, so every past day's "actual"
        // became that day's forecast — see plans/260808-nws-actuals-forecast-contamination.md.
        // NWS actuals now come from station observations, derived below alongside the blend.
        //
        if (result.rawObservations.isNotEmpty()) {
            // Permanent diagnostic: separates "the backfill produced no cloud" from "the write
            // dropped it". Both failed silently once, because the columns are nullable.
            Log.i(
                TAG,
                "BACKFILL_CLOUD src=$weatherSource rows=${result.rawObservations.size} " +
                    "withLow=${result.rawObservations.count { it.cloudCoverLow != null }} " +
                    "hourlyWithLow=${result.hourly.count { it.cloudCoverLow != null }} " +
                    "hourlyTotal=${result.hourly.size}",
            )
        }
        // A Tomorrow.io fetch logs its five-minute line even when it brought no rows, as on Android
        // (decision O4): its own forecast fetch with the full lookback, a borrowed recovery through the
        // current-conditions fetcher with the incremental one.
        val tomorrowIoHours = when {
            weatherSource == WeatherSource.TOMORROW_IO.id && result.hourly.isNotEmpty() -> TomorrowIoApi.FULL_ACTUALS_LOOKBACK_HOURS
            borrowedFeed == WeatherSource.TOMORROW_IO -> TomorrowIoApi.INCREMENTAL_ACTUALS_LOOKBACK_HOURS
            else -> null
        }
        if (result.rawObservations.isNotEmpty() || tomorrowIoHours != null) {
            persistObservations(result.rawObservations, now, tomorrowIoHours)
        }
        return persisted
    }

    /**
     * Post-fetch data-derivation steps: recompute daily extremes from stored observations,
     * ensure forecast-only history rows, snapshot the displayed rain chance, and run the one-time
     * chance/frozen-column backfills. NWS station actuals are a follow-up ([runFollowUps]). Returns the count of recomputed
     * extreme rows for the [refresh] pipeline-health log.
     */
    private suspend fun runPostFetchBackfills(now: Long): Int {
        // Derive actual daily highs/lows from the stored observation window — the actuals that
        // forecast-accuracy comparisons are measured against.
        val extremesCount = recomputeDailyExtremes(now)
        dailyHistoryRun.afterFetch(latitude, longitude, now)
        repairUtcDatedPredictionsIfNeeded()
        dailyHistoryRun.upkeepIfDue(latitude, longitude, now, isCharging())
        return extremesCount
    }

    /**
     * WeatherAPI's prior-day provider history. The rule is `:shared` [WeatherApiHistoryRun] (same as
     * Android); this is its desktop host. Returns the hours stored (0 unless fetched).
     */
    internal suspend fun backfillWeatherApiHistoryIfNeeded(now: Long): Int {
        if (weatherSource != WeatherSource.WEATHER_API.id) return 0
        val result = WeatherApiHistoryRun.run(
            DesktopWeatherApiHistoryHost(weatherDao, latitude, longitude, now) { date ->
                weatherService.fetchWeatherApiHistory(date).let { WeatherApiHistoryRun.History(it.hourly, it.daily.size) }
            },
            nowMs = now,
        )
        return if (result.status == WeatherApiHistoryRun.Status.FETCHED) result.storedHours else 0
    }

    /**
     * Open-Meteo's 15-minute nowcast, when [NowcastRefreshPolicy] says it is worth a request: Open-Meteo
     * displayed, native coverage (or not yet probed), the series ≥ 15 min old, and rain plausible —
     * any of [sources]' hourly chance ≥ 30 % in the next 3 h, or the series itself shows rain. Rides
     * the observation loop; adds no timer. `plans/261021-open-meteo-15-min-nowcast.md`.
     */
    suspend fun refreshNowcastIfDue(
        store: NowcastStore,
        sources: List<String>,
        reason: String,
    ): NowcastFetcher.Outcome? = withContext(Dispatchers.IO) {
        com.weatherwidget.shared.nowcast.NowcastRefreshRun.run(
            DesktopNowcastHost(
                weatherDao = weatherDao,
                lat = latitude,
                lon = longitude,
                openMeteoDisplayed = displaySource == WeatherSource.OPEN_METEO,
                usableSourceIds = sources.toSet(),
                store = store,
                fetch = { weatherService.refreshNowcast() },
            ),
            reason = reason,
            nowMs = currentTimeMillis(),
        )
    }

    /**
     * The current-temperature fetch: the shared [CurrentConditionsRun] (Android's rule; Phase 3 of
     * plans/261013-shared-current-conditions-fetch.md), then this source's own post-processing.
     *
     * @param visibleSources every visible source, best first: the charging loop's run, each feed
     *   once, low-priority feeds throttled, a 5-min gate per site. Null = the displayed source alone,
     *   forced, which every other caller (catch-up, launch, the battery loop) wants.
     */
    suspend fun refreshObservations(
        visibleSources: List<WeatherSource>? = null,
        reason: String = "obs_refresh",
    ): ForecastSnapshot = withContext(Dispatchers.IO) {
        try {
            // Recent-only: this path (the current-temp loop + launch OBSERVATIONS action) needs
            // every reading published since the previous poll, not the whole 7-day series that
            // refresh()'s full pull re-fetches anyway. A single-latest fetch here dropped the
            // intermediate readings of stations that publish faster than the poll interval. See
            // plans/260820-observation-loop-recent-window-not-latest-row.md.
            val displayedFeed = ActualsFeedPolicy.feedFor(displaySource, latitude, longitude)
            var displayedRaw: RawFetch? = null
            var displayedFailure: Exception? = null
            var stored = 0
            suspend fun store(raw: RawFetch, feed: WeatherSource?) {
                if (raw.rawObservations.isNotEmpty() || feed == WeatherSource.TOMORROW_IO) {
                    persistObservations(
                        raw.rawObservations,
                        currentTimeMillis(),
                        TomorrowIoApi.INCREMENTAL_ACTUALS_LOOKBACK_HOURS.takeIf { feed == WeatherSource.TOMORROW_IO },
                    )
                    stored += raw.rawObservations.size
                }
            }
            val run = CurrentConditionsRun(
                DesktopCurrentConditionsHost(
                    visibleSources = visibleSources ?: listOf(displaySource),
                    displayedSourceIds = setOf(displaySource.id),
                    memory = DesktopCurrentConditionsMemory.PROCESS,
                    weatherDao = weatherDao,
                    fetch = { feed ->
                        val raw = weatherService.fetchFeedObservations(feed, recentOnly = true)
                        store(raw, feed)
                        if (feed == displayedFeed) displayedRaw = raw
                        // The feed's own current reading only: an NWS cycle with nothing to blend is
                        // no reading, as on Android — never its newest raw row (user, 2026-10-24).
                        raw.providerCurrentTemp?.let {
                            CurrentConditionsRun.Reading(feed, it, raw.providerCurrentCondition, raw.providerCurrentObservedAt)
                        }
                    },
                    // The daemon's failure banner reads the displayed feed's failure, raised below.
                    recordFailure = { feed, error -> if (feed == displayedFeed) displayedFailure = error },
                ),
                nowMs = currentTimeMillis,
            )
            run.run(
                latitude, longitude,
                source = if (visibleSources == null) displaySource else null,
                reason = reason,
                force = visibleSources == null,
            ).getOrThrow()
            // Station networks are not run feeds (Android refreshes them on their own); a source that
            // borrows one still gets its rows here, as before.
            if (displayedFeed in ActualsFeedPolicy.STATION_NETWORK_FEEDS) {
                val raw = weatherService.fetchObservationsOnly(recentOnly = true)
                store(raw, displayedFeed)
                displayedRaw = raw
            }
            displayedFailure?.let { throw it }
            // A borrowed feed's own current (NWS's blend, Open-Meteo's current) is never the
            // borrower's header value.
            val result = displayedRaw
                ?.let { if (displayedFeed != displaySource) RawFetch(rawObservations = it.rawObservations) else it }
                ?: RawFetch()
            val now = currentTimeMillis()

            val extremesCount = recomputeDailyExtremes(now)
            dailyHistoryRun.afterFetch(latitude, longitude, now)
            dailyHistoryRun.upkeepIfDue(latitude, longitude, now, isCharging())
            val cached = loadCached(now)

            weatherDao.log(
                tag = "OBS_REFRESH",
                message = "source=$weatherSource obs=$stored extremes=$extremesCount",
            )
            // Once per fetch cycle (~30 min), not per status tick: enough to spot a cache that
            // stopped hitting, sparse enough not to swamp app_logs. See BlendSeriesCache.stats().
            weatherDao.log(
                tag = "BLEND_CACHE",
                message = ActualsAggregator.blendCache.stats(),
                level = "DEBUG",
            )
            weatherDao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok(displaySource.id), "INFO")

            if (cached == null) {
                return@withContext rawFetchToSnapshot(result)
            }

            // Re-resolve the temp/delta fields against the DB-derived snapshot; keep the network's
            // provider current identity when present, and the DB-derived raw observations (the
            // Phase 1 invariant: the panel and popup must see the same observation set).
            val resolvedCached = resolveForForecastResult(cached.raw.hourly, cached.raw.rawObservations, now)

            ForecastSnapshot(
                raw = cached.raw,
                resolved = ResolvedView(
                    currentTemp = resolvedCached.displayTemp,
                    currentCondition = result.providerCurrentCondition ?: cached.resolved.currentCondition,
                    currentObservedAt = result.providerCurrentObservedAt
                        .takeIf { displaySource.supportsTemperatureActuals }
                        ?: cached.resolved.currentObservedAt,
                    appliedDelta = resolvedCached.appliedDelta,
                    deltaFromYesterday = resolvedCached.deltaFromYesterday,
                ),
            )
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                weatherDao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.failure(displaySource.id, e), "WARN")
            }
            throw e
        }
    }

    /**
     * Every desktop `observations` write: the `:shared` [ObservationWriteRun], as Android. Set
     * [tomorrowIoWindowHours] when this write is a Tomorrow.io fetch (its five-minute line).
     */
    private suspend fun persistObservations(
        readings: List<ObservationReading>,
        now: Long,
        tomorrowIoWindowHours: Int? = null,
    ): ObservationWriteRun.Result =
        ObservationWriteRun(DesktopObservationWriteHost(weatherDao, latitude, longitude))
            .write(ObservationWriteRun.Write(readings, tomorrowIoWindowHours), now)

    /**
     * The stored daily blend for the last [DailyBlendRecomputeRun.MAX_DAYS_BACK] days through today:
     * the `:shared` run, as Android — one whole day at a time, unchanged days skipped. Returns rows written.
     */
    internal suspend fun recomputeDailyExtremes(now: Long): Int {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        return DailyBlendRecomputeRun.range(blendRecomputeHost(), today.minusDays(DailyBlendRecomputeRun.MAX_DAYS_BACK), today, now)
    }

    /** Forced recompute of [days] (a repair path: the gap fill just stored their observations). */
    internal suspend fun recomputeDailyExtremesForDays(days: Set<LocalDate>, now: Long): Int =
        days.sorted().sumOf { DailyBlendRecomputeRun.day(blendRecomputeHost(), it, now, force = true) }

    internal fun blendRecomputeHost() =
        DesktopDailyBlendRecomputeHost(weatherDao, latitude, longitude, personalStationWeight, weatherDao.blendSignatures)

    /**
     * Creates forecast-only `daily_history` rows ([DailyHistoryWriter.FORECAST_ONLY_ROW]) for past
     * days of the display source that have a usable forecast snapshot but no row at all. The rules
     * live in [DailyHistoryMaintenance] so Android and desktop agree exactly; `computed*` stays NULL
     * (no fabricated actuals; keeps the row out of accuracy baselines). Idempotent, so it runs after
     * every fetch.
     */
    // Daily-history maintenance: the shared run (same as Android), over this JDBC host.
    internal suspend fun ensureForecastOnlyHistoryRows(now: Long) =
        dailyHistoryRun.ensureForecastOnlyHistoryRows(latitude, longitude, now)

    internal suspend fun snapshotDisplayedRainChance(now: Long) =
        dailyHistoryRun.snapshotDisplayedRainChance(latitude, longitude, now)

    internal suspend fun backfillForecastChanceSnapshotsIfNeeded(now: Long) =
        dailyHistoryRun.backfillForecastChanceSnapshotsIfNeeded(latitude, longitude, now)

    internal suspend fun backfillFrozenDisplayColumnsIfNeeded(now: Long) =
        dailyHistoryRun.backfillFrozenDisplayColumnsIfNeeded(latitude, longitude, now)

    internal suspend fun settlePastForecastOverlays(now: Long) =
        dailyHistoryRun.settlePastForecastOverlays(latitude, longitude, now)

    private val dailyHistoryRun = DailyHistoryRun(DesktopDailyHistoryHost(weatherDao))

    /**
     * One-time scrub of app_logs rows that still embed API credentials (Ktor exception text
     * carries the full request URL). Gated by [APP_LOGS_KEY_SCRUB_DONE_TAG]; idempotent, so even a
     * missed gate would only cost a cheap scan. See performance/261003-synoptic-fetch-cost-and-resilience.md.
     */
    internal fun scrubApiKeysFromAppLogsIfNeeded() {
        if (weatherDao.getRecentLogsByTags(listOf(APP_LOGS_KEY_SCRUB_DONE_TAG), limit = 1).isNotEmpty()) return
        val scrubbed = weatherDao.scrubCredentialsFromAppLogs()
        weatherDao.log(APP_LOGS_KEY_SCRUB_DONE_TAG, "scrubbed=$scrubbed", "INFO")
    }

    /**
     * One-time backfill of the frozen display columns (forecastHighTemp/LowTemp/PrecipAmountMm +
     * noonCloudPercent, see [com.weatherwidget.shared.util.DailyHistoryFreeze]) for daily_history
     * rows from before the feature existed, while their source tables are still retained:
     *
     *  - Overlay: the most recent complete non-degenerate snapshot batch for that (date, source) —
     *    exactly what the past-day reader selects today. Past target dates are never re-fetched,
     *    so every stored batch predates the day's end and the freeze-window rule is satisfied by
     *    construction.
     *  - Noon cloud: the as-predicted hourly_forecast_history archive ([DesktopWeatherDao.getHourlyHistory]
     *    already returns the freshest snapshot per hour), same pipeline as the chance backfill.
     *
     * Best-effort: unfillable days keep nulls (no regression). Gated by a one-time app_logs marker
     * like the chance backfill.
     */
    /**
     * Once a day, after the one-shot backfills that read every snapshot have run
     * (performance/260929-hourly-history-snapshot-retention.md). Best-effort: never fails a refresh.
     */
    internal fun pruneHistorySnapshotsIfDue(now: Long) {
        try {
            val last = weatherDao.getRecentLogsByTags(listOf(HISTORY_PRUNE_TAG), limit = 1).firstOrNull()?.timestamp ?: 0L
            val backfillsDone = listOf(CHANCE_BACKFILL_DONE_TAG, FROZEN_DISPLAY_BACKFILL_DONE_TAG)
                .all { weatherDao.getRecentLogsByTags(listOf(it), limit = 1).isNotEmpty() }
            when (HistoryPruneSchedule.decide(now, last, backfillsDone)) {
                HistoryPruneSchedule.Decision.NOT_DUE -> return
                HistoryPruneSchedule.Decision.PREREQUISITES_PENDING -> {
                    weatherDao.log(HISTORY_PRUNE_TAG, HistoryPruneSchedule.SKIPPED_PREREQUISITES, "INFO")
                    return
                }
                HistoryPruneSchedule.Decision.RUN -> Unit
            }
            // Due, but only on AC with the screen off; the next refresh asks again (no log row, so
            // the due check above stays keyed on the last real pass).
            if (!housekeepingAllowed()) return
            weatherDao.pruneHourlyHistorySnapshots()
            // DELETE leaves the file its size; the first prune freed ~100 MB on this machine
            // (183 MB -> 82 MB, VACUUM 0.16 s on a copy). Only when there is real space to win.
            val freeBytes = weatherDao.freelistBytes()
            if (freeBytes >= HistoryPruneSchedule.VACUUM_MIN_FREELIST_BYTES) {
                val startNs = System.nanoTime()
                try {
                    weatherDao.vacuum()
                    weatherDao.log(
                        HISTORY_PRUNE_TAG,
                        "vacuum=done freedBytes=$freeBytes ms=${(System.nanoTime() - startNs) / 1_000_000}",
                    )
                } catch (e: Exception) {
                    weatherDao.log(HISTORY_PRUNE_TAG, "vacuum=skipped reason=${e.javaClass.simpleName} freeBytes=$freeBytes")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "history prune failed: ${e.message}")
        }
    }

    /**
     * Once per install: `forecasts.dateOfPrediction` written while the DAO used the UTC date
     * ([DesktopWeatherDao.repairUtcDatedPredictions]). Table-wide, every site and source. Runs
     * after this build's writer has stored at least one batch, so no old-writer row follows it
     * (plans/261005-desktop-forecast-today-is-utc-date-after-5pm.md).
     */
    internal fun repairUtcDatedPredictionsIfNeeded() {
        if (weatherDao.getRecentLogsByTags(listOf(UTC_PREDICTION_DATE_REPAIR_DONE_TAG), limit = 1).isNotEmpty()) return
        val repaired = weatherDao.repairUtcDatedPredictions()
        weatherDao.log(UTC_PREDICTION_DATE_REPAIR_DONE_TAG, "repaired=$repaired zone=${ZoneId.systemDefault()}")
    }

    /** The display source's daily actuals, plus every source's history depth from the same read. */
    private class DailyActualsLoad(
        val actuals: Map<String, DailyHistory>,
        val historyDepths: List<com.weatherwidget.shared.months.SourceDepth>,
    )

    private fun loadDailyActuals(
        daily: List<DailyForecast>,
        hourly: List<HourlyForecast>,
        observations: List<ObservationReading>,
        now: Long,
    ): DailyActualsLoad {
        if (daily.isEmpty() && observations.isEmpty()) return DailyActualsLoad(emptyMap(), emptyList())
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // History reaches back ACTUALS_HISTORY_DAYS for the zoom-out; rows from today on are
        // dropped by the assembler (today is live only).
        val pastRows = if (daily.isEmpty()) {
            emptyList()
        } else {
            val start = daily.minOf { LocalDate.parse(it.date) }.minusDays(ACTUALS_HISTORY_DAYS)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            weatherDao.getExtremesInRange(start, end, latitude, longitude)
        }
        val yesterdayMs = today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Composition is shared with Android's DailyActualsStore (DailyActualsAssembler), including
        // the late-start today-low gate this copy used to lack.
        val result = DailyActualsAssembler.assemble(
            activeSources = listOf(displaySource),
            pastRows = pastRows,
            yesterdayDonors = weatherDao.getMeasuredExtremesForDateAnySite(yesterdayMs, weatherSource),
            observations = observations,
            hourlyForecasts = hourly,
            latitude = latitude,
            longitude = longitude,
            today = today,
            zone = zone,
            nowMs = now,
            personalStationWeight = personalStationWeight,
        )
        DailyActualsAssembler.suppressedLowLines(result, latitude, longitude, zone).forEach { (tag, message, level) ->
            weatherDao.log(tag, message, level)
        }
        return DailyActualsLoad(
            actuals = result.bySource[displaySource.id].orEmpty().mapKeys { it.key.toString() },
            // pastRows holds every source in the site box: the months view's "another source has
            // more history" line (MonthsSourceDepth) needs no read of its own.
            historyDepths = com.weatherwidget.shared.months.MonthsSourceDepth.depths(pastRows, today),
        )
    }

    private fun loadDailySnapshots(daily: List<DailyForecast>): Map<String, List<DailyForecastSnapshot>> {
        if (daily.isEmpty()) return emptyMap()
        val dates = daily.map { LocalDate.parse(it.date) }
        val start = dates.min().minusDays(14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = dates.max().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return weatherDao.getDailyForecastSnapshots(start, end, latitude, longitude, weatherSource)
    }

    /**
     * Best-effort climate-normals fetch+cache (the future-day fallback). Skips the network if this
     * location is already cached, so it's cheap to call unconditionally on every launch/resume —
     * it must NOT be gated behind forecast staleness or a fresh-forecast launch would never populate
     * normals. Compute is shared with Android via [ClimateNormals].
     */
    /**
     * The rule is `:shared` [ClimateNormalsRun] (same as Android); this is its desktop host.
     * @return true only if normals were just (re)fetched and cached, so callers can reload state.
     */
    suspend fun ensureClimateNormals(): Boolean = ClimateNormalsRun.warm(
        DesktopClimateNormalsHost(weatherDao, weatherService::fetchHistoricalDailyTemps),
        latitude,
        longitude,
    ).fetched

    /**
     * Appends climate-normal gap rows (isClimateNormal=true) for future dates not already covered by
     * a real forecast, out to [GAP_HORIZON_DAYS] — only where the shared [DailyColumnSource] rule
     * allows climate filler (after today+2), as on Android. Today, +1 and +2 without a real row render
     * missing until the fetch lands, rather than as climate averages. Read-only (cached normals); no network. The daily
     * model already renders such rows as a green fallback bar, so no model/graph change is needed.
     */
    /** The site's cached normals by calendar day (months view band); empty until cached. Read-only. */
    private fun cachedNormalsByMonthDay(): Map<java.time.MonthDay, Pair<Float, Float>> {
        val (monthlyHigh, monthlyLow) = weatherDao.getClimateNormals(ClimateNormals.locationKey(latitude, longitude))
        return ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)
    }

    private fun appendClimateNormalGaps(daily: List<DailyForecast>, now: Long): List<DailyForecast> {
        val (monthlyHigh, monthlyLow) = weatherDao.getClimateNormals(ClimateNormals.locationKey(latitude, longitude))
        if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) return daily

        val normals = ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val filled = PartialForecastDays.fillPartialFutureDays(daily, weatherSource, normals, today)
        val existing = filled.map { LocalDate.parse(it.date) }.toSet()
        val gaps = ClimateNormals.fillGaps(existing, normals, today, GAP_HORIZON_DAYS)
            .filter { DailyColumnSource.allowsClimateNormal(it.date, today) }
            .map { gap ->
                DailyForecast(
                    date = gap.date.toString(),
                    highTemp = gap.highTemp,
                    lowTemp = gap.lowTemp,
                    condition = "Historical Avg",
                    isClimateNormal = true,
                )
            }
        return if (gaps.isEmpty()) filled else filled + gaps
    }

    companion object {
        private const val TAG = "DesktopWeatherRepository"
        /** [refreshObservationWindow]: NWS never answered — worth a retry. */
        const val OBS_WINDOW_UNREACHABLE = -1

        /** [refreshObservationWindow]: failed for another reason — not retried. */
        const val OBS_WINDOW_FAILED = -2

        private const val ACTUALS_HISTORY_DAYS = 547L
        private const val FRESH_OBSERVATION_MS = 30 * 60 * 1000L
        private const val GAP_HORIZON_DAYS = 16L
        // The launch backfill / normal refresh always cover ~7 days back, so on-demand history starts
        // from here and only deepens. Capped at the 18-month DB retention (cleanup deletes past that).
        private const val BASELINE_HISTORY_DAYS = 7
        private const val MAX_HISTORY_DAYS = 547
        private const val CHANCE_BACKFILL_DONE_TAG = DesktopDailyHistoryHost.CHANCE_BACKFILL_DONE_TAG
        private const val FROZEN_DISPLAY_BACKFILL_DONE_TAG = DesktopDailyHistoryHost.FROZEN_DISPLAY_BACKFILL_DONE_TAG
        private const val UTC_PREDICTION_DATE_REPAIR_DONE_TAG = "UTC_PREDICTION_DATE_REPAIR_DONE"
        private const val HISTORY_PRUNE_TAG = HistoryPruneSchedule.LOG_TAG

        /** app_logs rows used as permanent "already done" state; exempt from the 72 h log window. */
        private val PERMANENT_LOG_MARKERS =
            listOf(
                CHANCE_BACKFILL_DONE_TAG,
                FROZEN_DISPLAY_BACKFILL_DONE_TAG,
                APP_LOGS_KEY_SCRUB_DONE_TAG,
                UTC_PREDICTION_DATE_REPAIR_DONE_TAG,
            )
        private const val APP_LOGS_KEY_SCRUB_DONE_TAG = "APP_LOGS_KEY_SCRUB_DONE"
    }

    /**
     * NWS apiHighTemp/apiLowTemp from the dedicated `/stations/{id}/observations` pull. The rule is
     * `:shared` [NwsStationActualsRun] (same as Android); this is its desktop host.
     */
    internal suspend fun fillNwsStationActualsIfNeeded(now: Long) = NwsStationActualsRun.run(
        DesktopNwsStationActualsHost(
            weatherDao = weatherDao,
            lat = latitude,
            lon = longitude,
            personalStationWeight = personalStationWeight,
            stations = { weatherService.nearestStationsForDailyActuals() },
            // The service answers null for a failed request (it logs the cause); the run needs it thrown.
            stationDay = { station, startIso, endIso ->
                weatherService.fetchApiObservationDay(station, startIso, endIso)
                    ?: throw java.io.IOException("station day request failed")
            },
        ),
        nowMs = now,
    )

}
