package com.weatherwidget.data.repository

import com.weatherwidget.shared.util.ClimateNormalsRun
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.shared.util.ClimateNormals
import kotlinx.coroutines.CancellationException
import java.time.MonthDay

/**
 * Android host of `:shared` [ClimateNormalsRun] (cache, fetch, reduce, logs — the
 * same run as desktop); this reads and writes `climate_normals`.
 */
internal class ClimateNormalsRepository(
    private val climateNormalDao: ClimateNormalDao,
    private val openMeteoApi: OpenMeteoApi,
    private val appLogDao: AppLogDao,
) {
    private fun host(latitude: Double, longitude: Double) =
        RoomClimateNormalsHost(climateNormalDao, appLogDao) { start, end ->
            openMeteoApi.getHistoricalDailyTemps(latitude, longitude, start, end)
        }

    suspend fun getHistoricalNormalsByMonthDay(
        latitude: Double,
        longitude: Double,
    ): Map<MonthDay, Pair<Float, Float>> = ClimateNormalsRun.run(host(latitude, longitude), latitude, longitude).byMonthDay

    suspend fun warmBestEffort(
        latitude: Double,
        longitude: Double,
    ) {
        ClimateNormalsRun.warm(host(latitude, longitude), latitude, longitude)
    }
}

/**
 * Room side of [ClimateNormalsRun]: reads and writes `climate_normals`. Pinned to desktop's SQLite
 * Host by `ClimateNormalsHostContract` (`:shared` testFixtures).
 */
internal class RoomClimateNormalsHost(
    private val climateNormalDao: ClimateNormalDao,
    private val appLogDao: AppLogDao,
    private val fetch: suspend (start: String, end: String) -> List<DailyForecast>,
) : ClimateNormalsRun.Host {
    override suspend fun cached(locationKey: String): Pair<Map<Int, Float>, Map<Int, Float>> {
        val rows = climateNormalDao.getNormalsForLocation(locationKey)
        return rows.associate { it.monthDay.take(2).toInt() to it.highTemp } to
            rows.associate { it.monthDay.take(2).toInt() to it.lowTemp }
    }

    override suspend fun fetchDailyTemps(start: String, end: String) = fetch(start, end)

    override suspend fun store(locationKey: String, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>) {
        climateNormalDao.deleteOtherLocations(locationKey)
        climateNormalDao.insertAll(
            (1..12).mapNotNull { month ->
                val high = monthlyHigh[month] ?: return@mapNotNull null
                val low = monthlyLow[month] ?: return@mapNotNull null
                ClimateNormalEntity(
                    monthDay = "${month.toString().padStart(2, '0')}-15",
                    locationKey = locationKey,
                    highTemp = high,
                    lowTemp = low,
                )
            },
        )
    }

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
}
