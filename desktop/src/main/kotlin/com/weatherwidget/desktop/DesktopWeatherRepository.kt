package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.*
import com.weatherwidget.data.model.*
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.shared.actuals.ActualsAggregator
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
import java.time.MonthDay
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class DesktopWeatherRepository(
    private val weatherService: DesktopWeatherService,
    private val weatherDao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
    private val weatherSource: String
) {
    private fun resolveForForecastResult(
        hourly: List<HourlyForecast>,
        now: Long
    ): Pair<Float?, Float?> {
        val displaySource = WeatherSource.fromDisplaySource(weatherSource)
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        
        val window = CurrentTemperatureResolver.buildCurrentTempResolutionWindow(nowLocal)
        val zoneId = ZoneId.systemDefault()
        val minEpoch = window.start.atZone(zoneId).toInstant().toEpochMilli()
        val maxEpoch = window.end.atZone(zoneId).toInstant().toEpochMilli()

        val observations = weatherDao.getObservationsInRange(minEpoch, maxEpoch, latitude, longitude)
            .map { it.toReading() }
        
        val narrowHourly = hourly.filter { it.dateTime in minEpoch..maxEpoch }

        val resolvedObs = ActualsAggregator.resolveCurrentObservation(
            observations = observations,
            hourlyForecasts = narrowHourly,
            displaySourceId = displaySource.id,
            userLat = latitude,
            userLon = longitude,
            nowMs = now,
            lookbackHours = 12L,
            lookaheadHours = 3L,
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
        )
        return resolution.displayTemp to resolution.appliedDelta
    }

    suspend fun loadCached(now: Long = System.currentTimeMillis()): ForecastResult? = withContext(Dispatchers.IO) {
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours for cache
        // Cover the widest zoom-out (6 days back) so the continuous-zoom graph never truncates history.
        val stitchedStart = now - (DesktopGraphUtils.MAX_BACK_HOURS * 3600 * 1000L)
        val hourly = weatherDao.getHourlyWithHistory(latitude, longitude, weatherSource, stitchedStart, now + (168 * 3600 * 1000L), maxAgeMs)
        val daily = weatherDao.getDailyForecasts(latitude, longitude, weatherSource)
        
        // Cover the widest zoom-out (6 days back) so the actual line spans the whole window,
        // matching the hourly read above. Observations exist ~7-14 days back (NWS HISTORY_DAYS=7,
        // 30-day retention); only the read window was capping the actual line at 2 days.
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

        val (currentTemp, appliedDelta) = resolveForForecastResult(hourly, now)
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
     * Gated to NWS: it's the only source whose deep history we extend on demand (station observations).
     */
    fun needsDeeperHistory(neededBackHours: Int): Boolean =
        weatherSource == "NWS" && neededHistoryDays(neededBackHours) > deepestHistoryDaysFetched

    /**
     * Pulls older NWS station observations on demand when the user zooms/pans the graph past what's
     * cached — these drive the pink actual line. Idempotent and guarded so rapid wheel events don't
     * spam the network; returns true only when new data was persisted (caller then reloads the cache).
     *
     * Deliberately does NOT backfill an Open-Meteo forecast curve: GENERIC_GAP is future-only and must
     * never fill history (its Open-Meteo decimals would masquerade as the real, whole-degree NWS
     * forecast). The past forecast curve comes solely from real accumulated NWS snapshots.
     */
    suspend fun ensureHistory(neededBackHours: Int): Boolean = withContext(Dispatchers.IO) {
        if (weatherSource != "NWS") return@withContext false
        val neededDays = neededHistoryDays(neededBackHours)
        if (neededDays <= deepestHistoryDaysFetched) return@withContext false
        historyFetchMutex.withLock {
            // Re-check under the lock: a concurrent call may have already deepened coverage.
            if (neededDays <= deepestHistoryDaysFetched) return@withLock false
            var fetchedAny = false
            try {
                val obs = weatherService.fetchObservationHistory(neededDays.toLong())
                if (obs.isNotEmpty()) {
                    weatherDao.upsertObservations(obs.map { it.toEntity(System.currentTimeMillis()) })
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

    suspend fun refresh(now: Long = System.currentTimeMillis()): ForecastResult = withContext(Dispatchers.IO) {
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

        // Derive actual daily highs/lows from the stored observation window — the actuals that
        // forecast-accuracy comparisons are measured against.
        val extremesCount = recomputeDailyExtremes(now)

        // Snapshot for history (Tier 1 simplification: 4h buckets)
        val snapshotBucket = (now / (4 * 3600 * 1000L)) * (4 * 3600 * 1000L)
        weatherDao.upsertHourlyForecastHistory(latitude, longitude, weatherSource, snapshotBucket, result.hourly)

        // Best-effort: ensure climate normals are cached for the future-day fallback. One network
        // fetch per location, then served from cache; never fails the main refresh.
        ensureClimateNormals()

        // Cleanup old data (> 30 days)
        weatherDao.cleanup(now - (30L * 24 * 3600 * 1000))

        // Persistent pipeline-health summary
        weatherDao.log(
            tag = "REFRESH",
            message = "source=$weatherSource hourly=${result.hourly.size} daily=${result.daily.size} " +
                "obs=${result.rawObservations.size} extremes=$extremesCount",
        )

        loadCached(now) ?: result
    }

    suspend fun refreshObservations(): ForecastResult = withContext(Dispatchers.IO) {
        val result = weatherService.fetchObservationsOnly()
        val now = System.currentTimeMillis()

        if (result.rawObservations.isNotEmpty()) {
            weatherDao.upsertObservations(result.rawObservations.map { it.toEntity(now) })
        }

        val extremesCount = recomputeDailyExtremes(now)
        val cached = loadCached(now)

        weatherDao.log(
            tag = "OBS_REFRESH",
            message = "source=$weatherSource obs=${result.rawObservations.size} extremes=$extremesCount",
        )

        val cachedHourly = cached?.hourly ?: emptyList()
        val (currentTemp, appliedDelta) = resolveForForecastResult(cachedHourly, now)

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
    }

    /** Reads the stored observation window, (re)computes daily_extremes, and returns the row count. */
    private fun recomputeDailyExtremes(now: Long): Int {
        val windowStart = now - (HISTORY_WINDOW_DAYS + 1) * 86_400_000L
        val windowEnd = now + 86_400_000L
        val observations = weatherDao.getObservationsInRange(windowStart, windowEnd, latitude, longitude)
        val hourly = weatherDao.getHourlyWithHistory(latitude, longitude, weatherSource, now - (72 * 3600 * 1000L), now + 86_400_000L, 48 * 3600 * 1000L)

        val extremes = ActualsAggregator.aggregate(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourly,
            locationLat = latitude,
            locationLon = longitude,
            updatedAtMs = now
        )

        weatherDao.upsertDailyExtremes(extremes)
        return extremes.size
    }

    private fun loadDailyActuals(daily: List<DailyForecast>): Map<String, DailyExtreme> {
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
        val gaps = mutableListOf<DailyForecast>()
        var date = today
        val end = today.plusDays(GAP_HORIZON_DAYS)
        while (!date.isAfter(end)) {
            if (date !in existing) {
                normals[MonthDay.from(date)]?.let { (high, low) ->
                    gaps.add(
                        DailyForecast(
                            date = date.toString(),
                            highTemp = high,
                            lowTemp = low,
                            condition = "Historical Avg",
                            isClimateNormal = true,
                        ),
                    )
                }
            }
            date = date.plusDays(1)
        }
        return if (gaps.isEmpty()) daily else daily + gaps
    }

    companion object {
        private const val HISTORY_WINDOW_DAYS = 7L
        private const val ACTUALS_HISTORY_DAYS = 31L
        private const val FRESH_OBSERVATION_MS = 30 * 60 * 1000L
        private const val GAP_HORIZON_DAYS = 16L
        // The launch backfill / normal refresh always cover ~7 days back, so on-demand history starts
        // from here and only deepens. Capped at the 30-day DB retention (cleanup deletes past that).
        private const val BASELINE_HISTORY_DAYS = 7
        private const val MAX_HISTORY_DAYS = 30
    }
}
