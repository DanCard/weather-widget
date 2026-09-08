package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.DailyForecastGraphRenderer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats and builds provenance reports and diagnostic messages for today's high temperature,
 * station extremes, and local observation spans. Extracted from [DailyGraphRenderer].
 */
object DailyTodayProvenanceReporter {

    fun buildTodayHighProvenanceMessage(
        appWidgetId: Int,
        today: LocalDate,
        displaySource: WeatherSource,
        forecastWeather: ForecastEntity?,
        dailyActual: DailyHistory?,
        todayDay: DailyForecastGraphRenderer.DayData,
        currentTemp: Float?,
        observedAt: Long?,
        observations: List<ObservationEntity>,
    ): String {
        val stationMaxes = formatStationMaxes(observations)
        val obsSpan = formatObservationSpan(observations)
        return "widget=$appWidgetId date=$today source=${displaySource.id} " +
            "forecastHigh=${formatTempValue(forecastWeather?.highTemp)} forecastLow=${formatTempValue(forecastWeather?.lowTemp)} " +
            "dailyActualHigh=${formatTempValue(dailyActual?.computedHighTemp)} dailyActualLow=${formatTempValue(dailyActual?.computedLowTemp)} " +
            "currentTemp=${formatTempValue(currentTemp)} observedAt=${formatLocalTime(observedAt)} " +
            "graphObservedHigh=${formatTempValue(todayDay.solidLineHigh)} graphObservedLow=${formatTempValue(todayDay.solidLineLow)} " +
            "graphForecastHigh=${formatTempValue(todayDay.dashedLineHigh)} graphForecastLow=${formatTempValue(todayDay.dashedLineLow)} " +
            "graphGhostHigh=${formatTempValue(todayDay.ghostLineHigh)} graphSnapshotHigh=${formatTempValue(todayDay.snapshotHigh)} " +
            "obsRows=${observations.size} obsSpan=$obsSpan stationMaxes=[$stationMaxes]"
    }

    fun formatStationMaxes(observations: List<ObservationEntity>): String {
        if (observations.isEmpty()) return "none"
        return observations
            .groupBy { it.stationId }
            .mapNotNull { (stationId, rows) ->
                val maxRow = rows.maxByOrNull { it.temperature } ?: return@mapNotNull null
                val minRow = rows.minByOrNull { it.temperature }
                StationExtremeSummary(
                    stationId = stationId,
                    maxTemp = maxRow.temperature,
                    maxAt = maxRow.timestamp,
                    minTemp = minRow?.temperature,
                    distanceKm = maxRow.distanceKm,
                    rowCount = rows.size,
                )
            }
            .sortedWith(compareByDescending<StationExtremeSummary> { it.maxTemp }.thenBy { it.distanceKm })
            .take(6)
            .joinToString("|") { summary ->
                "${summary.stationId}(max=${formatTempValue(summary.maxTemp)}@${formatLocalTime(summary.maxAt)}," +
                    "min=${formatTempValue(summary.minTemp)},n=${summary.rowCount},d=${formatDistance(summary.distanceKm)}km)"
            }
    }

    private data class StationExtremeSummary(
        val stationId: String,
        val maxTemp: Float,
        val maxAt: Long,
        val minTemp: Float?,
        val distanceKm: Float,
        val rowCount: Int,
    )

    fun formatObservationSpan(observations: List<ObservationEntity>): String {
        if (observations.isEmpty()) return "none"
        return "${formatLocalTime(observations.minOf { it.timestamp })}..${formatLocalTime(observations.maxOf { it.timestamp })}"
    }

    fun formatTempValue(value: Float?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: "null"

    fun formatDistance(value: Float): String =
        String.format(Locale.US, "%.2f", value)

    fun formatLocalTime(timestampMs: Long?): String {
        if (timestampMs == null) return "null"
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
        return Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(formatter)
    }
}
