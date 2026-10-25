package com.weatherwidget.data.remote

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.nowcast.NowcastSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

/**
 * What one source's forecast fetch asks its provider for, on both platforms (user 2026-10-25;
 * `plans/261025-shared-source-fetch-table.md`). The reply is returned as the provider sent it: what it
 * becomes in the database is [com.weatherwidget.shared.util.ForecastPersistRun]'s and
 * `ObservationWriteRun`'s. A forecast reply's provider *current* reading is not filed here — current
 * readings come only from the current-conditions path (decision F1).
 *
 * NWS stays the platform's own ([Host.fetchNws]) until its observation pull (F6) is decided.
 */
class SourceFetchTable(private val host: Host) {
    /**
     * The provider calls, as functions so a test can see exactly what each source asks for. [of] wires
     * the `:shared` clients; a null call is a source this build does not provision.
     */
    class Calls(
        val openMeteo: suspend (lat: Double, lon: Double, historyDays: Int, parts: ForecastParts) -> RawFetch,
        val silurian: (suspend (lat: Double, lon: Double, parts: ForecastParts) -> RawFetch)? = null,
        val weatherApi: (suspend (lat: Double, lon: Double) -> RawFetch)? = null,
        val openWeatherMap: (suspend (lat: Double, lon: Double) -> RawFetch)? = null,
        val tomorrowIoForecast: (suspend (lat: Double, lon: Double, parts: ForecastParts) -> RawFetch)? = null,
        val tomorrowIoHistory: (suspend (lat: Double, lon: Double, lookbackHours: Int) -> RawFetch)? = null,
        val google: (suspend (lat: Double, lon: Double, request: GoogleRequest) -> RawFetch)? = null,
        val googlePagesLine: (onDemandDay: Boolean) -> String = { "" },
    ) {
        companion object {
            fun of(
                openMeteo: OpenMeteoApi,
                silurian: SilurianApi?,
                weatherApi: WeatherApi?,
                openWeatherMap: OpenWeatherMapApi?,
                tomorrowIo: TomorrowIoApi?,
                google: GoogleWeatherApi?,
            ) = Calls(
                openMeteo = { lat, lon, historyDays, parts -> openMeteo.getForecast(lat, lon, historyDays = historyDays, parts = parts) },
                silurian = silurian?.let { api -> { lat, lon, parts -> api.getForecast(lat, lon, parts) } },
                weatherApi = weatherApi?.let { api -> { lat, lon -> api.getForecast(lat, lon) } },
                openWeatherMap = openWeatherMap?.let { api -> { lat, lon -> api.getForecast(lat, lon) } },
                tomorrowIoForecast = tomorrowIo?.let { api -> { lat, lon, parts -> api.getForecast(lat, lon, parts) } },
                tomorrowIoHistory = tomorrowIo?.let { api -> { lat, lon, hours -> api.getFiveMinuteHistory(lat, lon, hours) } },
                google = google?.let { api ->
                    { lat, lon, r -> api.getForecast(lat, lon, r.includeHistory, r.storedHours, r.parts, r.hoursAhead) }
                },
                googlePagesLine = { onDemandDay -> google?.hoursPagesLine(onDemandDay).orEmpty() },
            )
        }
    }

    /** One Google forecast request's arguments. */
    data class GoogleRequest(
        val includeHistory: Boolean,
        val storedHours: List<HourlyForecast>,
        val parts: ForecastParts,
        val hoursAhead: Int,
    )

    interface Host {
        val calls: Calls

        /** NWS's forecast fetch with [parts], the platform's own until F6 is decided. */
        suspend fun fetchNws(latitude: Double, longitude: Double, parts: ForecastParts): RawFetch

        /** Google's live hours on the site's exact quantized key, `dateTime` in [startMs, endMs] (decision F2). */
        suspend fun storedGoogleHours(latitude: Double, longitude: Double, startMs: Long, endMs: Long): List<HourlyForecast>

        /** `GoogleHistoryRun.needsHistory` at this site. */
        suspend fun googleNeedsHistory(latitude: Double, longitude: Double, refillDay: LocalDate?, nowMs: Long): Boolean

        fun saveNowcast(latitude: Double, longitude: Double, series: NowcastSeries)

        suspend fun log(tag: String, message: String, level: String)
    }

    /**
     * [parts]: what the plan asked for ([ForecastParts.requested] widens it for a source that cannot
     * split). [onDemand]: a tapped day's deeper hours (only Google asks differently). [refillDay]: a past
     * day's History refresh, which needs Google's history.
     */
    data class Request(
        val parts: ForecastParts = ForecastParts.BOTH,
        val horizon: ForecastHorizonCadence.Horizon? = null,
        val onDemand: HourlyOnDemand.Request? = null,
        val refillDay: LocalDate? = null,
    )

