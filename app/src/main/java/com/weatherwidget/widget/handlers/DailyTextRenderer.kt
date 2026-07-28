package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Text-mode (1-row) daily rendering, extracted from `DailyViewHandler`.
 *
 * Owns the day-cell binding (label, icon, high/low, rain summary) and the text-mode
 * root/content padding. The data shape (`DailyViewHandler.DailyRenderContext`,
 * `DailyViewLogic.TextDayData`) stays put — only the binding logic moves here.
 */
internal object DailyTextRenderer {
    private const val TAG = "DailyTextRenderer"
    internal const val LOG_TAG_TODAY_BAR_DEBUG = "TODAY_BAR_DEBUG"

    // Text-mode padding (dp). Root padding is asymmetric: left edge gets a small inset to
    // keep labels off the widget border; right edge depends on icon-width vs not (text-mode
    // reserves extra right padding so the 8th column has breathing room on wide widgets).
    private const val TEXT_MODE_ROOT_LEFT_PADDING_DP = 2
    private const val TEXT_MODE_ROOT_TOP_PADDING_DP = 0
    private const val TEXT_MODE_ROOT_RIGHT_PADDING_DP = 8
    private const val TEXT_MODE_ROOT_BOTTOM_PADDING_DP = 0
    private const val TEXT_MODE_CONTENT_RIGHT_PADDING_DP = 18

    internal data class DayIds(
        val container: Int,
        val label: Int,
        val icon: Int,
        val high: Int,
        val low: Int,
        val rain: Int,
    )

    /**
     * Renders the text-mode (single-row) daily layout: sets view visibilities, applies
     * widget-root and text-container paddings, populates visible day cells, and writes
     * the per-render summary to app_logs. Returns the list of visible day data so the
     * caller (DailyViewHandler.updateWidget) can drive missing-data refreshes.
     */
    internal suspend fun render(
        ctx: DailyViewHandler.DailyRenderContext,
    ): List<DailyViewLogic.TextDayData> {
        DailyVisibilityManager.setTextModeViews(ctx.views)

        val rootRightPaddingDp = if (ctx.isIconWidth) TEXT_MODE_ROOT_LEFT_PADDING_DP else TEXT_MODE_ROOT_RIGHT_PADDING_DP
        val contentRightPaddingDp = if (ctx.isIconWidth) 0 else TEXT_MODE_CONTENT_RIGHT_PADDING_DP
        ctx.views.setViewPadding(
            R.id.widget_root,
            WidgetSizeCalculator.dpToPx(ctx.context, TEXT_MODE_ROOT_LEFT_PADDING_DP),
            WidgetSizeCalculator.dpToPx(ctx.context, TEXT_MODE_ROOT_TOP_PADDING_DP),
            WidgetSizeCalculator.dpToPx(ctx.context, rootRightPaddingDp),
            WidgetSizeCalculator.dpToPx(ctx.context, TEXT_MODE_ROOT_BOTTOM_PADDING_DP),
        )
        val rightPaddingPx = WidgetSizeCalculator.dpToPx(ctx.context, contentRightPaddingDp)
        ctx.views.setViewPadding(R.id.text_container, 0, 0, rightPaddingPx, 0)

        val visibleDaysInfo = updateCells(ctx)

        visibleDaysInfo.find { it.isToday }?.let { todayDay ->
            Log.v(TAG, "$LOG_TAG_TODAY_BAR_DEBUG widget=${ctx.appWidgetId} mode=TEXT high=${todayDay.highLabel} low=${todayDay.lowLabel} " +
                "fallback=${todayDay.isTodayForecastFallback}")
        }

        DailyViewHandler.logDailyRenderSummary(
            appLogDao = ctx.appLogDao,
            appWidgetId = ctx.appWidgetId,
            dateOffset = ctx.dateOffset,
            displaySource = ctx.displaySource,
            numColumns = ctx.numColumns,
            numRows = ctx.numRows,
            useGraph = false,
            skipYesterday = ctx.skipYesterday,
            centerDate = ctx.centerDate,
            visibleDates = visibleDaysInfo.map { it.date },
        )
        return visibleDaysInfo
    }

