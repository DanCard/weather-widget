package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill

/**
 * Current conditions for one model feed — what Android's current-temp fetch and desktop's
 * observations-only fetch each used to build on their own (plans/261013-shared-current-conditions-fetch.md).
 * Fetches and maps; the platform stores [Result.readings] and logs.
 *
 * **Centre point only.** These feeds' actuals are virtual stations (model output at a point), so
 * Android's four ~8–10 km offset samples added requests without adding a real reading (user,
 * 2026-10-13). Station networks (NWS, METAR, Synoptic) are not handled here.
 *
 * Errors propagate: the clients' `require2xx` raises [com.weatherwidget.data.remote.ApiAccessException]
 * for a refused request, and what a platform makes of any failure is its own call.
 */
class CurrentConditionsFetcher(
    private val calls: Calls,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The client calls used, as functions so tests need no HTTP. Null = that client is not configured. */
    class Calls(
        val openMeteoCurrent: (suspend (Double, Double) -> OpenMeteoApi.CurrentReading?)? = null,
        val openMeteoCondition: (Int) -> String = { "Unknown" },
        val weatherApiForecast: (suspend (Double, Double) -> RawFetch)? = null,
        val openWeatherMapForecast: (suspend (Double, Double) -> RawFetch)? = null,
        val tomorrowIoFiveMinuteHistory: (suspend (Double, Double, Int) -> RawFetch)? = null,
    )

    /** Rows to store, and the feed's own current product (null when it returned none). */
    data class Result(val readings: List<ObservationReading>, val current: Current?)

    data class Current(
        val source: WeatherSource,
        val temperature: Float,
        val condition: String?,
        val observedAt: Long?,
    )

    /** Null when [feed] is not one this fetcher serves (see [FEEDS]) or its client is not configured. */
    suspend fun fetch(feed: WeatherSource, latitude: Double, longitude: Double): Result? =
        when (feed) {
            WeatherSource.OPEN_METEO -> calls.openMeteoCurrent?.let { openMeteo(it, latitude, longitude) }
            WeatherSource.WEATHER_API -> calls.weatherApiForecast?.let { forecastCurrent(feed, "WAPI", it, latitude, longitude) }
            WeatherSource.OPEN_WEATHER_MAP -> calls.openWeatherMapForecast?.let { forecastCurrent(feed, "OWM", it, latitude, longitude) }
            WeatherSource.TOMORROW_IO -> calls.tomorrowIoFiveMinuteHistory?.let { tomorrowIo(it, latitude, longitude) }
            else -> null
        }

    private suspend fun openMeteo(
        call: suspend (Double, Double) -> OpenMeteoApi.CurrentReading?,
        latitude: Double,
        longitude: Double,
    ): Result {
        val reading = call(latitude, longitude) ?: return Result(emptyList(), null)
        val now = nowMs()
        val condition = reading.weatherCode?.let(calls.openMeteoCondition)
        val hasBands = reading.cloudCoverLow != null || reading.cloudCoverMid != null || reading.cloudCoverHigh != null
        val row = ObservationReading(
            stationId = mainStationId(WeatherSource.OPEN_METEO),
            stationName = "Meteo: Current",
            timestamp = reading.observedAt ?: now,
            temperature = reading.temperature,
            condition = condition ?: "Unknown",
            locationLat = latitude,
            locationLon = longitude,
            distanceKm = 0f,
            stationType = StationType.OFFICIAL,
            api = WeatherSource.OPEN_METEO.id,
            fetchedAt = now,
            cloudCover = reading.cloudCover,
            cloudCoverLow = reading.cloudCoverLow,
            cloudCoverMid = reading.cloudCoverMid,
            cloudCoverHigh = reading.cloudCoverHigh,
            cloudVerticalKind = if (hasBands) CloudVerticalKind.PROVIDER_BANDS else CloudVerticalKind.NONE,
        )
        return Result(listOf(row), Current(WeatherSource.OPEN_METEO, reading.temperature, condition, reading.observedAt))
    }

    /** A source whose current conditions ride on its forecast response (WeatherAPI, OpenWeatherMap). */
    private suspend fun forecastCurrent(
        source: WeatherSource,
        label: String,
        call: suspend (Double, Double) -> RawFetch,
        latitude: Double,
        longitude: Double,
    ): Result {
        val forecast = call(latitude, longitude)
        val temperature = forecast.providerCurrentTemp ?: return Result(emptyList(), null)
        val now = nowMs()
        val row = ObservationReading(
            stationId = mainStationId(source),
            stationName = "$label: Current",
            timestamp = forecast.providerCurrentObservedAt ?: now,
            temperature = temperature,
            condition = forecast.providerCurrentCondition ?: "Unknown",
            locationLat = latitude,
            locationLon = longitude,
            distanceKm = 0f,
            stationType = StationType.OFFICIAL,
            api = source.id,
            fetchedAt = now,
        )
        return Result(
            listOf(row),
            Current(source, temperature, forecast.providerCurrentCondition, forecast.providerCurrentObservedAt),
        )
    }

    /** Tomorrow.io's five-minute history: every row of the window, the newest as the current reading. */
    private suspend fun tomorrowIo(
        call: suspend (Double, Double, Int) -> RawFetch,
        latitude: Double,
        longitude: Double,
    ): Result {
        val history = call(latitude, longitude, TomorrowIoApi.INCREMENTAL_ACTUALS_LOOKBACK_HOURS)
        val now = nowMs()
        val readings = HistoricalActualsBackfill.build(
            hourly = history.subHourly.ifEmpty { history.hourly },
            latitude = latitude,
            longitude = longitude,
            sourceId = WeatherSource.TOMORROW_IO.id,
            nowMs = now,
            fetchedAt = now,
        )
        val latest = readings.maxByOrNull { it.timestamp }
        return Result(
            readings,
            latest?.let { Current(WeatherSource.TOMORROW_IO, it.temperature, it.condition, it.timestamp) },
        )
    }

    companion object {
        /** The feeds served here; NWS and the station networks have their own fetchers. */
        val FEEDS: Set<WeatherSource> = setOf(
            WeatherSource.OPEN_METEO,
            WeatherSource.WEATHER_API,
            WeatherSource.OPEN_WEATHER_MAP,
            WeatherSource.TOMORROW_IO,
        )

        /** `<SOURCE>_MAIN`: the synthetic backfill id the blend already knows (ObservationSourceMatcher). */
        fun mainStationId(source: WeatherSource): String = "${source.id}_MAIN"

        /** Production wiring from the shared clients; a null client leaves its feed unserved. */
        fun of(
            openMeteo: OpenMeteoApi?,
            weatherApi: WeatherApi?,
            openWeatherMap: OpenWeatherMapApi?,
            tomorrowIo: TomorrowIoApi?,
        ): CurrentConditionsFetcher =
            CurrentConditionsFetcher(
                Calls(
                    openMeteoCurrent = openMeteo?.let { api -> { lat, lon -> api.getCurrent(lat, lon) } },
                    openMeteoCondition = { code -> openMeteo?.weatherCodeToCondition(code) ?: "Unknown" },
                    weatherApiForecast = weatherApi?.let { api -> { lat, lon -> api.getForecast(lat, lon) } },
                    openWeatherMapForecast = openWeatherMap?.let { api -> { lat, lon -> api.getForecast(lat, lon) } },
                    tomorrowIoFiveMinuteHistory = tomorrowIo?.let { api -> { lat, lon, hours -> api.getFiveMinuteHistory(lat, lon, hours) } },
                ),
            )
    }
}