    /** False for a source this build has no client for; its fetch is skipped, not failed. */
    fun serves(source: WeatherSource): Boolean = when (source) {
        WeatherSource.NWS, WeatherSource.OPEN_METEO -> true
        WeatherSource.SILURIAN -> host.calls.silurian != null
        WeatherSource.WEATHER_API -> host.calls.weatherApi != null
        WeatherSource.OPEN_WEATHER_MAP -> host.calls.openWeatherMap != null
        WeatherSource.TOMORROW_IO -> host.calls.tomorrowIoForecast != null
        WeatherSource.GOOGLE_WEATHER -> host.calls.google != null
        else -> false
    }

    suspend fun fetch(source: WeatherSource, latitude: Double, longitude: Double, request: Request, nowMs: Long): RawFetch {
        require(serves(source)) { "no forecast fetch for ${source.id}" }
        val parts = ForecastParts.requested(source, request.parts)
        val calls = host.calls
        return when (source) {
            WeatherSource.NWS -> host.fetchNws(latitude, longitude, parts)
            WeatherSource.OPEN_METEO -> calls.openMeteo(latitude, longitude, OPEN_METEO_HISTORY_DAYS, parts)
                // The same reply's next 3 h of 15-minute precipitation: the nowcast, for free.
                .also { raw -> raw.nowcast?.let { host.saveNowcast(latitude, longitude, it) } }
            WeatherSource.SILURIAN -> calls.silurian!!(latitude, longitude, parts)
            WeatherSource.WEATHER_API -> calls.weatherApi!!(latitude, longitude)
            WeatherSource.OPEN_WEATHER_MAP -> calls.openWeatherMap!!(latitude, longitude)
            WeatherSource.TOMORROW_IO -> tomorrowIo(calls, latitude, longitude, parts)
            WeatherSource.GOOGLE_WEATHER -> google(calls, latitude, longitude, parts, request, nowMs)
            else -> error("no forecast fetch for ${source.id}")
        }
    }

    /** Forecast and five-minute history in parallel; history only with the daily part, and its failure keeps the forecast. */
    private suspend fun tomorrowIo(calls: Calls, latitude: Double, longitude: Double, parts: ForecastParts): RawFetch = coroutineScope {
        val forecast = async { calls.tomorrowIoForecast!!(latitude, longitude, parts) }
        val history = async {
            // Actuals: on the daily cadence, not the hourly one.
            if (!parts.daily) return@async null
            try {
                calls.tomorrowIoHistory?.invoke(latitude, longitude, TomorrowIoApi.FULL_ACTUALS_LOOKBACK_HOURS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.log(TMRW_HISTORY_FAIL_TAG, "full history unavailable; retaining cached actuals: ${e.javaClass.simpleName}", "WARN")
                null
            }
        }
        val reply = forecast.await()
        val fiveMinute = history.await() ?: return@coroutineScope reply
        reply.copy(
            subHourly = fiveMinute.subHourly,
            providerCurrentTemp = fiveMinute.providerCurrentTemp,
            providerCurrentCondition = fiveMinute.providerCurrentCondition,
            providerCurrentObservedAt = fiveMinute.providerCurrentObservedAt,
            providerCurrentCloudCover = fiveMinute.providerCurrentCloudCover,
        )
    }

    private suspend fun google(
        calls: Calls,
        latitude: Double,
        longitude: Double,
        parts: ForecastParts,
        request: Request,
        nowMs: Long,
    ): RawFetch {
        val onDemand = request.onDemand?.takeIf { it.sourceId == WeatherSource.GOOGLE_WEATHER.id }
        val google = GoogleRequest(
            // An on-demand day needs no elapsed hours; history/hours has a 20/day quota.
            includeHistory = onDemand == null && host.googleNeedsHistory(latitude, longitude, request.refillDay, nowMs),
            storedHours = host.storedGoogleHours(
                LocationMatch.quantize(latitude),
                LocationMatch.quantize(longitude),
                nowMs - HOUR_MS,
                nowMs + (GoogleWeatherApi.FORECAST_HOURS + 2) * HOUR_MS,
            ),
            parts = parts,
            hoursAhead = HourlyOnDemand.askHours(WeatherSource.GOOGLE_WEATHER.id, onDemand, request.horizon),
        )
        return calls.google!!(latitude, longitude, google)
            .also { host.log(GoogleWeatherApi.HOURS_PAGES_TAG, calls.googlePagesLine(onDemand != null), "INFO") }
    }

    companion object {
        /** Open-Meteo's `past_days`: the provider history its actuals come from. */
        const val OPEN_METEO_HISTORY_DAYS = 7

        /** A Tomorrow.io five-minute history request that failed during a forecast fetch. */
        const val TMRW_HISTORY_FAIL_TAG = "FETCH_TMRW_5M_FAIL"

        private const val HOUR_MS = 3_600_000L
    }
}
