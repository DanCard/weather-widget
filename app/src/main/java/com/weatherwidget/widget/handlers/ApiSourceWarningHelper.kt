package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetProvider

object ApiSourceWarningHelper {
    @VisibleForTesting
    internal data class SourceWarning(
        val headerText: String,
        val summaryText: String,
        val detailText: String,
        val sourceLabelText: String,
        val toastMessage: String,
    )

    internal suspend fun resolveBlockingSourceWarning(
        appLogDao: AppLogDao,
        displaySource: WeatherSource,
        hasSelectedSourceData: Boolean,
    ): SourceWarning? {
        if (hasSelectedSourceData || !displaySource.requiresApiKey()) return null

        val relevantTags = buildSet {
            add("CURR_FETCH_ERROR")
            sourceFailureTag(displaySource)?.let { add(it) }
        }
        val messages =
            appLogDao.getRecentLogs(40)
                .asSequence()
                .filter { it.tag in relevantTags }
                .filter {
                    it.tag != "CURR_FETCH_ERROR" || it.message.contains("source=${displaySource.id}")
                }
                .map { it.message }
                .toList()

        return classifyBlockingSourceWarning(displaySource, hasSelectedSourceData, messages)
    }

    @VisibleForTesting
    internal fun classifyBlockingSourceWarning(
        displaySource: WeatherSource,
        hasSelectedSourceData: Boolean,
        latestFailureMessages: List<String>,
    ): SourceWarning? {
        if (hasSelectedSourceData || !displaySource.requiresApiKey()) return null

        for (rawMessage in latestFailureMessages) {
            val message = rawMessage.trim()
            if (message.isBlank()) continue
            val normalized = message.lowercase()
            if (normalized.contains("code=http_401") ||
                normalized.contains(" 401 ") ||
                normalized.contains("401 unauthorized") ||
                normalized.contains("invalid or unauthorized")
            ) {
                val detail = extractDetail(message)
                val toast = "${displaySource.displayName} 401 error. $detail"
                val summary =
                    when {
                        detail.contains("one call 3.0", ignoreCase = true) ->
                            "One Call 3.0 subscription required."
                        detail.contains("invalid", ignoreCase = true) ||
                            detail.contains("unauthorized", ignoreCase = true) ->
                            "API key invalid or unauthorized."
                        else -> "401 unauthorized."
                    }
                return SourceWarning(
                    headerText = "${displaySource.shortDisplayName} 401 error",
                    summaryText = summary,
                    detailText = detail,
                    sourceLabelText = "${displaySource.shortDisplayName} 401",
                    toastMessage = toast,
                )
            }
            if (normalized.contains("api key missing") || normalized.contains("_api_key is missing")) {
                val detail = extractDetail(message)
                val toast = "${displaySource.displayName} key missing. $detail"
                return SourceWarning(
                    headerText = "${displaySource.shortDisplayName} key missing",
                    summaryText = "API key missing.",
                    detailText = detail,
                    sourceLabelText = "${displaySource.shortDisplayName} key",
                    toastMessage = toast,
                )
            }
        }

        return null
    }

    internal fun renderSourceWarningState(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        warning: SourceWarning,
    ) {
        views.setTextViewText(R.id.api_source, warning.sourceLabelText)
        views.setTextViewText(R.id.widget_warning_title, warning.headerText)
        views.setTextViewText(R.id.widget_warning_summary, warning.summaryText)
        views.setTextViewText(R.id.widget_warning_detail, warning.detailText)
        views.setViewVisibility(R.id.widget_warning_container, View.VISIBLE)
        views.setViewVisibility(R.id.widget_warning_summary, View.VISIBLE)

        views.setViewVisibility(R.id.text_container, View.GONE)
        views.setViewVisibility(R.id.graph_view, View.GONE)
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_reserved_space, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_day_zones, View.GONE)
        views.setViewVisibility(R.id.current_temp, View.GONE)
        views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        views.setViewVisibility(R.id.precip_probability, View.GONE)
        views.setViewVisibility(R.id.weather_icon, View.GONE)
        views.setViewVisibility(R.id.nav_left, View.GONE)
        views.setViewVisibility(R.id.nav_right, View.GONE)
        views.setViewVisibility(R.id.nav_left_zone, View.GONE)
        views.setViewVisibility(R.id.nav_right_zone, View.GONE)
        views.setViewVisibility(R.id.home_icon, View.GONE)
        views.setViewVisibility(R.id.home_touch_zone, View.GONE)
        views.setViewVisibility(R.id.history_icon, View.GONE)
        views.setViewVisibility(R.id.history_touch_zone, View.GONE)
        views.setViewVisibility(R.id.current_stations_icon, View.GONE)
        views.setViewVisibility(R.id.current_stations_touch_zone, View.GONE)
        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, false)
        views.setOnClickPendingIntent(
            R.id.widget_warning_container,
            PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.apiToggle(appWidgetId) + 7000,
                Intent(context, WeatherWidgetProvider::class.java).apply {
                    action = WeatherWidgetProvider.ACTION_SHOW_TOAST
                    putExtra(WeatherWidgetProvider.EXTRA_TOAST_MESSAGE, warning.toastMessage)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    internal fun hideSourceWarning(views: RemoteViews) {
        views.setViewVisibility(R.id.widget_warning_container, View.GONE)
        views.setViewVisibility(R.id.widget_warning_summary, View.GONE)
        views.setOnClickPendingIntent(R.id.widget_warning_container, null)
    }

    private fun sourceFailureTag(source: WeatherSource): String? =
        when (source) {
            WeatherSource.VISUAL_CROSSING -> "FETCH_VISUAL_CROSSING_FAIL"
            WeatherSource.OPEN_WEATHER_MAP -> "FETCH_OWM_FAIL"
            WeatherSource.WEATHER_API -> "FETCH_WAPI_FAIL"
            WeatherSource.SILURIAN -> "FETCH_SILURIAN_FAIL"
            else -> null
        }

    private fun WeatherSource.requiresApiKey(): Boolean =
        this == WeatherSource.VISUAL_CROSSING ||
            this == WeatherSource.OPEN_WEATHER_MAP ||
            this == WeatherSource.WEATHER_API ||
            this == WeatherSource.SILURIAN ||
            this == WeatherSource.TOMORROW_IO

    private fun extractDetail(message: String): String {
        val prefixes = listOf("detail=", "error=")
        for (prefix in prefixes) {
            val index = message.indexOf(prefix)
            if (index >= 0) {
                return message.substring(index + prefix.length).replace(Regex("\\s+"), " ").trim()
            }
        }
        return message.replace(Regex("\\s+"), " ").trim()
    }
}
