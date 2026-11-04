package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast

/**
 * Desktop SQLite side of [ElapsedForecastBackfill.Store]: [sourceId]'s `hourly_forecasts` at
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
    override suspend fun insert(rows: List<HourlyForecast>) =
        weatherDao.upsertHourlyForecastHistory(latitude, longitude, sourceId, bucket, rows)
}
