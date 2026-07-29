package com.weatherwidget.data.repository

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
 * Owns climate-normal cache lookup, historical fetch, aggregation, and persistence.
 */
internal class ClimateNormalsRepository(
    private val climateNormalDao: ClimateNormalDao,
    private val openMeteoApi: OpenMeteoApi,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
) {
    suspend fun getHistoricalNormalsByMonthDay(
        latitude: Double,
        longitude: Double,
    ): Map<MonthDay, Pair<Float, Float>> {
        val locationKey = ClimateNormals.locationKey(latitude, longitude)
        val cachedNormals = climateNormalDao.getNormalsForLocation(locationKey)
        if (cachedNormals.isNotEmpty()) {
            val monthlyHigh = cachedNormals.associate {
                it.monthDay.take(2).toInt() to it.highTemp
            }
            val monthlyLow = cachedNormals.associate {
                it.monthDay.take(2).toInt() to it.lowTemp
            }
            return ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)
        }
        if (!widgetStateManager.isSourceVisible(WeatherSource.OPEN_METEO)) {
            appLogDao.log(
                "CLIMATE_SKIP_DISABLED",
                "source=${WeatherSource.OPEN_METEO.id}",
            )
            return emptyMap()
        }

        val (startDate, endDate) = ClimateNormals.rollingWindow()
        val dailyTemps = openMeteoApi.getHistoricalDailyTemps(
            latitude,
            longitude,
            startDate,
            endDate,
        )
        val (monthlyHigh, monthlyLow) = ClimateNormals.monthlyMeans(dailyTemps)
        if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) {
            appLogDao.log(
                "CLIMATE_FETCH_EMPTY",
                "lat=$latitude lon=$longitude rows=${dailyTemps.size}",
            )
            return emptyMap()
        }
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
        return ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)
    }

    suspend fun warmBestEffort(
        latitude: Double,
        longitude: Double,
    ) {
        try {
            getHistoricalNormalsByMonthDay(latitude, longitude)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            appLogDao.logException(
                "CLIMATE_WARM_FAIL",
                "Climate-normal cache warm failed for " +
                    "lat=$latitude lon=$longitude",
                exception,
            )
        }
    }
}
