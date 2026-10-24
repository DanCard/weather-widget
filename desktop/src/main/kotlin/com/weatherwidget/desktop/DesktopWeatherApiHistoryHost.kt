package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.toEntity
import com.weatherwidget.data.local.desktop.toReading
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.history.WeatherApiHistoryRun
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill
import java.time.LocalDate

/**
 * Desktop SQLite side of [WeatherApiHistoryRun] at one site; the fetch is passed in. The day's
 * extremes are recomputed by the refresh's post-fetch pass, not here. Pinned to Android's Room Host
 * by `WeatherApiHistoryHostContract` (`:shared` testFixtures).
 */
internal class DesktopWeatherApiHistoryHost(
    private val weatherDao: DesktopWeatherDao,
    override val lat: Double,
    override val lon: Double,
    private val nowMs: Long,
    private val fetch: suspend (LocalDate) -> WeatherApiHistoryRun.History,
) : WeatherApiHistoryRun.Host {
    // Scoped to what the run keeps, as Android's read.
    override suspend fun storedObservations(startMs: Long, endMs: Long) =
        weatherDao.getObservationsInRange(startMs, endMs, lat, lon)
            .filter { it.api == WeatherSource.WEATHER_API.id }
            .map { it.toReading() }

    override suspend fun latestResult(prefix: String) =
        weatherDao.getLatestLogByTagAndMessagePrefix(WeatherApiHistoryRun.RESULT_TAG, prefix)?.message

    override suspend fun fetchHistory(date: LocalDate) = fetch(date)

    // The day's hours filed as WeatherAPI observations (the shared historical-actuals rule).
    override suspend fun store(targetHours: List<HourlyForecast>, date: LocalDate) =
        weatherDao.upsertObservations(
            HistoricalActualsBackfill.build(
                hourly = targetHours,
                latitude = lat,
                longitude = lon,
                sourceId = WeatherSource.WEATHER_API.id,
                nowMs = nowMs,
            ).map { it.toEntity(nowMs) },
        )

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
