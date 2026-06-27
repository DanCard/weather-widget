package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetActions
import java.time.LocalDate
import java.time.LocalDateTime

internal object DailyClickHandlerFactory {



    @VisibleForTesting
    internal fun buildDayClickIntent(
        context: Context, appWidgetId: Int, dayIndex: Int, date: LocalDate,
        iconRes: Int?, lat: Double, lon: Double,
        displaySource: WeatherSource,
        now: LocalDateTime = LocalDateTime.now(),
        targetModeOverride: ViewMode? = null,
        offsetOverride: Int? = null,
        clickSource: String? = null,
        precipProbability: Int? = null,
    ): Intent {
        val isHistory = date.isBefore(now.toLocalDate())
        val showHistory = DayClickHelper.shouldShowHistory(isHistory)

        return Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetActions.ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("date", date.toString())
            putExtra("isHistory", isHistory)
            putExtra("showHistory", showHistory)
            putExtra("index", dayIndex)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
            clickSource?.let { putExtra(WidgetActions.EXTRA_CLICK_SOURCE, it) }

            if (!showHistory) {
                val targetMode = targetModeOverride
                    ?: if (isHistory) ViewMode.TEMPERATURE
                    else DayClickHelper.resolveDailyTargetViewMode(iconRes, precipProbability)
                val offset = offsetOverride ?: DayClickHelper.calculatePrecipitationOffset(now, date)
                putExtra(WidgetActions.EXTRA_TARGET_VIEW, targetMode.name)
                putExtra(WidgetActions.EXTRA_HOURLY_OFFSET, offset)
            }
        }
    }

    @VisibleForTesting
    internal fun setupGraphDayClickHandlers(
        context: Context, views: RemoteViews, appWidgetId: Int, now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>, lat: Double, lon: Double, displaySource: WeatherSource,
        numColumns: Int
    ) {
        val zoneIds = listOf(
            R.id.graph_day1_zone, R.id.graph_day2_zone, R.id.graph_day3_zone, R.id.graph_day4_zone,
            R.id.graph_day5_zone, R.id.graph_day6_zone, R.id.graph_day7_zone, R.id.graph_day8_zone,
            R.id.graph_day9_zone, R.id.graph_day10_zone
        )
        setupGraphZoneClickHandlers(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            now = now,
            days = days,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            numColumns = numColumns,
            zoneIds = zoneIds,
            requestCodeOffset = 0,
        )
    }

    @VisibleForTesting
    internal fun setupGraphBottomDayClickHandlers(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        numColumns: Int,
    ) {
        val zoneIds = listOf(
            R.id.graph_bottom_day1_zone, R.id.graph_bottom_day2_zone, R.id.graph_bottom_day3_zone, R.id.graph_bottom_day4_zone,
            R.id.graph_bottom_day5_zone, R.id.graph_bottom_day6_zone, R.id.graph_bottom_day7_zone, R.id.graph_bottom_day8_zone,
            R.id.graph_bottom_day9_zone, R.id.graph_bottom_day10_zone,
        )
        setupGraphZoneClickHandlers(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            now = now,
            days = days,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            numColumns = numColumns,
            zoneIds = zoneIds,
            requestCodeOffset = 100,
            resolveTargetMode = { iconRes -> DayClickHelper.resolveBottomRowTargetViewMode(iconRes) },
        )
    }

    internal fun setupGraphZoneClickHandlers(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        numColumns: Int,
        zoneIds: List<Int>,
        requestCodeOffset: Int = 0,
        resolveTargetMode: ((Int?) -> ViewMode)? = null,
    ) {
        for (i in zoneIds.indices) {
            val zoneId = zoneIds[i]
            if (i < numColumns) {
                views.setViewVisibility(zoneId, View.VISIBLE)
                views.setOnClickPendingIntent(zoneId, null)
            } else {
                views.setViewVisibility(zoneId, View.GONE)
            }
        }

        days.forEachIndexed { index, dayData ->
            val colIndex = dayData.columnIndex ?: index
            val zoneId = zoneIds.getOrNull(colIndex) ?: return@forEachIndexed
            val targetModeOverride = resolveTargetMode?.invoke(dayData.iconRes)
            val intent = buildDayClickIntent(
                context = context,
                appWidgetId = appWidgetId,
                dayIndex = colIndex + 1,
                date = dayData.date,
                iconRes = dayData.iconRes,
                lat = lat,
                lon = lon,
                displaySource = displaySource,
                now = now,
                targetModeOverride = targetModeOverride,
                clickSource = if (requestCodeOffset == 0) {
                    "graph_day:col=$colIndex:date=${dayData.date}"
                } else {
                    "graph_bottom_day:col=$colIndex:date=${dayData.date}"
                },
                precipProbability = dayData.rainData.dailyPrecipProbability,
            )
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.graphClick(appWidgetId, colIndex + requestCodeOffset),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(zoneId, pendingIntent)
        }
    }
}