    private fun updateCells(
        ctx: DailyViewHandler.DailyRenderContext,
    ): List<DailyViewLogic.TextDayData> {
        val textCols = ctx.numColumns.coerceAtLeast(1)
        val dayDataList = DailyViewLogic.prepareTextDays(
            ctx.now, ctx.centerDate, ctx.today, ctx.weatherByDate, ctx.forecastSnapshots, ctx.hourlyForecasts, textCols,
            ctx.displaySource, ctx.skipHistory, ctx.stateManager, ctx.appWidgetId, ctx.precipProb, ctx.dailyActuals,
            ctx.climateNormals,
            ctx.currentTemps,
            currentTemp = ctx.currentTemp,
            observedAt = ctx.observedAt,
            todayLabel = ctx.context.getString(R.string.today)
        )

        val dayIds = listOf(
            DayIds(R.id.day1_container, R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low, R.id.day1_rain),
            DayIds(R.id.day2_container, R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low, R.id.day2_rain),
            DayIds(R.id.day3_container, R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low, R.id.day3_rain),
            DayIds(R.id.day4_container, R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low, R.id.day4_rain),
            DayIds(R.id.day5_container, R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low, R.id.day5_rain),
            DayIds(R.id.day6_container, R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low, R.id.day6_rain),
            DayIds(R.id.day7_container, R.id.day7_label, R.id.day7_icon, R.id.day7_high, R.id.day7_low, R.id.day7_rain),
            DayIds(R.id.day8_container, R.id.day8_label, R.id.day8_icon, R.id.day8_high, R.id.day8_low, R.id.day8_rain),
        )
        check(dayDataList.size <= dayIds.size) { "dayDataList has ${dayDataList.size} items but only ${dayIds.size} DayIds available" }

        dayDataList.forEachIndexed { index, data ->
            val ids = dayIds[index]
            if (data.isVisible) {
                ctx.views.setViewVisibility(ids.container, View.VISIBLE)
                populateDay(ctx.context, ctx.views, ctx.now, ids, data, ctx.hourlyForecasts, ctx.displaySource)
            } else {
                ctx.views.setViewVisibility(ids.container, View.GONE)
            }
        }

        if (dayDataList.any { it.isToday && it.rainSummary != null }) {
            ctx.stateManager.markRainShown(ctx.appWidgetId, ctx.today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }

        return dayDataList.filter { it.isVisible }
    }

    private fun populateDay(
        context: Context, views: RemoteViews, now: LocalDateTime,
        ids: DayIds, data: DailyViewLogic.TextDayData,
        hourlyForecasts: List<HourlyForecastEntity>, displaySource: WeatherSource
    ) {
        views.setTextViewText(ids.label, data.label)
        views.setViewVisibility(ids.label, if (data.showLabel) View.VISIBLE else View.GONE)

        val iconRes = data.iconRes
        views.setImageViewResource(ids.icon, iconRes)

        // Resolve tint next to the predicates (WeatherIconMapper); null = clear filter so
        // precipitation/mixed icons display their natural colors (e.g. blue raindrops).
        val tintColorRes = WeatherIconMapper.resolveDailyTextIconTint(iconRes)
        if (tintColorRes != null) {
            views.setInt(ids.icon, "setColorFilter", context.getColor(tintColorRes))
        } else {
            views.setInt(ids.icon, "setColorFilter", 0)
        }

        views.setViewVisibility(ids.icon, View.VISIBLE)
        views.setTextViewText(ids.high, data.highLabel ?: "--°")
        views.setTextViewText(ids.low, data.lowLabel ?: "--°")

        if (data.showRain && !data.rainSummary.isNullOrEmpty()) {
            views.setTextViewText(ids.rain, data.rainSummary)
            views.setViewVisibility(ids.rain, View.VISIBLE)
        } else {
            views.setViewVisibility(ids.rain, View.GONE)
        }
    }
}
