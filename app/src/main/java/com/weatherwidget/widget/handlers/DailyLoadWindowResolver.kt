package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalTime

/**
 * Resolves how many days of daily forecast rows actually need loading, from the real geometry and
 * saved date offset of the installed widgets — replacing the flat 30-day window that over-fetched
 * ~3x for a 10-column widget (see plans/260803-daily-load-window-right-sizing.md).
 *
 * Returns the **widest** need across all installed widgets, deliberately, for two reasons: the
 * startup and worker paths repaint every widget from one shared load, and
 * [WidgetInteractionCache] is keyed on `(lat, lon, today)` so all widgets in a tap burst must agree
 * on the window or a narrow widget's load would serve a wide one. One shared, widest window keeps
 * that coalescing intact while still fetching only what some widget genuinely renders.
 */
internal object DailyLoadWindowResolver {

    /**
     * Floor applied to the resolved window. Covers the "no widgets readable yet" case (fresh install,
     * host not yet reporting options) and keeps the today/yesterday/tomorrow core plus the accuracy
     * comparison available even for a 1-column widget.
     */
    private val MINIMUM = NavigationUtils.DailyLoadWindow(historyDays = 2L, forecastDays = 3L)

    fun resolve(
        context: Context,
        appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context),
        stateManager: WidgetStateManager = WidgetStateManager(context),
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now(),
    ): NavigationUtils.DailyLoadWindow {
        val ids: IntArray = runCatching {
            appWidgetManager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
        }.getOrNull() ?: IntArray(0)

        return ids.fold(MINIMUM) { widest, id ->
            val cols = runCatching {
                WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, id).cols
            }.getOrNull() ?: return@fold widest
            val offset = runCatching { stateManager.getDateOffset(id) }.getOrNull() ?: 0
            widest.coerceAtLeast(
                NavigationUtils.dailyLoadWindow(
                    today = today,
                    dateOffset = offset,
                    numColumns = cols,
                    skipYesterday = NavigationUtils.shouldSkipYesterday(now, cols),
                ),
            )
        }
    }
}
