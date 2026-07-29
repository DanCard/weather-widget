package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.*
import com.weatherwidget.data.model.*
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.history.ProviderHistoryDecision
import com.weatherwidget.shared.history.ProviderHistoryFailureClass
import com.weatherwidget.shared.history.ProviderHistoryPolicy
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.util.SpatialInterpolator
import com.weatherwidget.widget.CurrentTemperatureResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class DesktopWeatherRepository(
    private val weatherService: DesktopWeatherService,
    private val weatherDao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
    private val weatherSource: String,
    private val personalStationWeight: Double = 1.0,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private fun resolveForForecastResult(
        hourly: List<HourlyForecast>,
        observations: List<com.weatherwidget.data.model.ObservationReading>,
        now: Long,
        resultLogLevel: String = "DEBUG",
    ): Pair<Float?, Float?> {
        val displaySource = WeatherSource.fromDisplaySource(weatherSource)
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        
        val window = CurrentTemperatureResolver.buildCurrentTempResolutionWindow(nowLocal)
        val zoneId = ZoneId.systemDefault()
        val minEpoch = window.start.atZone(zoneId).toInstant().toEpochMilli()
        val maxEpoch = window.end.atZone(zoneId).toInstant().toEpochMilli()

        val narrowObs = observations.filter { it.timestamp in minEpoch..maxEpoch }
        val narrowHourly = hourly.filter { it.dateTime in minEpoch..maxEpoch }

        val resolvedObs = ActualsAggregator.resolveCurrentObservation(
            observations = narrowObs,
            hourlyForecasts = narrowHourly,
            displaySourceId = displaySource.id,
            userLat = latitude,
            userLon = longitude,
            nowMs = now,
            lookbackHours = 12L,
            lookaheadHours = 3L,
            personalStationWeight = personalStationWeight,
        )

        val lastObservedTemp = resolvedObs?.first
        val observedAt = resolvedObs?.second

        val smoothedForecasts = CurrentTemperatureResolver.computeSmoothedForecasts(
            narrowHourly, displaySource.id
        )

        val resolution = CurrentTemperatureResolver.resolve(
            now = nowLocal,
            displaySource = displaySource,
            hourlyForecasts = narrowHourly,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            storedDeltaState = null,
            currentLat = latitude,
            currentLon = longitude,
            smoothedForecasts = smoothedForecasts,
            resultLogLevel = resultLogLevel,
        )
        return resolution.displayTemp to resolution.appliedDelta
    }

    fun resolveCurrentTempInMemory(forecast: ForecastResult, now: Long): Pair<Float?, Float?> {
        // High-frequency path: runs on every genmon panel connect and every UI minute tick.
        // VERBOSE keeps the CURR_TEMP_RESULT row out of app_logs (the sparse fetch-cycle
        // resolves in loadCached/refreshObservations stay DEBUG and remain queryable).
        return resolveForForecastResult(forecast.hourly, forecast.rawObservations, now, resultLogLevel = "VERBOSE")
    }

    suspend fun loadCached(now: Long = currentTimeMillis()): ForecastResult? = withContext(Dispatchers.IO) {
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours for cache
        // Cover the widest zoom-out (6 days back) so the continuous-zoom graph never truncates history.
        val stitchedStart = now - (DesktopGraphUtils.MAX_BACK_HOURS * 3600 * 1000L)
        val hourly = weatherDao.getHourlyWithHistory(latitude, longitude, weatherSource, stitchedStart, now + (168 * 3600 * 1000L), maxAgeMs)
        val daily = weatherDao.getDailyForecasts(latitude, longitude, weatherSource)
        
        // Cover the widest zoom-out (6 days back) so the actual line spans the whole window,
        // matching the hourly read above. Observations exist ~7-14 days back (NWS HISTORY_DAYS=7,
        // 18-month retention); only the read window was capping the actual line at 2 days.
        val obsStart = now - (DesktopGraphUtils.MAX_BACK_HOURS * 3600 * 1000L)
        val obsEnd = now + (2 * 3600 * 1000L) // Include some cushion
        val observations = weatherDao.getObservationsInRange(obsStart, obsEnd, latitude, longitude)
            .map { it.toReading() }

        // The range query returns every API's observations; the displayed current condition /
        // "observed at" must come ONLY from the displayed source (NWS_BLEND has api=NWS, so it's
        // correctly included for NWS and excluded for Open-Meteo/Silurian). Without this filter a
        // non-NWS view would show an NWS blend timestamp/condition.
        val displaySource = WeatherSource.fromDisplaySource(weatherSource)
        val sourceObs = observations.filter { it.api == displaySource.id }

        // Prefer the most-recent NWS_BLEND synthetic row — it represents the IDW-weighted truth
        // across all stations. Raw station rows can have newer timestamps (from historical fetches)
        // but those are single-station readings, not the calibrated blend.
        val newestObs = sourceObs.filter { it.stationId == "NWS_BLEND" }.maxByOrNull { it.timestamp }
            ?: sourceObs.maxByOrNull { it.timestamp }

        // Freshness gate only governs whether the *current condition* is shown as observed vs forecast.
        val latestObs = newestObs?.takeIf { now - it.timestamp < FRESH_OBSERVATION_MS }

        val (currentTemp, appliedDelta) = resolveForForecastResult(hourly, observations, now)
        val actuals = loadDailyActuals(daily)
        val snapshots = loadDailySnapshots(daily)

        if (hourly.isEmpty() && daily.isEmpty()) {
            return@withContext null
        }

        ForecastResult(
            currentTemp = currentTemp,
            currentCondition = latestObs?.condition ?: hourly.firstOrNull()?.condition,
            currentObservedAt = newestObs?.timestamp,
            appliedDelta = appliedDelta,
            daily = appendClimateNormalGaps(daily, now),
            hourly = hourly,
            dailyActuals = actuals,
            dailySnapshots = snapshots,
            rawObservations = observations,
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
            WeatherSource.NWS.id ->
                neededHistoryDays(neededBackHours) > deepestHistoryDaysFetched
            WeatherSource.WEATHER_API.id ->
                neededBackHours >= 24 && weatherApiHistoryDecision(currentTimeMillis()) is
                    ProviderHistoryDecision.Fetch
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
        if (weatherSource != WeatherSource.NWS.id) return@withContext false
        val neededDays = neededHistoryDays(neededBackHours)
        if (neededDays <= deepestHistoryDaysFetched) return@withContext false
        historyFetchMutex.withLock {
            // Re-check under the lock: a concurrent call may have already deepened coverage.
            if (neededDays <= deepestHistoryDaysFetched) return@withLock false
            var fetchedAny = false
            try {
                val obs = weatherService.fetchObservationHistory(neededDays.toLong())
                if (obs.isNotEmpty()) {
                    weatherDao.upsertObservations(obs.map { it.toEntity(currentTimeMillis()) })
                    fetchedAny = true
                }
            } catch (e: Exception) {
                Log.e("DesktopWeatherRepository", "On-demand NWS observation history fetch failed: $e")
            }
            if (fetchedAny) {
                deepestHistoryDaysFetched = neededDays
                Log.i("DesktopWeatherRepository", "ensureHistory deepened to ${neededDays}d back (source=$weatherSource)")
            }
            fetchedAny
        }
    }

    suspend fun refresh(
        now: Long = currentTimeMillis(),
    ): ForecastResult = withContext(Dispatchers.IO) {
        val displaySource = WeatherSource.fromDisplaySource(weatherSource)
        try {
            // NOTE: no Open-Meteo history backfill here. GENERIC_GAP is future-only; past forecast history
            // must come only from real accumulated NWS snapshots (a fresh install simply starts sparse and
            // fills in as it runs), so we never seed Open-Meteo decimals into the past.
            val result = weatherService.fetchForecast()

            // Persist
            weatherDao.upsertHourlyForecasts(latitude, longitude, weatherSource, result.hourly)
            weatherDao.upsertForecasts(latitude, longitude, weatherSource, result.daily)

            if (result.rawObservations.isNotEmpty()) {
                weatherDao.upsertObservations(result.rawObservations.map { it.toEntity(now) })
            }
            val historyObsCount = backfillWeatherApiHistoryIfNeeded(now)

            // Derive actual daily highs/lows from the stored observation window — the actuals that
            // forecast-accuracy comparisons are measured against.
            val extremesCount = recomputeDailyExtremes(now)
            snapshotDisplayedRainChance(now)
            backfillForecastChanceSnapshotsIfNeeded(now)
            backfillFrozenDisplayColumnsIfNeeded(now)

            // Snapshot for history (Tier 1 simplification: 4h buckets)
            val timestampToGroupPredictions = (now / (4 * 3600 * 1000L)) * (4 * 3600 * 1000L)
            weatherDao.upsertHourlyForecastHistory(latitude, longitude, weatherSource, timestampToGroupPredictions, result.hourly)

            // Best-effort: ensure climate normals are cached for the future-day fallback. One network
            // fetch per location, then served from cache; never fails the main refresh.
            ensureClimateNormals()

            // Cleanup old data (> 18 months / 547 days)
            weatherDao.cleanup(now - (DB_RETENTION_DAYS * 24 * 3600 * 1000L))

            // Persistent pipeline-health summary
            weatherDao.log(
                tag = "REFRESH",
                message = "source=$weatherSource hourly=${result.hourly.size} daily=${result.daily.size} " +
                    "obs=${result.rawObservations.size} historyObs=$historyObsCount extremes=$extremesCount",
            )
            weatherDao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok(displaySource.id), "INFO")

            loadCached(now) ?: result
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                weatherDao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.failure(displaySource.id, e), "WARN")
            }
            throw e
        }
    }

    private fun weatherApiHistoryDecision(now: Long): ProviderHistoryDecision {
        val zoneId = ZoneId.systemDefault()
        val targetDate = ProviderHistoryPolicy.targetDate(now, zoneId)
        val startMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val timestamps =
            weatherDao.getObservationsInRange(startMs, endMs, latitude, longitude)
                .asSequence()
                .filter {
                    it.api == WeatherSource.WEATHER_API.id &&
                        LocationMatch.sameSite(
                            it.locationLat,
                            it.locationLon,
                            latitude,
                            longitude,
                        )
                }
                .map { it.timestamp }
                .toList()
        val prefix =
            "site=${ProviderHistoryPolicy.siteKey(latitude, longitude)} date=$targetDate"
        val retryAtMs =
            weatherDao.getLatestLogByTagAndMessagePrefix(
                WAPI_HISTORY_RESULT_TAG,
                prefix,
            )?.message
                ?.let(ProviderHistoryPolicy::retryAtFromMessage)
        return ProviderHistoryPolicy.decide(
            source = WeatherSource.WEATHER_API,
            nowMs = now,
            zoneId = zoneId,
            storedTimestamps = timestamps,
            retryAtMs = retryAtMs,
        )
    }

    internal suspend fun backfillWeatherApiHistoryIfNeeded(now: Long): Int {
        if (weatherSource != WeatherSource.WEATHER_API.id) return 0
        val zoneId = ZoneId.systemDefault()
        val decision = weatherApiHistoryDecision(now)
        when (decision) {
            is ProviderHistoryDecision.AlreadyCovered -> {
                Log.v(
                    TAG,
                    "WeatherAPI history covered date=${decision.date} hours=${decision.distinctHours}",
                )
                return 0
            }
            is ProviderHistoryDecision.Cooldown -> {
                Log.v(
                    TAG,
                    "WeatherAPI history cooldown date=${decision.date} " +
                        "retryAtMs=${decision.retryAtMs}",
                )
                return 0
            }
            is ProviderHistoryDecision.NotApplicable -> return 0
            is ProviderHistoryDecision.Fetch -> {
                val prefix =
                    "site=${ProviderHistoryPolicy.siteKey(latitude, longitude)} " +
                        "date=${decision.date}"
                weatherDao.log(
                    WAPI_HISTORY_CHECK_TAG,
                    "$prefix coverage=${decision.distinctHours} decision=fetch",
                    "DEBUG",
                )
                return try {
                    val history = weatherService.fetchWeatherApiHistory(decision.date)
                    val targetReadings =
                        history.rawObservations.filter {
                            it.api == WeatherSource.WEATHER_API.id &&
                                Instant.ofEpochMilli(it.timestamp)
                                    .atZone(zoneId)
                                    .toLocalDate() == decision.date
                        }
                    if (targetReadings.isEmpty()) {
                        recordWeatherApiHistoryFailure(
                            prefix,
                            now,
                            ProviderHistoryFailureClass.MALFORMED_RESPONSE,
                            "empty_target_day",
                        )
                        return 0
                    }
                    weatherDao.upsertObservations(targetReadings.map { it.toEntity(now) })
                    val distinctHours =
                        targetReadings.asSequence()
                            .map { it.timestamp / ProviderHistoryPolicy.HOUR_MS }
                            .distinct()
                            .count()
                    val partialRetryAt =
                        if (
                            distinctHours <
                            ProviderHistoryPolicy.COMPLETE_DAY_MIN_DISTINCT_HOURS
                        ) {
                            now + ProviderHistoryPolicy.retryDelayMs(
                                ProviderHistoryFailureClass.MALFORMED_RESPONSE,
                            )
                        } else {
                            null
                        }
                    weatherDao.log(
                        WAPI_HISTORY_RESULT_TAG,
                        buildString {
                            append("$prefix result=stored hours=$distinctHours")
                            if (partialRetryAt != null) append(" retryAtMs=$partialRetryAt")
                        },
                        "INFO",
                    )
                    distinctHours
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    val statusCode = (exception as? ApiAccessException)?.statusCode
                    recordWeatherApiHistoryFailure(
                        prefix = prefix,
                        now = now,
                        failureClass =
                            ProviderHistoryPolicy.failureClassForStatus(statusCode),
                        detail = statusCode?.let { "http_$it" }
                            ?: exception.javaClass.simpleName.ifBlank { "error" },
                    )
                    0
                }
            }
        }
    }

    private fun recordWeatherApiHistoryFailure(
        prefix: String,
        now: Long,
        failureClass: ProviderHistoryFailureClass,
        detail: String,
    ) {
        val retryAtMs = now + ProviderHistoryPolicy.retryDelayMs(failureClass)
        weatherDao.log(
            WAPI_HISTORY_RESULT_TAG,
            "$prefix result=cooldown failure=${failureClass.name.lowercase()} " +
                "detail=$detail retryAtMs=$retryAtMs",
            "WARN",
        )
    }

    suspend fun refreshObservations(): ForecastResult = withContext(Dispatchers.IO) {
        val displaySource = WeatherSource.fromDisplaySource(weatherSource)
        try {
            val result = weatherService.fetchObservationsOnly()
            val now = currentTimeMillis()

            if (result.rawObservations.isNotEmpty()) {
                weatherDao.upsertObservations(result.rawObservations.map { it.toEntity(now) })
            }

            val extremesCount = recomputeDailyExtremes(now)
            snapshotDisplayedRainChance(now)
            backfillForecastChanceSnapshotsIfNeeded(now)
            backfillFrozenDisplayColumnsIfNeeded(now)
            val cached = loadCached(now)

            weatherDao.log(
                tag = "OBS_REFRESH",
                message = "source=$weatherSource obs=${result.rawObservations.size} extremes=$extremesCount",
            )
            weatherDao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok(displaySource.id), "INFO")

            val cachedHourly = cached?.hourly ?: emptyList()
            val cachedObs = cached?.rawObservations ?: emptyList()
            val (currentTemp, appliedDelta) = resolveForForecastResult(cachedHourly, cachedObs, now)

            result.copy(
                currentTemp = currentTemp,
                appliedDelta = appliedDelta,
                currentCondition = result.currentCondition ?: cached?.currentCondition,
                currentObservedAt = result.currentObservedAt ?: cached?.currentObservedAt,
                daily = cached?.daily ?: emptyList(),
                hourly = cachedHourly,
                dailyActuals = cached?.dailyActuals ?: emptyMap(),
                dailySnapshots = cached?.dailySnapshots ?: emptyMap(),
                rawObservations = if (result.rawObservations.isNotEmpty()) result.rawObservations else cached?.rawObservations ?: emptyList(),
            )
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                weatherDao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.failure(displaySource.id, e), "WARN")
            }
            throw e
        }
    }

    /** Reads the stored observation window, (re)computes daily_history, and returns the row count. */
    internal fun recomputeDailyExtremes(now: Long): Int {
        val windowStart = now - (HISTORY_WINDOW_DAYS + 1) * 86_400_000L
        val windowEnd = now + 86_400_000L
        val observations = weatherDao.getObservationsInRange(windowStart, windowEnd, latitude, longitude)
        val hourly = weatherDao.getHourlyWithHistory(latitude, longitude, weatherSource, now - (72 * 3600 * 1000L), now + 86_400_000L, 48 * 3600 * 1000L)

        val extremes = ActualsAggregator.aggregate(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourly,
            locationLat = latitude,
            locationLon = longitude,
            updatedAtMs = now,
            personalStationWeight = personalStationWeight,
        )

        // extremes are rebuilt from scratch each recompute and never populate the forecast chance
        // snapshot columns or the frozen display columns (written separately by
        // snapshotDisplayedRainChance) — carry over any existing values so this write doesn't
        // clobber them (full-row REPLACE in upsertDailyHistory).
        val existingBySource = weatherDao.getExtremesInRange(windowStart, windowEnd, latitude, longitude)
            .groupBy { it.date to it.source }
        val merged = extremes.map { new ->
            val existing = existingBySource[new.date to new.source]?.firstOrNull()
            if (existing == null) {
                new
            } else {
                new.copy(
                    forecastDayPrecipChance = existing.forecastDayPrecipChance,
                    forecastNightPrecipChance = existing.forecastNightPrecipChance,
                    forecastHighTemp = existing.forecastHighTemp,
                    forecastLowTemp = existing.forecastLowTemp,
                    forecastPrecipAmountMm = existing.forecastPrecipAmountMm,
                    noonCloudPercent = existing.noonCloudPercent,
                )
            }
        }

        weatherDao.upsertDailyHistory(merged)
        return merged.size
    }

    /**
     * Snapshots the resolved (as-displayed) day/night forecast rain chance into daily_history for
     * yesterday and today, so that once a day rolls into history its rain label can replay what was
     * actually shown instead of falling back to NWS's raw 6am/6pm period fields (see
     * DailyRainLabels.resolveLiveDayNightChance / resolveDailyLabelPrecip). Call after every
     * successful fetch.
     *
     * Each of a date's two windows (day: 8am-8pm, night: 8pm-8am next day) is only (re)written while
     * still open. The live hourly_forecasts table is REPLACE'd on every fetch, so a PAST hour's row
     * reflects the latest re-forecast for that hour, not what was actually shown at the time
     * ("hindcast drift" — same reason the hourly graph reads hourly_forecast_history instead for its
     * past segment). Recomputing a closed window from that drifted data would silently overwrite the
     * correctly-archived snapshot with a different, wrong value days later. Once closed, a window's
     * stored value is left untouched forever.
     *
     * Only updates daily_history rows that already exist (written by recomputeDailyExtremes) — a
     * chance with nothing to attach to yet is caught by the next fetch cycle.
     *
     * Also freezes the forecast-overlay values (forecastHighTemp/LowTemp/PrecipAmountMm) and the
     * measured noon cloud % under [com.weatherwidget.shared.util.DailyHistoryFreeze] windows, so
     * the daily bar view can render past days from daily_history alone. Because this runs after
     * every fetch and the merge only accepts complete batches, the surviving overlay equals "most
     * recent complete forecast of the day" — what the snapshot-table reader would have selected.
     */
    internal fun snapshotDisplayedRainChance(now: Long) {
        val zoneId = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val yesterday = today.minusDays(1)
        val startMs = yesterday.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

        val dailyRows = weatherDao.getDailyForecasts(latitude, longitude, weatherSource)
            .filter { it.date == yesterday.toString() || it.date == today.toString() }
        if (dailyRows.isEmpty()) return
        // Today's night window runs 8pm today -> 8am TOMORROW, so the hourly range must extend a
        // full day past `today`, not stop at tomorrow's midnight.
        val hourlyRows = weatherDao.getHourlyForecasts(
            latitude, longitude, weatherSource,
            yesterday.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            today.plusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )
        val existingByDateSource = weatherDao.getExtremesInRange(startMs, endMs, latitude, longitude)
            .groupBy { it.date to it.source }

        val toUpsert = mutableListOf<DailyHistory>()
        listOf(yesterday, today).forEach { date ->
            val dayWindowOpen = now < date.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli()
            val nightWindowOpen = now < date.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
            // The night window is the last to close (next-day 8am, same as the noon-cloud window;
            // the overlay window closes earlier at midnight), so this early-exit covers every
            // freeze window too.
            if (!dayWindowOpen && !nightWindowOpen) return@forEach
            val overlayOpen = com.weatherwidget.shared.util.DailyHistoryFreeze.overlayWindowOpen(now, date, zoneId)
            val noonCloudOpen = com.weatherwidget.shared.util.DailyHistoryFreeze.noonCloudWindowOpen(now, date, zoneId)

            val dateMs = date.toEpochDay() * 86_400_000L
            dailyRows.filter { it.date == date.toString() }.forEach { row ->
                val fragments = existingByDateSource[dateMs to weatherSource].orEmpty()
                if (fragments.isEmpty()) return@forEach
                // ...AtSite: hourlyRows are RAW proximity-box rows (jitter fragments included), and
                // the window max is a `max` — one poisoned fragment wins outright. See the Android
                // twin of this call and DailyRainLabels.resolveLiveDayNightChanceAtSite.
                val resolved = com.weatherwidget.shared.util.DailyRainLabels.resolveLiveDayNightChanceAtSite(
                    displaySourceId = weatherSource,
                    daytimePrecipProbability = row.daytimePrecipProbability,
                    nighttimePrecipProbability = row.nighttimePrecipProbability,
                    precipProbability = row.precipProbability,
                    hourly = hourlyRows,
                    centerLat = latitude,
                    centerLon = longitude,
                    targetDate = date,
                    zoneId = zoneId,
                )
                // Overlay freeze candidate: only a real, non-degenerate forecast row
                // (climate-normal filler and collapsed high==low rows must never masquerade as
                // the day's displayed forecast). highTemp/lowTemp are non-nullable on desktop.
                val overlayRow = row.takeIf { !it.isClimateNormal && it.highTemp != it.lowTemp }
                val resolvedNoonCloud = com.weatherwidget.shared.util.DailyNoonCloudCover
                    .resolveMeasuredNoonCloudCoverPercent(
                        hourly = hourlyRows,
                        date = date,
                        displaySourceId = weatherSource,
                    )
                fragments.forEach { existing ->
                    val newDay = if (dayWindowOpen) resolved.dayPrecip else existing.forecastDayPrecipChance
                    val newNight = if (nightWindowOpen) resolved.nightPrecip else existing.forecastNightPrecipChance
                    val frozen = com.weatherwidget.shared.util.DailyHistoryFreeze.merge(
                        overlayOpen = overlayOpen,
                        noonCloudOpen = noonCloudOpen,
                        resolvedHigh = overlayRow?.highTemp,
                        resolvedLow = overlayRow?.lowTemp,
                        resolvedPrecipAmountMm = overlayRow?.precipAmountMm,
                        resolvedNoonCloudPercent = resolvedNoonCloud,
                        existing = com.weatherwidget.shared.util.DailyHistoryFreeze.FrozenDisplay(
                            forecastHighTemp = existing.forecastHighTemp,
                            forecastLowTemp = existing.forecastLowTemp,
                            forecastPrecipAmountMm = existing.forecastPrecipAmountMm,
                            noonCloudPercent = existing.noonCloudPercent,
                        ),
                    )
                    val updated = existing.copy(
                        forecastDayPrecipChance = newDay,
                        forecastNightPrecipChance = newNight,
                        forecastHighTemp = frozen.forecastHighTemp,
                        forecastLowTemp = frozen.forecastLowTemp,
                        forecastPrecipAmountMm = frozen.forecastPrecipAmountMm,
                        noonCloudPercent = frozen.noonCloudPercent,
                    )
                    // Persist the rain-chance transition (Log.d persists; Log.v would not). The frozen
                    // day/night chance is captured from the live hourly-window max while the window is
                    // open, so this desktop install and the Android one can freeze different values if a
                    // provider revises the chance between their independent fetches (e.g. NWS 14%->15%).
                    // Logging the resolved input + before->after + timestamp lets us reconstruct which
                    // value each install captured and when, if the two databases ever diverge.
                    if (newDay != existing.forecastDayPrecipChance || newNight != existing.forecastNightPrecipChance) {
                        Log.d(
                            "DesktopWeatherRepository",
                            "freezeRainChance: date=$date src=$weatherSource dayWin=$dayWindowOpen nightWin=$nightWindowOpen" +
                                " resolvedDay=${resolved.dayPrecip} resolvedNight=${resolved.nightPrecip}" +
                                " day=${existing.forecastDayPrecipChance}->$newDay" +
                                " night=${existing.forecastNightPrecipChance}->$newNight",
                        )
                    }
                    if (updated != existing) toUpsert.add(updated)
                }
            }
        }
        if (toUpsert.isNotEmpty()) weatherDao.upsertDailyHistory(toUpsert)
    }

    /**
     * One-time backfill: fills the forecast chance snapshot columns for daily_history rows from
     * before this feature existed, using the as-predicted hourly_forecast_history archive (never the
     * live, REPLACE-overwritten hourly_forecasts table) — [DesktopWeatherDao.getHourlyHistory] already
     * returns the freshest snapshot per hour, the same "latest forecast wins" rule the live hourly
     * graph uses for its own hindcast segment.
     *
     * Best-effort: a day with no matching history rows (never fetched, or aged past the 18-month
     * hourly_forecast_history retention) is simply left with null chances, same as today — no
     * regression, just a missed enhancement for that day. Gated by a one-time marker in app_logs
     * (reusing the existing pipeline-health log rather than a new config field or file) since a row's
     * chances staying null forever would otherwise re-scan every call.
     */
    internal fun backfillForecastChanceSnapshotsIfNeeded(now: Long) {
        if (weatherDao.getRecentLogsByTags(listOf(CHANCE_BACKFILL_DONE_TAG), limit = 1).isNotEmpty()) return
        val zoneId = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val startMs = today.minusDays(CHANCE_BACKFILL_LOOKBACK_DAYS).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

        val rowsNeedingBackfill = weatherDao.getExtremesInRange(startMs, endMs, latitude, longitude)
            .filter { it.forecastDayPrecipChance == null && it.forecastNightPrecipChance == null }

        val toUpsert = mutableListOf<DailyHistory>()
        for (row in rowsNeedingBackfill) {
            val date = LocalDate.ofEpochDay(row.date / 86_400_000L)
            val windowStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val windowEndMs = date.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
            val historyRows = weatherDao.getHourlyHistory(latitude, longitude, row.source, windowStartMs, windowEndMs, now)
            if (historyRows.isEmpty()) continue

            val dayNight = com.weatherwidget.shared.util.DailyRainLabels.calculateDayNightPrecipProbabilities(
                hourly = historyRows,
                targetDate = date,
                displaySourceId = row.source,
                zoneId = zoneId,
            )
            if (dayNight.dayMax == null && dayNight.nightMax == null) continue
            toUpsert.add(row.copy(forecastDayPrecipChance = dayNight.dayMax, forecastNightPrecipChance = dayNight.nightMax))
        }
        if (toUpsert.isNotEmpty()) weatherDao.upsertDailyHistory(toUpsert)
        weatherDao.log(CHANCE_BACKFILL_DONE_TAG, "backfilled=${toUpsert.size} scanned=${rowsNeedingBackfill.size}")
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
    internal fun backfillFrozenDisplayColumnsIfNeeded(now: Long) {
        if (weatherDao.getRecentLogsByTags(listOf(FROZEN_DISPLAY_BACKFILL_DONE_TAG), limit = 1).isNotEmpty()) return
        val zoneId = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val startMs = today.minusDays(CHANCE_BACKFILL_LOOKBACK_DAYS).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

        // Per-column: a row can already carry noon cloud but no overlay (the live writer runs
        // before this backfill, and yesterday's noon-cloud window is still open on the first
        // post-migration fetch) — an all-columns-null row gate would skip its overlay forever.
        val rowsNeedingBackfill = weatherDao.getExtremesInRange(startMs, endMs, latitude, longitude)
            .filter { (it.forecastHighTemp == null && it.forecastLowTemp == null) || it.noonCloudPercent == null }

        val snapshotsBySource = rowsNeedingBackfill.map { it.source }.distinct().associateWith { source ->
            weatherDao.getDailyForecastSnapshots(startMs, endMs, latitude, longitude, source)
        }

        val toUpsert = mutableListOf<DailyHistory>()
        for (row in rowsNeedingBackfill) {
            val date = LocalDate.ofEpochDay(row.date / 86_400_000L)
            val overlay = snapshotsBySource[row.source]?.get(date.toString()).orEmpty()
                .filter { it.highTemp != null && it.lowTemp != null && it.highTemp != it.lowTemp }
                .maxByOrNull { it.fetchedAt }

            val windowStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val windowEndMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val historyRows = weatherDao.getHourlyHistory(latitude, longitude, row.source, windowStartMs, windowEndMs, now)
            val noonCloud = if (historyRows.isEmpty()) {
                null
            } else {
                com.weatherwidget.shared.util.DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(
                    hourly = historyRows,
                    date = date,
                    displaySourceId = row.source,
                    zone = zoneId,
                )
            }

            // Fill only what's missing — never overwrite a value the live writer already froze.
            val updated = row.copy(
                forecastHighTemp = row.forecastHighTemp ?: overlay?.highTemp,
                forecastLowTemp = row.forecastLowTemp ?: overlay?.lowTemp,
                forecastPrecipAmountMm = row.forecastPrecipAmountMm ?: overlay?.precipAmountMm,
                noonCloudPercent = row.noonCloudPercent ?: noonCloud,
            )
            if (updated == row) continue
            toUpsert.add(updated)
        }
        if (toUpsert.isNotEmpty()) weatherDao.upsertDailyHistory(toUpsert)
        weatherDao.log(FROZEN_DISPLAY_BACKFILL_DONE_TAG, "backfilled=${toUpsert.size} scanned=${rowsNeedingBackfill.size}")
    }

    private fun loadDailyActuals(daily: List<DailyForecast>): Map<String, DailyHistory> {
        if (daily.isEmpty()) return emptyMap()
        val dates = daily.map { LocalDate.parse(it.date) }
        val start = dates.min().minusDays(ACTUALS_HISTORY_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = dates.max().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return weatherDao.getDailyActuals(start, end, latitude, longitude, weatherSource)
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
    /** @return true only if normals were just (re)fetched and cached, so callers can reload state. */
    suspend fun ensureClimateNormals(): Boolean {
        try {
            val key = ClimateNormals.locationKey(latitude, longitude)
            val (cachedHigh, _) = weatherDao.getClimateNormals(key)
            if (cachedHigh.isNotEmpty()) return false

            val (startDate, endDate) = ClimateNormals.rollingWindow()
            val dailyTemps = weatherService.fetchHistoricalDailyTemps(startDate, endDate)
            val (monthlyHigh, monthlyLow) = ClimateNormals.monthlyMeans(dailyTemps)
            if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) {
                weatherDao.log("CLIMATE_FETCH_EMPTY", "rows=${dailyTemps.size}", "WARN")
                return false
            }
            weatherDao.upsertClimateNormals(key, monthlyHigh, monthlyLow)
            weatherDao.log("CLIMATE_CACHED", "key=$key months=${monthlyHigh.size}")
            return true
        } catch (e: Exception) {
            Log.e("DesktopWeatherRepository", "Climate normals fetch failed: $e")
            return false
        }
    }

    /**
     * Appends climate-normal gap rows (isClimateNormal=true) for future dates not already covered by
     * a real forecast, out to [GAP_HORIZON_DAYS]. Read-only (cached normals); no network. The daily
     * model already renders such rows as a green fallback bar, so no model/graph change is needed.
     */
    private fun appendClimateNormalGaps(daily: List<DailyForecast>, now: Long): List<DailyForecast> {
        val (monthlyHigh, monthlyLow) = weatherDao.getClimateNormals(ClimateNormals.locationKey(latitude, longitude))
        if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) return daily

        val normals = ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)
        val existing = daily.map { LocalDate.parse(it.date) }.toSet()
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val gaps = ClimateNormals.fillGaps(existing, normals, today, GAP_HORIZON_DAYS).map { gap ->
            DailyForecast(
                date = gap.date.toString(),
                highTemp = gap.highTemp,
                lowTemp = gap.lowTemp,
                condition = "Historical Avg",
                isClimateNormal = true,
            )
        }
        return if (gaps.isEmpty()) daily else daily + gaps
    }

    companion object {
        private const val TAG = "DesktopWeatherRepository"
        private const val WAPI_HISTORY_CHECK_TAG = "WAPI_HISTORY_CHECK"
        private const val WAPI_HISTORY_RESULT_TAG = "WAPI_HISTORY_RESULT"
        private const val HISTORY_WINDOW_DAYS = 7L
        private const val ACTUALS_HISTORY_DAYS = 547L
        private const val FRESH_OBSERVATION_MS = 30 * 60 * 1000L
        private const val GAP_HORIZON_DAYS = 16L
        // The launch backfill / normal refresh always cover ~7 days back, so on-demand history starts
        // from here and only deepens. Capped at the 18-month DB retention (cleanup deletes past that).
        private const val BASELINE_HISTORY_DAYS = 7
        private const val MAX_HISTORY_DAYS = 547
        private const val CHANCE_BACKFILL_LOOKBACK_DAYS = 547L
        private const val DB_RETENTION_DAYS = 547L // 18 months (~547 days)
        private const val CHANCE_BACKFILL_DONE_TAG = "CHANCE_BACKFILL_DONE"
        private const val FROZEN_DISPLAY_BACKFILL_DONE_TAG = "FROZEN_DISPLAY_BACKFILL_DONE"
    }
}
