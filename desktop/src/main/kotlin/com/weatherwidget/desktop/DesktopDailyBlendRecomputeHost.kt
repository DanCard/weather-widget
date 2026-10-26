package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.toReading
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import com.weatherwidget.shared.actuals.ReducedSignatureStore

/**
 * Desktop SQLite side of [DailyBlendRecomputeRun] at one site. Pinned to Android's Room Host by
 * `DailyBlendRecomputeHostContract` (`:shared` testFixtures).
 */
internal class DesktopDailyBlendRecomputeHost(
    private val weatherDao: DesktopWeatherDao,
    override val lat: Double,
    override val lon: Double,
    private val personalStationWeight: Double,
    override val signatures: ReducedSignatureStore,
) : DailyBlendRecomputeRun.Host {
    override fun personalStationWeight() = personalStationWeight

    override suspend fun observations(startMs: Long, endMs: Long) =
        weatherDao.getObservationsInRange(startMs, endMs, lat, lon).map { it.toReading() }

    override suspend fun hourlyAllSources(startMs: Long, endMs: Long) =
        weatherDao.getHourlyForecastsAllSources(lat, lon, startMs, endMs)

    override suspend fun observationSignature(startMs: Long, endMs: Long) =
        weatherDao.observationSignatureInRange(startMs, endMs, lat, lon)

    override suspend fun dailyHistoryAt(dateKey: Long) = weatherDao.getExtremesInRange(dateKey, dateKey, lat, lon)

    override suspend fun insert(rows: List<DailyHistory>) = weatherDao.upsertDailyHistory(rows)

    override suspend fun updateBlendIfUnchanged(merged: DailyHistory, newUpdatedAt: Long, expectedUpdatedAt: Long) =
        weatherDao.updateBlendIfUnchanged(merged, newUpdatedAt, expectedUpdatedAt)

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)

    override fun elapsedMs() = System.nanoTime() / 1_000_000L
}
