package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.SourceFetchTable
import com.weatherwidget.shared.nowcast.NowcastSeries
import com.weatherwidget.shared.nowcast.NowcastStore
import java.time.LocalDate

/**
 * Desktop side of [SourceFetchTable]: Google's stored hours on the site's exact key, as Android's
 * `RoomSourceFetchHost`; pinned to it by `SourceFetchTableHostContract`. It used the same-site tolerance
 * box, which counted a jitter fragment's hours as this site's (decision F2). Without a database (tests,
 * the UI process) nothing is stored, so Google fetches every page.
 */
internal class DesktopSourceFetchHost(
    override val calls: SourceFetchTable.Calls,
    private val weatherDao: DesktopWeatherDao?,
    private val needsHistory: suspend (refillDay: LocalDate?) -> Boolean,
    private val nowcastStore: NowcastStore?,
) : SourceFetchTable.Host {
    override suspend fun storedGoogleHours(latitude: Double, longitude: Double, startMs: Long, endMs: Long): List<HourlyForecast> =
        weatherDao?.getHourlyForecasts(latitude, longitude, WeatherSource.GOOGLE_WEATHER.id, startMs, endMs)
            ?.filter { it.locationLat == latitude && it.locationLon == longitude }
            .orEmpty()

    override suspend fun googleNeedsHistory(latitude: Double, longitude: Double, refillDay: LocalDate?, nowMs: Long) = needsHistory(refillDay)

    override fun saveNowcast(latitude: Double, longitude: Double, series: NowcastSeries) {
        nowcastStore?.saveSeries(latitude, longitude, series)
    }

    override suspend fun log(tag: String, message: String, level: String) {
        weatherDao?.log(tag, message, level)
    }
}
