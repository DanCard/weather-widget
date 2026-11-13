package com.weatherwidget.stats

import com.weatherwidget.data.local.HourlyActualDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.toHourlyActual
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.RainAccuracyReport
import com.weatherwidget.shared.stats.RainAccuracyRunner
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Android entry to the shared [RainAccuracyRunner] (plans/261113-per-source-rain-score.md). */
@Singleton
class RainAccuracyCalculator
    @Inject
    constructor(
        hourlyForecastHistoryDao: HourlyForecastHistoryDao,
        observationDao: ObservationDao,
        hourlyActualDao: HourlyActualDao,
    ) {
        private val runner = RainAccuracyRunner(RoomRainAccuracyHost(hourlyForecastHistoryDao, observationDao, hourlyActualDao))

        suspend fun report(
            sources: List<WeatherSource>,
            lat: Double,
            lon: Double,
            days: Int = 30,
            zone: ZoneId = ZoneId.systemDefault(),
            today: LocalDate = LocalDate.now(zone),
        ): RainAccuracyReport = runner.run(sources, lat, lon, days, zone, today)
    }

/** Room rows for [RainAccuracyRunner]; reads only. */
class RoomRainAccuracyHost(
    private val hourlyForecastHistoryDao: HourlyForecastHistoryDao,
    private val observationDao: ObservationDao,
    private val hourlyActualDao: HourlyActualDao,
) : RainAccuracyRunner.Host {
    override suspend fun forecastSnapshots(
        source: WeatherSource,
        startMs: Long,
        endMs: Long,
        bucketStartMs: Long,
        bucketEndMs: Long,
        lat: Double,
        lon: Double,
    ): List<RainAccuracyRunner.PrecipSnapshot> =
        hourlyForecastHistoryDao.getHistoryInRangeForBucketWindow(
            startDateTime = startMs,
            endDateTime = endMs,
            bucketStart = bucketStartMs,
            bucketEnd = bucketEndMs,
            lat = lat,
            lon = lon,
            source = source.id,
        ).map { RainAccuracyRunner.PrecipSnapshot(it.dateTime, it.timestampToGroupPredictions, it.precipAmountMm) }

    // Unscoped by api: the runner keeps only measured gauge feeds (ObservedPrecip.measuredRows).
    override suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double): List<ObservationReading> =
        observationDao.getObservationsInRange(startMs, endMs, lat, lon, apis = null).map { it.toReading() }

    override suspend fun archivedActuals(startMs: Long, endMs: Long, lat: Double, lon: Double, sourceId: String): List<HourlyActual> =
        hourlyActualDao.getInRange(startMs, endMs, lat, lon, sourceId).map { it.toHourlyActual() }
}
