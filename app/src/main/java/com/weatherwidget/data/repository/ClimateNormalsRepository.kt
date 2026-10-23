package com.weatherwidget.data.repository

import com.weatherwidget.shared.util.ClimateNormalsRun
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.CancellationException
import java.time.MonthDay

/**
 * Android host of `:shared` [ClimateNormalsRun] (cache, Open-Meteo gate, fetch, reduce, logs — the
 * same run as desktop); this reads and writes `climate_normals`.
 */
internal class ClimateNormalsRepository(
    private val climateNormalDao: ClimateNormalDao,
    private val openMeteoApi: OpenMeteoApi,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
) {
    private fun host(latitude: Double, longitude: Double) = object : ClimateNormalsRun.Host {
        override suspend fun cached(locationKey: String): Pair<Map<Int, Float>, Map<Int, Float>> {
            val rows = climateNormalDao.getNormalsForLocation(locationKey)
            return rows.associate { it.monthDay.take(2).toInt() to it.highTemp } to
                rows.associate { it.monthDay.take(2).toInt() to it.lowTemp }
        }

        override val openMeteoEnabled get() = widgetStateManager.isSourceVisible(WeatherSource.OPEN_METEO)

        override suspend fun fetchDailyTemps(start: String, end: String) =
            openMeteoApi.getHistoricalDailyTemps(latitude, longitude, start, end)

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
