package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DailyExtremesComputer
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.ObservationReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val latestObs = weatherDao.getLatestObservation(latitude, longitude, maxAgeMs)

        if (hourly.isEmpty() && daily.isEmpty()) {
            return@withContext null
        }

        ForecastResult(
            currentTemp = latestObs?.temperature ?: hourly.firstOrNull()?.temperature,
            currentCondition = latestObs?.condition ?: hourly.firstOrNull()?.condition,
            currentObservedAt = latestObs?.timestamp ?: hourly.firstOrNull()?.dateTime,
            daily = daily,
            hourly = hourly
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

        result
    }

    /** Reads the stored observation window, (re)computes daily_extremes, and returns the row count. */
    private fun recomputeDailyExtremes(now: Long): Int {
        val windowStart = now - (HISTORY_WINDOW_DAYS + 1) * DailyExtremesComputer.MS_IN_A_DAY
        val windowEnd = now + DailyExtremesComputer.MS_IN_A_DAY
        val observations = weatherDao.getObservationsInRange(windowStart, windowEnd, latitude, longitude)
        val extremes = DailyExtremesComputer.compute(observations)
        if (extremes.isNotEmpty()) {
            weatherDao.upsertDailyExtremes(extremes)
        }
        return extremes.size
    }

    private fun ObservationReading.toEntity(fetchedAt: Long) = DesktopObservationEntity(
        stationId = stationId,
        stationName = stationName,
        timestamp = timestamp,
        temperature = temperature,
        condition = condition,
        locationLat = locationLat,
        locationLon = locationLon,
        distanceKm = distanceKm,
        stationType = stationType,
        fetchedAt = fetchedAt,
        maxTempLast24h = maxTempLast24h,
        minTempLast24h = minTempLast24h,
        api = api,
        precipAmountMm = precipAmountMm,
    )

    companion object {
        private const val HISTORY_WINDOW_DAYS = 7L
    }
}
