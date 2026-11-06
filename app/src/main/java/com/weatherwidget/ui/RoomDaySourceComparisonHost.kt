package com.weatherwidget.ui

import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.toDailyForecast
import com.weatherwidget.data.local.toDailyForecastSnapshot
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.DaySourceComparison
import com.weatherwidget.stats.AccuracyCalculator
import java.time.Instant
import java.time.ZoneOffset

/** Room reads for the shared [DaySourceComparison] runner (Forecast History "All sources"). */
internal class RoomDaySourceComparisonHost(
    private val forecastDao: ForecastDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val sources: List<WeatherSource>,
    /** 30-day high bias by source id; [accuracyBias] in production. */
    private val bias: suspend (List<WeatherSource>, Double, Double) -> Map<String, Double>,
) : DaySourceComparison.Host {
    override suspend fun visibleSources(): List<WeatherSource> = sources

    override suspend fun forecastRows(targetDateEpoch: Long, lat: Double, lon: Double): List<DaySourceComparison.StoredRow> {
        val date = Instant.ofEpochMilli(targetDateEpoch).atZone(ZoneOffset.UTC).toLocalDate()
        return forecastDao.getForecastEvolution(targetDateEpoch, lat, lon).map {
            DaySourceComparison.StoredRow(it.toDailyForecast(date), it.toDailyForecastSnapshot(date), it.locationLat, it.locationLon)
        }
    }

    override suspend fun dailyHistory(targetDateEpoch: Long, lat: Double, lon: Double): List<DailyHistory> =
        dailyHistoryDao.getExtremesInRange(targetDateEpoch, targetDateEpoch, lat, lon).map { it.toDailyHistory() }

    // Raw box rows on purpose: the shared runner picks the nearest site per source, the same rule
    // desktop's rows go through (HourlyProximityQueryAllowlistTest entry).
    override suspend fun hourly(startMs: Long, endMs: Long, lat: Double, lon: Double, sourceIds: List<String>): List<HourlyForecast> =
        hourlyForecastDao.getHourlyForecastsForSources(startMs, endMs, lat, lon, sourceIds).map { it.toHourlyForecast() }

    override suspend fun highBias(sources: List<WeatherSource>, lat: Double, lon: Double): Map<String, Double> =
        bias(sources, lat, lon)

    companion object {
        /** The accuracy card's 30-day comparison, high bias only, for sources with history. */
        fun accuracyBias(calculator: AccuracyCalculator): suspend (List<WeatherSource>, Double, Double) -> Map<String, Double> =
            { sources, lat, lon ->
                val comparison = calculator.calculateComparison(lat, lon, 30)
                sources.mapNotNull { source ->
                    comparison.statsFor(source)?.takeIf { it.totalForecasts > 0 }?.let { source.id to it.highBias }
                }.toMap()
            }
    }
}
