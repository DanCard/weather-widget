package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.widget.WidgetConstants
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists Open-Meteo `past_days` daily values as `apiHighTemp`/`apiLowTemp` in `daily_history`.
 * Extracted from [DailyActualsStore] so that store only owns the live-today read path and the blend
 * recompute; the only caller is the Open-Meteo fetch path in [ForecastFetchCoordinator].
 */
@Singleton
class OpenMeteoPastDayActualsWriter @Inject constructor(
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
) {
    /**
     * When [DailyForecast] rows carry a `past_days` window, the /forecast response includes
     * ERA5-based observed values for past dates in the same daily array — these are NOT forecasts.
     */
    suspend fun persistOpenMeteoPastDayActuals(
        latitude: Double,
        longitude: Double,
        dailyForecasts: List<DailyForecast>,
    ) {
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        val pastForecasts = dailyForecasts.filter { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
            date != null && date.isBefore(today)
        }
        if (pastForecasts.isEmpty()) return

        val minDate = pastForecasts.minOf { LocalDate.parse(it.date) }
        val maxDate = pastForecasts.maxOf { LocalDate.parse(it.date) }
        val existing = dailyHistoryDao
            .getExtremesInRange(
                minDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                maxDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                keyLat,
                keyLon,
            )
            .filter { it.source == WeatherSource.OPEN_METEO.id }
        val existingByDate = existing.groupBy { it.date }
            .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }

        val toUpsert = mutableListOf<DailyHistoryEntity>()
        for (day in pastForecasts) {
            val dateEpoch = LocalDate.parse(day.date).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val existingRow = existingByDate[dateEpoch]
            toUpsert.add(
                DailyHistoryEntity(
                    date = dateEpoch,
                    source = WeatherSource.OPEN_METEO.id,
                    locationLat = keyLat,
                    locationLon = keyLon,
                    computedHighTemp = existingRow?.computedHighTemp ?: day.highTemp,
                    computedLowTemp = existingRow?.computedLowTemp ?: day.lowTemp,
                    condition = existingRow?.condition ?: day.condition,
                    updatedAt = now,
                    precipAmountMm = existingRow?.precipAmountMm,
                    precipDayMm = existingRow?.precipDayMm,
                    precipNightMm = existingRow?.precipNightMm,
                    forecastDayPrecipChance = existingRow?.forecastDayPrecipChance,
                    forecastNightPrecipChance = existingRow?.forecastNightPrecipChance,
                    forecastHighTemp = existingRow?.forecastHighTemp,
                    forecastLowTemp = existingRow?.forecastLowTemp,
                    forecastPrecipAmountMm = existingRow?.forecastPrecipAmountMm,
                    noonCloudPercent = existingRow?.noonCloudPercent,
                    apiHighTemp = day.highTemp,
                    apiLowTemp = day.lowTemp,
                    lastWriter = DailyHistoryWriter.OPEN_METEO_PAST_DAYS.storedValue,
                ),
            )
        }

        if (toUpsert.isNotEmpty()) {
            dailyHistoryDao.insertAll(toUpsert)
            appLogDao.log(
                "METEO_PAST_ACTUALS",
                "dates=${toUpsert.size} min=$minDate max=$maxDate",
                "DEBUG",
            )
        }
    }
}
