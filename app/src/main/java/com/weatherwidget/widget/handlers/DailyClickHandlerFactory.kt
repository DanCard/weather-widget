package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DayClickResolver
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActionReceiver
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
        precipProbability: Int?,
    ): Intent = buildDayClickIntent(
        context = context,
        appWidgetId = appWidgetId,
        dayIndex = dayIndex,
        date = date,
        iconRes = iconRes,
        lat = lat,
        lon = lon,
        displaySource = displaySource,
        now = now,
        targetModeOverride = targetModeOverride,
        offsetOverride = offsetOverride,
        clickSource = clickSource,
        routingPrecip = DayClickResolver.RoutingPrecip(
            probability = precipProbability,
            gateSource = DayClickResolver.PrecipGateSource.DAILY,
        ),
    )

    @VisibleForTesting
    internal fun buildDayClickIntent(
        context: Context, appWidgetId: Int, dayIndex: Int, date: LocalDate,
        iconRes: Int?, lat: Double, lon: Double,
        displaySource: WeatherSource,
        now: LocalDateTime = LocalDateTime.now(),
        targetModeOverride: ViewMode? = null,
        offsetOverride: Int? = null,
        clickSource: String? = null,
        routingPrecip: DayClickResolver.RoutingPrecip =
            DayClickResolver.RoutingPrecip(null, DayClickResolver.PrecipGateSource.DAILY),
    ): Intent {
        val isHistory = date.isBefore(now.toLocalDate())

        return Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("date", date.toString())
            putExtra("isHistory", isHistory)
            // Day-column taps always route to the hourly graph, never history. The explicit false
            // keeps the intent contract unambiguous: the coordinator only honors an explicit true
            // from the dedicated forecast-history shortcut.
            putExtra("showHistory", false)
            putExtra("index", dayIndex)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
            clickSource?.let { putExtra(WidgetActions.EXTRA_CLICK_SOURCE, it) }
            putExtra(WidgetActions.EXTRA_PRECIP_GATE, routingPrecip.auditText())

            val targetMode = targetModeOverride
                ?: if (isHistory) ViewMode.TEMPERATURE
                else DayClickHelper.resolveDailyTargetViewMode(iconRes, routingPrecip.probability)
            val offset = offsetOverride ?: DayClickHelper.calculatePrecipitationOffset(now, date)
            putExtra(WidgetActions.EXTRA_TARGET_VIEW, targetMode.name)
            putExtra(WidgetActions.EXTRA_HOURLY_OFFSET, offset)
        }
    }

    @VisibleForTesting
    internal fun setupGraphDayClickHandlers(
        context: Context, views: RemoteViews, appWidgetId: Int, now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>, lat: Double, lon: Double, displaySource: WeatherSource,
        numColumns: Int,
        useLargeTodayOverlay: Boolean = false,
        hourlyForecasts: List<HourlyForecastEntity> = emptyList(),
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
            useLargeTodayOverlay = useLargeTodayOverlay,
            hourlyForecasts = hourlyForecasts,
        )
        setupGraphUpperDayClickHandlers(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            now = now,
            days = days,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            numColumns = numColumns,
            useLargeTodayOverlay = useLargeTodayOverlay,
            hourlyForecasts = hourlyForecasts,
        )
    }

    /**
     * The half of each day column above the nav chevrons: always the temperature graph, whatever the
     * day's icon or rain chance says. Shares [setupGraphZoneClickHandlers] with the two conditional
     * rows so column spans, Today's double-width slot and visibility stay in lockstep with them —
     * only the resolved destination differs.
     */
    @VisibleForTesting
    internal fun setupGraphUpperDayClickHandlers(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        numColumns: Int,
        useLargeTodayOverlay: Boolean = false,
        hourlyForecasts: List<HourlyForecastEntity> = emptyList(),
    ) {
        val zoneIds = listOf(
            R.id.graph_day1_top_zone, R.id.graph_day2_top_zone, R.id.graph_day3_top_zone,
            R.id.graph_day4_top_zone, R.id.graph_day5_top_zone, R.id.graph_day6_top_zone,
            R.id.graph_day7_top_zone, R.id.graph_day8_top_zone, R.id.graph_day9_top_zone,
            R.id.graph_day10_top_zone,
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
            requestCodeOffset = 200,
            useLargeTodayOverlay = useLargeTodayOverlay,
            hourlyForecasts = hourlyForecasts,
            resolveTargetMode = { DayClickHelper.resolveUpperColumnTargetViewMode() },
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
        useLargeTodayOverlay: Boolean = false,
        hourlyForecasts: List<HourlyForecastEntity> = emptyList(),
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
            useLargeTodayOverlay = useLargeTodayOverlay,
            hourlyForecasts = hourlyForecasts,
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
        useLargeTodayOverlay: Boolean = false,
        hourlyForecasts: List<HourlyForecastEntity> = emptyList(),
    ) {
        // Mapped once per render, not once per column: the shared model conversion is pure and the
        // routing gate below asks the same question of the same rows for every day.
        val sharedHourly = hourlyForecasts.map { it.toHourlyForecast() }
        val slots =
            DailyLargeTodayOverlayPolicy.slots(
                columnIndices = days.mapIndexed { index, day -> day.columnIndex ?: index },
                todayFlags = days.map { it.isToday },
                enabled = useLargeTodayOverlay,
            )
        val visibleSlotCount =
            numColumns +
                if (useLargeTodayOverlay && days.any { it.isToday }) {
                    DailyLargeTodayOverlayPolicy.TODAY_SLOT_SPAN - 1
                } else {
                    0
                }
        for (i in zoneIds.indices) {
            val zoneId = zoneIds[i]
            if (i < visibleSlotCount) {
                views.setViewVisibility(zoneId, View.VISIBLE)
                views.setOnClickPendingIntent(zoneId, null)
            } else {
                views.setViewVisibility(zoneId, View.GONE)
            }
        }

        days.forEachIndexed { index, dayData ->
            val colIndex = dayData.columnIndex ?: index
            val slot = slots.getOrNull(index) ?: return@forEachIndexed
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
                clickSource = when (requestCodeOffset) {
                    0 -> "graph_day:col=$colIndex:date=${dayData.date}"
                    200 -> "graph_day_upper:col=$colIndex:date=${dayData.date}"
                    else -> "graph_bottom_day:col=$colIndex:date=${dayData.date}"
                },
                routingPrecip = DayClickResolver.routingPrecipProbability(
                    targetDay = dayData.date,
                    now = now,
                    hourly = sharedHourly,
                    displaySourceId = displaySource.id,
                    fallbackSourceId = WeatherSource.GENERIC_GAP.id,
                    dailyProbability = dayData.rainData.dailyPrecipProbability,
                ),
            )
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.graphClick(appWidgetId, colIndex + requestCodeOffset),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            repeat(slot.span) { slotOffset ->
                zoneIds.getOrNull(slot.start + slotOffset)?.let { zoneId ->
                    views.setOnClickPendingIntent(zoneId, pendingIntent)
                }
            }
        }
    }
}
