package com.weatherwidget.data.repository

import com.weatherwidget.shared.history.WeatherApiHistoryRun
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.shared.history.ProviderHistoryDecision
import com.weatherwidget.shared.history.ProviderHistoryFailureClass
import com.weatherwidget.shared.history.ProviderHistoryPolicy
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal typealias WeatherApiHistoryBackfillStatus = WeatherApiHistoryRun.Status

internal typealias WeatherApiHistoryBackfillResult = WeatherApiHistoryRun.Result

/**
 * Repairs WeatherAPI's missing prior-day provider history without coupling it to the NWS station
 * observation worker. The rule is `:shared` [WeatherApiHistoryRun] (same as desktop); this is its
 * Android host. Failures are optional: a valid current forecast remains successful.
 */
internal class WeatherApiHistoryBackfiller(
    private val weatherApi: WeatherApi,
    private val observationDao: ObservationDao,
    private val hourlyStore: HourlyForecastStore,
    private val observationRepository: ObservationRepository,
    private val appLogDao: AppLogDao,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun backfillIfNeeded(
        latitude: Double,
        longitude: Double,
    ): WeatherApiHistoryBackfillResult = WeatherApiHistoryRun.run(
        RoomWeatherApiHistoryHost(
            lat = latitude,
            lon = longitude,
            observationDao = observationDao,
            appLogDao = appLogDao,
            hourlyStore = hourlyStore,
            fetch = { date -> weatherApi.getHistory(latitude, longitude, date).let { WeatherApiHistoryRun.History(it.hourly, it.daily.size) } },
            recompute = { date ->
                observationRepository.recomputeDailyExtremesFromStoredObservations(
                    latitude = latitude,
                    longitude = longitude,
                    startDate = date,
                    endDateInclusive = date,
                    hourlyForecasts = emptyList(),
                    // Repair path: it distrusts what is stored, which is the whole reason it ran.
                    force = true,
                )
            },
        ),
        nowMs = nowProvider(),
        zoneId = zoneIdProvider(),
    )
}

/**
 * Room side of [WeatherApiHistoryRun] at one site; the fetch and the day's extremes recompute are
 * passed in. Its stored read and the store are pinned to desktop's by `WeatherApiHistoryHostContract`
 * (`:shared` testFixtures).
 */
internal class RoomWeatherApiHistoryHost(
    override val lat: Double,
    override val lon: Double,
    private val observationDao: ObservationDao,
    private val appLogDao: AppLogDao,
    private val hourlyStore: HourlyForecastStore,
    private val fetch: suspend (java.time.LocalDate) -> WeatherApiHistoryRun.History,
    private val recompute: suspend (java.time.LocalDate) -> Unit,
) : WeatherApiHistoryRun.Host {
    override suspend fun storedObservations(startMs: Long, endMs: Long) =
        // Scoped to exactly what the run keeps.
        observationDao.getObservationsInRange(startMs, endMs, lat, lon, setOf(WeatherSource.WEATHER_API.id))
            .map { it.toReading() }

    override suspend fun latestResult(prefix: String) =
        appLogDao.getLatestLogByTagAndMessagePrefix(WeatherApiHistoryRun.RESULT_TAG, prefix)?.message

    override suspend fun fetchHistory(date: java.time.LocalDate) = fetch(date)

    override suspend fun store(targetHours: List<com.weatherwidget.data.model.HourlyForecast>, date: java.time.LocalDate) {
        // The elapsed hours are filed as observations by saveHistoricalActuals.
        hourlyStore.saveHourlyEntitiesFromShared(
            hourlyData = targetHours,
            latitude = lat,
            longitude = lon,
            sourceId = WeatherSource.WEATHER_API.id,
        )
        recompute(date)
    }

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
}
