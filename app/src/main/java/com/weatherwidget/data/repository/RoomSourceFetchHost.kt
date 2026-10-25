package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.SourceFetchTable
import com.weatherwidget.shared.nowcast.NowcastSeries
import java.time.LocalDate

/**
 * Room side of [SourceFetchTable]: Google's stored hours on the site's exact key, as desktop's
 * `DesktopSourceFetchHost`; pinned to it by `SourceFetchTableHostContract` (`:shared` testFixtures).
 */
internal class RoomSourceFetchHost(
    override val calls: SourceFetchTable.Calls,
    private val hourlyStore: HourlyForecastStore,
    private val appLogDao: AppLogDao,
    private val nws: suspend (latitude: Double, longitude: Double, parts: ForecastParts) -> RawFetch,
    private val needsHistory: suspend (latitude: Double, longitude: Double, refillDay: LocalDate?) -> Boolean,
    private val nowcast: (latitude: Double, longitude: Double, series: NowcastSeries) -> Unit,
) : SourceFetchTable.Host {
    override suspend fun fetchNws(latitude: Double, longitude: Double, parts: ForecastParts) = nws(latitude, longitude, parts)

    override suspend fun storedGoogleHours(latitude: Double, longitude: Double, startMs: Long, endMs: Long) =
        hourlyStore.storedHourlyForSite(latitude, longitude, WeatherSource.GOOGLE_WEATHER.id, startMs, endMs)

    override suspend fun googleNeedsHistory(latitude: Double, longitude: Double, refillDay: LocalDate?, nowMs: Long) =
        needsHistory(latitude, longitude, refillDay)

    override fun saveNowcast(latitude: Double, longitude: Double, series: NowcastSeries) = nowcast(latitude, longitude, series)

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
}
