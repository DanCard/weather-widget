package com.weatherwidget.widget.handlers

import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.ZoomLevel
import java.time.LocalDateTime
import java.time.ZoneId

private data class HourlyTextSlot(
    val labelId: Int,
    val iconId: Int,
    val tempId: Int,
    val lowId: Int,
)

internal fun updateHourlyTextMode(
    views: RemoteViews,
    hourlyForecasts: List<HourlyForecastEntity>,
    centerTime: LocalDateTime,
    numColumns: Int,
    displaySource: WeatherSource,
    useCelsius: Boolean,
) {
    val forecastsByTime = resolveForecastsByTime(hourlyForecasts, displaySource)

    val timeOffsets =
        when {
            numColumns >= 6 -> listOf(0, 3, 6, 9, 12, 15)
            numColumns == 5 -> listOf(0, 3, 6, 9, 12)
            numColumns == 4 -> listOf(0, 3, 6, 9)
            numColumns == 3 -> listOf(0, 3, 6)
            numColumns == 2 -> listOf(0, 6)
            else -> listOf(0)
        }

    val containerIds =
        listOf(
            R.id.day1_container to HourlyTextSlot(R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low),
            R.id.day2_container to HourlyTextSlot(R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low),
            R.id.day3_container to HourlyTextSlot(R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low),
            R.id.day4_container to HourlyTextSlot(R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low),
            R.id.day5_container to HourlyTextSlot(R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low),
            R.id.day6_container to HourlyTextSlot(R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low),
        )

    containerIds.forEachIndexed { index, (containerId, ids) ->
        if (index < timeOffsets.size) {
            val offset = timeOffsets[index]
            val targetTime = centerTime.plusHours(offset.toLong())
            val hourMs = targetTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val forecast = forecastsByTime[hourMs]

            views.setViewVisibility(containerId, View.VISIBLE)

            val label = if (offset == 0) "Now" else "+${offset}h"
            views.setTextViewText(ids.labelId, label)
            views.setViewVisibility(ids.iconId, View.GONE)

            if (forecast != null) {
                val displayTemp = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(forecast.temperature) else forecast.temperature
                val temp = String.format("%.1f\u00B0", displayTemp)
                views.setTextViewText(ids.tempId, temp)
                views.setTextViewText(ids.lowId, "")
            } else {
                views.setTextViewText(ids.tempId, "--\u00B0")
                views.setTextViewText(ids.lowId, "")
            }
        } else {
            views.setViewVisibility(containerId, View.GONE)
        }
    }
}

internal fun temperatureDeltaHiddenReason(
    currentTemp: Float?,
    delta: Float?,
): String? =
    when {
        currentTemp == null -> "current_temp_missing"
        delta == null -> "no_delta"
        kotlin.math.abs(delta) < 0.1f -> "below_threshold"
        else -> null
    }

internal fun buildHeaderStateLog(
    widgetId: Int,
    viewMode: ViewMode,
    displaySource: WeatherSource,
    configuredLocation: Pair<Double, Double>?,
    dataLat: Double,
    dataLon: Double,
    dimensions: WidgetDimensions,
    currentTemp: Float?,
    estimatedTemp: Float?,
    observedTemp: Float?,
    appliedDelta: Float?,
    headerDelta: Float?,
    deltaVisible: Boolean,
    deltaHiddenReason: String?,
    precipVisible: Boolean,
    precipProbability: Int?,
    isNowLineVisible: Boolean?,
    offset: Int,
    zoom: ZoomLevel?,
    resolveMs: Long,
): String =
    "headerState widget=$widgetId mode=${viewMode.name} source=${displaySource.id} " +
        "configuredLoc=${formatLocation(configuredLocation)} dataLoc=${formatLocation(dataLat to dataLon)} " +
        "cols=${dimensions.cols} rows=${dimensions.rows} sizeDp=${dimensions.widthDp}x${dimensions.heightDp} " +
        "deviceOrientation=${WidgetSizeCalculator.orientationName(dimensions.deviceOrientation)} " +
        "hostOrientation=${WidgetSizeCalculator.orientationName(dimensions.hostOrientation)} " +
        "orientationSource=${dimensions.orientationSource} " +
        "homePackage=${dimensions.homePackageName ?: "none"} " +
        "homeScreenOrientation=${dimensions.homeScreenOrientation} " +
        "currentTemp=${formatTemp(currentTemp)} estimatedTemp=${formatTemp(estimatedTemp)} " +
        "observedTemp=${formatTemp(observedTemp)} appliedDelta=${formatTemp(appliedDelta)} " +
        "headerDelta=${formatTemp(headerDelta)} " +
        "deltaVisible=$deltaVisible deltaHiddenReason=${deltaHiddenReason ?: "none"} " +
        "precipVisible=$precipVisible precipProbability=${precipProbability ?: "none"} " +
        "isNowLineVisible=${isNowLineVisible ?: "n/a"} " +
        "offset=$offset zoom=${zoom?.name ?: "n/a"} resolveMs=$resolveMs"

private fun formatLocation(location: Pair<Double, Double>?): String {
    if (location == null) return "none"
    return String.format("%.5f,%.5f", location.first, location.second)
}

private fun formatTemp(value: Float?): String = value?.let { String.format("%.2f", it) } ?: "none"
