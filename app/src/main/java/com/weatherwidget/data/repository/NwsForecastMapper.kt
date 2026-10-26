package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsForecastReply
import javax.inject.Inject
import javax.inject.Singleton

/** Android's handle on `:shared` [NwsForecastReply], the NWS forecast fetch desktop runs too. */
@Singleton
class NwsForecastMapper @Inject constructor(
    private val nwsApi: NwsApi,
    private val appLogDao: AppLogDao,
) {
    suspend fun fetchFromNws(
        latitude: Double,
        longitude: Double,
        parts: ForecastParts = ForecastParts.BOTH,
    ): RawFetch = NwsForecastReply.fetch(nwsApi, latitude, longitude, parts, log = { tag, message, level -> appLogDao.log(tag, message, level) })
}
