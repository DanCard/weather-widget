package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Diagnostics logger for daily view rendering.
 * Extracted from [DailyViewHandler].
 */
internal object DailyRenderLogger {

    suspend fun logDailyRenderSummary(
        appLogDao: AppLogDao,
        appWidgetId: Int,
        dateOffset: Int,
        displaySource: WeatherSource,
        numColumns: Int,
        numRows: Int,
        useGraph: Boolean,
        skipYesterday: Boolean,
        centerDate: LocalDate,
        visibleDates: List<LocalDate>,
        cloudDays: List<CloudCoverDiagnosticRow>? = null,
        hourlyForecasts: List<HourlyForecastEntity>? = null,
    ) {
        val mode = if (useGraph) "GRAPH" else "TEXT"
        val datesSummary = visibleDates.joinToString(",").ifEmpty { "<none>" }
        val tag = if (visibleDates.isEmpty()) DailyViewHandler.LOG_TAG_DAILY_RENDER_EMPTY else DailyViewHandler.LOG_TAG_DAILY_RENDER
        val cloudSummary = if (cloudDays != null) {
            " " + buildCloudCoverDiagnostic(cloudDays, hourlyForecasts, displaySource)
        } else ""
        appLogDao.log(
            tag,
            "widget=$appWidgetId mode=$mode offset=$dateOffset cols=$numColumns rows=$numRows skipYesterday=$skipYesterday center=$centerDate source=${displaySource.id} days=${visibleDates.size} dates=$datesSummary$cloudSummary"
        )
    }

    fun buildCloudCoverDiagnostic(
        cloudDays: List<CloudCoverDiagnosticRow>,
        hourlyForecasts: List<HourlyForecastEntity>?,
        displaySource: WeatherSource,
    ): String {
        val resolved = cloudDays.count { it.cloudCoverRatioOverride != null }
        val missing = cloudDays.filter { it.cloudCoverRatioOverride == null }
            .map { "${it.date}(d${it.daysFromToday})" }
        val missingStr = if (missing.isEmpty()) "-" else missing.joinToString(",")

        val zone = ZoneId.systemDefault()
        val sourceRows = hourlyForecasts
            ?.filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
            ?: emptyList()
        val withCloud = sourceRows.count { it.cloudCover != null }
        val dates = sourceRows.asSequence()
            .map { Instant.ofEpochMilli(it.dateTime).atZone(zone).toLocalDate() }
            .toList()
        val window = if (dates.isEmpty()) "none" else "${dates.min()}..${dates.max()}"

        return "cloud=$resolved/${cloudDays.size} cloudMissing=$missingStr " +
            "hourlyRows=${sourceRows.size} hourlyWithCloud=$withCloud hourlyWindow=$window"
    }
}
