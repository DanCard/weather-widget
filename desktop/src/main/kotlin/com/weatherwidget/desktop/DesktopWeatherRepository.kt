package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.*
import com.weatherwidget.data.model.*
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.util.SpatialInterpolator
import com.weatherwidget.widget.CurrentTemperatureResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

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
        val obsStart = now - (48 * 3600 * 1000L)
        val obsEnd = now + (2 * 3600 * 1000L)
        val observations = weatherDao.getObservationsInRange(obsStart, obsEnd, latitude, longitude)
            .map { it.toReading() }

        val displaySource = WeatherSource.fromDisplaySource(weatherSource)
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())

        val resolvedObs = ActualsAggregator.resolveCurrentObservation(
            observations = observations,
            hourlyForecasts = hourly,
            displaySourceId = displaySource.id,
            userLat = latitude,
            userLon = longitude,
            nowMs = now,
            lookbackHours = 12L,
            lookaheadHours = 2L,
        )

        val lastObservedTemp = resolvedObs?.first
        val observedAt = resolvedObs?.second

        val resolution = CurrentTemperatureResolver.resolve(
            now = nowLocal,
            displaySource = displaySource,
            hourlyForecasts = hourly,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            storedDeltaState = null,
            currentLat = latitude,
            currentLon = longitude,
        )
        return resolution.displayTemp to resolution.appliedDelta
    }

    suspend fun loadCached(): ForecastResult? = withContext(Dispatchers.IO) {
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours for cache
        val now = System.currentTimeMillis()
        val stitchedStart = now - (72 * 3600 * 1000L)
        val hourly = weatherDao.getHourlyWithHistory(latitude, longitude, weatherSource, stitchedStart, now + (168 * 3600 * 1000L), maxAgeMs)
        val daily = weatherDao.getDailyForecasts(latitude, longitude, weatherSource)
        
        // Fetch observations for the past 48 hours to populate the actual line
        val obsStart = now - (48 * 3600 * 1000L)
        val obsEnd = now + (2 * 3600 * 1000L) // Include some cushion
        val observations = weatherDao.getObservationsInRange(obsStart, obsEnd, latitude, longitude)
            .map { it.toReading() }

        // Prefer the most-recent NWS_BLEND synthetic row — it represents the IDW-weighted truth
        // across all stations. Raw station rows can have newer timestamps (from historical fetches)
        // but those are single-station readings, not the calibrated blend.
        val newestObs = observations.filter { it.stationId == "NWS_BLEND" }.maxByOrNull { it.timestamp }
            ?: observations.maxByOrNull { it.timestamp }

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
            daily = daily,
            hourly = hourly,
            dailyActuals = actuals,
            dailySnapshots = snapshots,
            rawObservations = observations,
        )
    }

    suspend fun refresh(): ForecastResult = withContext(Dispatchers.IO) {
        val result = weatherService.fetchForecast()
        val now = System.currentTimeMillis()

        // Persist
        weatherDao.upsertHourlyForecasts(latitude, longitude, weatherSource, result.hourly)
        weatherDao.upsertForecasts(latitude, longitude, weatherSource, result.daily)

        if (result.rawObservations.isNotEmpty()) {
            weatherDao.upsertObservations(result.rawObservations.map { it.toEntity(now) })
        }

        // Derive actual daily highs/lows from the stored observation window — the actuals that
        // forecast-accuracy comparisons are measured against.
        val extremesCount = recomputeDailyExtremes(now)
        val actuals = loadDailyActuals(result.daily)
        val snapshots = loadDailySnapshots(result.daily)

        // Snapshot for history (Tier 1 simplification: 4h buckets)
        val snapshotBucket = (now / (4 * 3600 * 1000L)) * (4 * 3600 * 1000L)
        weatherDao.upsertHourlyForecastHistory(latitude, longitude, weatherSource, snapshotBucket, result.hourly)

        // Cleanup old data (> 30 days)
        weatherDao.cleanup(now - (30L * 24 * 3600 * 1000))

        // Persistent pipeline-health summary — makes a starving actuals pipeline (e.g. zero
        // observations) visible after the fact via the app_logs table, not just a scrolled-away
        // console line. A low obs/extremes count here is the signal that caught the
        // fractional-seconds bug.
        weatherDao.log(
            tag = "REFRESH",
            message = "source=$weatherSource hourly=${result.hourly.size} daily=${result.daily.size} " +
                "obs=${result.rawObservations.size} extremes=$extremesCount",
        )

        val (currentTemp, appliedDelta) = resolveForForecastResult(result.hourly, now)
        val newestObs = result.rawObservations.filter { it.stationId == "NWS_BLEND" }.maxByOrNull { it.timestamp }
            ?: result.rawObservations.maxByOrNull { it.timestamp }

        result.copy(
            currentTemp = currentTemp,
            appliedDelta = appliedDelta,
            currentObservedAt = newestObs?.timestamp ?: result.currentObservedAt,
            dailyActuals = actuals,
            dailySnapshots = snapshots,
            rawObservations = result.rawObservations,
        )
    }

    suspend fun refreshObservations(): ForecastResult = withContext(Dispatchers.IO) {
        val result = weatherService.fetchObservationsOnly()
        val now = System.currentTimeMillis()

        if (result.rawObservations.isNotEmpty()) {
            weatherDao.upsertObservations(result.rawObservations.map { it.toEntity(now) })
        }

        val extremesCount = recomputeDailyExtremes(now)
        val cached = loadCached()

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

    private fun loadDailyActuals(daily: List<com.weatherwidget.data.model.DailyForecast>): Map<String, com.weatherwidget.data.model.DailyExtreme> {
        if (daily.isEmpty()) return emptyMap()
        val dates = daily.map { LocalDate.parse(it.date) }
        // Look back past the forecast window so historical actual extremes are available for
        // left/history navigation. The forecast `daily` list begins ~yesterday, so anchoring the
        // query at dates.min() never loaded older actuals and left the history arrow disabled.
        val start = dates.min().minusDays(ACTUALS_HISTORY_DAYS)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = dates.max().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return weatherDao.getDailyActuals(start, end, latitude, longitude, weatherSource)
    }

    private fun loadDailySnapshots(daily: List<com.weatherwidget.data.model.DailyForecast>): Map<String, List<com.weatherwidget.data.model.DailyForecastSnapshot>> {
        if (daily.isEmpty()) return emptyMap()
        val dates = daily.map { LocalDate.parse(it.date) }
        val start = dates.min().minusDays(14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = dates.max().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return weatherDao.getDailyForecastSnapshots(start, end, latitude, longitude, weatherSource)
    }



    companion object {
        private const val HISTORY_WINDOW_DAYS = 7L
        // Match the widget's ~30-day history navigation and the 1-month data retention window.
        private const val ACTUALS_HISTORY_DAYS = 31L
        private const val FRESH_OBSERVATION_MS = 30 * 60 * 1000L
    }
}
