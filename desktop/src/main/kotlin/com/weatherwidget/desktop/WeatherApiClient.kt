package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.remote.NwsApi
import java.time.LocalDate

/**
 * The fetch surface [DesktopWeatherRepository] needs from the network layer. Extracted so the
 * repository depends on a seam it can mock directly (and so tests stop reaching into
 * [DesktopWeatherService] with reflection to substitute API clients).
 */
interface WeatherApiClient {
    suspend fun fetchForecast(): RawFetch

    /** Legacy Open-Meteo history backfill entry point; pinned as uncalled by a regression test. */
    suspend fun fetchHistory(historyDays: Int): RawFetch

    suspend fun fetchWeatherApiHistory(date: LocalDate): RawFetch

    suspend fun fetchObservationHistory(historyDays: Long): List<ObservationReading>

    suspend fun fetchObservationsOnly(): RawFetch

    suspend fun nearestStationsForDailyActuals(): List<NwsApi.StationInfo>

    suspend fun fetchApiObservationDay(
        station: NwsApi.StationInfo,
        startIso: String,
        endIso: String,
    ): List<ObservationReading>?

    suspend fun fetchHistoricalDailyTemps(startDate: String, endDate: String): List<DailyForecast>

    fun close()
}
