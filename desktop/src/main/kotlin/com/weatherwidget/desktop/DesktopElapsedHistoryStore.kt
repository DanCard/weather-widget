package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast

/**
 * Desktop SQLite side of [ElapsedForecastBackfill.Store]: [sourceId]'s `hourly_forecast_history` at
 * one site, new rows filed under [bucket]. Pinned to Android's by `ElapsedForecastBackfillStoreContract`
 * (`:shared` testFixtures).
 */
internal class DesktopElapsedHistoryStore(
    private val weatherDao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
    private val sourceId: String,
    private val bucket: Long,
) : ElapsedForecastBackfill.Store {
    override suspend fun coveredHours(startMs: Long, endMs: Long) = weatherDao.getHourlyHistoryCoveredHours(
        LocationMatch.quantize(latitude), LocationMatch.quantize(longitude), sourceId, startMs, endMs,
    )

    override suspend fun insert(rows: List<HourlyForecast>) =
        weatherDao.upsertHourlyForecastHistory(latitude, longitude, sourceId, bucket, rows)
}
