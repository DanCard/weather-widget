package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.*
import com.weatherwidget.data.model.*
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.util.SpatialInterpolator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

class DesktopWeatherRepository(
    private val weatherService: DesktopWeatherService,
    private val weatherDao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
    private val weatherSource: String
) {
    suspend fun loadCached(): ForecastResult? = withContext(Dispatchers.IO) {
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours for cache
        val hourly = weatherDao.getLatestHourly(latitude, longitude, weatherSource, maxAgeMs)
        val daily = weatherDao.getDailyForecasts(latitude, longitude, weatherSource)
        val now = System.currentTimeMillis()
        
        // Fetch observations for the past 48 hours to populate the actual line
        val obsStart = now - (48 * 3600 * 1000L)
        val obsEnd = now + (2 * 3600 * 1000L) // Include some cushion
        val observations = weatherDao.getObservationsInRange(obsStart, obsEnd, latitude, longitude)
            .map { it.toReading() }

        val newestTs = observations.maxOfOrNull { it.timestamp }
        val newestObs = if (newestTs != null) {
            val candidates = observations.filter { it.timestamp == newestTs }
            candidates.find { it.stationId == "NWS_BLEND" } ?: candidates.firstOrNull()
        } else null

        // Freshness gate only governs whether the *current temp* is shown as observed vs interpolated.
        val latestObs = newestObs?.takeIf { now - it.timestamp < FRESH_OBSERVATION_MS }
        val interpolatedTemp = TemperatureInterpolator.getInterpolatedTemperature(hourly, now)
        val actuals = loadDailyActuals(daily)
        val snapshots = loadDailySnapshots(daily)

        if (hourly.isEmpty() && daily.isEmpty()) {
            return@withContext null
        }

        ForecastResult(
            currentTemp = latestObs?.temperature ?: interpolatedTemp ?: hourly.firstOrNull()?.temperature,
            currentCondition = latestObs?.condition ?: hourly.firstOrNull()?.condition,
            // Timestamp of the genuine newest observation — never the earliest forecast hour.
            // The graph uses this as the actual/forecast transition; an early forecast hour here
            // would collapse the whole actual line. Mirrors DesktopWeatherService's refresh path.
            currentObservedAt = newestObs?.timestamp,
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

        result.copy(
            currentTemp = result.currentTemp
                ?: TemperatureInterpolator.getInterpolatedTemperature(result.hourly, now),
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

        result.copy(
            currentTemp = result.currentTemp ?: cached?.currentTemp,
            currentCondition = result.currentCondition ?: cached?.currentCondition,
            currentObservedAt = result.currentObservedAt ?: cached?.currentObservedAt,
            daily = cached?.daily ?: emptyList(),
            hourly = cached?.hourly ?: emptyList(),
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
        val hourly = weatherDao.getLatestHourly(latitude, longitude, weatherSource, 48 * 3600 * 1000L)

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
        val start = dates.min().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
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
        private const val FRESH_OBSERVATION_MS = 30 * 60 * 1000L
    }
}
