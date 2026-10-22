package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.TypedValue
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.HeaderContent

internal object DailyHeaderBinder {

    internal const val DELTA_VISIBILITY_THRESHOLD = HeaderContent.DELTA_VISIBILITY_THRESHOLD

    @VisibleForTesting
    internal data class HeaderPrecipPlacement(
        val showHeaderPrecip: Boolean,
        val allowTodayColumnPrecip: Boolean,
    )

    /**
     * The rain % shows whenever the disclosure ladder keeps it ([headerCanShowPrecip]). It never
     * yields to the date: the painted date drops itself when it cannot clear the rain %
     * (`DailyForecastHeaderRenderer.resolveDateDrawX`). The rain % used to be hidden whenever that
     * let the date fit (user, 2026-10-22: "the rain % is much higher priority than the date").
     */
    @VisibleForTesting
    internal fun resolveHeaderPrecipPlacement(
        precipText: String?,
        precipTextSizeDp: Float?,
        headerCanShowPrecip: Boolean,
    ): HeaderPrecipPlacement =
        HeaderPrecipPlacement(
            showHeaderPrecip = headerCanShowPrecip && !precipText.isNullOrBlank() && precipTextSizeDp != null,
            allowTodayColumnPrecip = false,
        )

    @VisibleForTesting
    internal fun currentTempTextSizePx(context: Context): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
            context.resources.displayMetrics,
        )

    internal fun dailyDeltaHiddenReason(
        currentTemp: Float?,
        delta: Float?,
        threshold: Float = DELTA_VISIBILITY_THRESHOLD,
    ): String? =
        when {
            currentTemp == null -> "current_temp_missing"
            delta == null -> "no_delta"
            kotlin.math.abs(delta) < threshold -> "below_threshold"
            else -> null
        }

    internal fun buildHeaderStateLog(
        widgetId: Int,
        viewMode: com.weatherwidget.widget.ViewMode,
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
        zoom: com.weatherwidget.widget.ZoomWindow?,
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
            "isNowLineVisible=${isNowLineVisible ?: "n/a"} offset=$offset zoom=${zoom?.stage?.name ?: "n/a"} resolveMs=$resolveMs"

    internal fun formatLocation(location: Pair<Double, Double>?): String {
        if (location == null) return "none"
        return String.format("%.5f,%.5f", location.first, location.second)
    }

    internal fun formatTemp(value: Float?): String = value?.let { String.format("%.2f", it) } ?: "none"
}
