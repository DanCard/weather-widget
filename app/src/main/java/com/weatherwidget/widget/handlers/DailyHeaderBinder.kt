package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.TypedValue
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.WeatherTimeUtils
import java.time.LocalDateTime

internal object DailyHeaderBinder {

    internal const val DELTA_VISIBILITY_THRESHOLD = 0.1f

    /**
     * Where the header date *would* sit, if it is shown at all.
     *
     * Despite the name this no longer positions anything: the daily header is painted into the
     * graph bitmap, and the date is placed and drawn by
     * [com.weatherwidget.widget.DailyForecastHeaderRenderer.resolveHeaderDateLayout]. What survives
     * here is the same geometry expressed in dp/RemoteViews units, used by
     * [resolveHeaderPrecipPlacement] to answer *"would the date still fit if we also drew the rain
     * %?"* — i.e. the header's precip-vs-date priority rule.
     *
     * The two implementations must stay in agreement; see the plan note in
     * `plans/260809-daily-view-history-and-observations-buttons.md`.
     */
    @VisibleForTesting
    internal enum class HeaderDatePlacement {
        CENTER,
        RIGHT,
    }

    @VisibleForTesting
    internal data class HeaderPrecipPlacement(
        val showHeaderPrecip: Boolean,
        val allowTodayColumnPrecip: Boolean,
    )

    @VisibleForTesting
    internal fun resolveHeaderDatePlacement(
        context: Context,
        widthDp: Int,
        numColumns: Int,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        apiSourceText: String,
        apiTextSizeDp: Float,
        dateText: String,
        includeIcon: Boolean = true,
    ): HeaderDatePlacement? {
        if (numColumns < HeaderConstants.DATE_MIN_COLUMNS) return null

        val widthPx = WidgetSizeCalculator.dpToPx(context, widthDp).toFloat()
        val leftClusterRight =
            HeaderWidthChecker.resolveLeftClusterRightPx(
                context = context,
                currentTempText = currentTempText,
                deltaText = deltaText,
                precipText = precipText,
                precipTextSizeDp = precipTextSizeDp,
                includeIcon = includeIcon,
                currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
            )
        val apiLeft = HeaderWidthChecker.resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)
        val dateWidth = HeaderWidthChecker.textWidthPx(context, dateText, HeaderConstants.DATE_TEXT_SIZE_DP)
        val gapPx = HeaderWidthChecker.dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP)
        val rightMarginPx = HeaderWidthChecker.dpToPx(context, HeaderConstants.DATE_RIGHT_MARGIN_DP)

        return resolveHeaderDatePlacementFromBounds(
            numColumns = numColumns,
            widthPx = widthPx,
            leftClusterRight = leftClusterRight,
            apiLeft = apiLeft,
            dateWidth = dateWidth,
            gapPx = gapPx,
            rightMarginPx = rightMarginPx,
        )
    }

    @VisibleForTesting
    internal fun resolveHeaderDatePlacementFromBounds(
        numColumns: Int,
        widthPx: Float,
        leftClusterRight: Float,
        apiLeft: Float,
        dateWidth: Float,
        gapPx: Float,
        rightMarginPx: Float,
    ): HeaderDatePlacement? {
        if (numColumns < HeaderConstants.DATE_MIN_COLUMNS) return null
        val centerLeft = (widthPx - dateWidth) / 2f
        val centerRight = centerLeft + dateWidth
        if (centerLeft >= leftClusterRight + gapPx && centerRight <= apiLeft - gapPx) {
            return HeaderDatePlacement.CENTER
        }

        val rightCenter = widthPx - rightMarginPx
        val rightLeft = rightCenter - dateWidth / 2f
        val rightRight = rightCenter + dateWidth / 2f
        if (rightLeft >= leftClusterRight + gapPx && rightRight <= apiLeft - gapPx) {
            return HeaderDatePlacement.RIGHT
        }

        return null
    }

    @VisibleForTesting
    internal fun resolveHeaderPrecipPlacement(
        context: Context,
        widthDp: Int,
        numColumns: Int,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        apiSourceText: String,
        apiTextSizeDp: Float,
        dateText: String?,
        headerCanShowPrecip: Boolean,
        includeIcon: Boolean,
    ): HeaderPrecipPlacement {
        if (precipText.isNullOrBlank() || precipTextSizeDp == null) {
            return HeaderPrecipPlacement(showHeaderPrecip = false, allowTodayColumnPrecip = false)
        }
        if (dateText.isNullOrBlank() || numColumns < HeaderConstants.DATE_MIN_COLUMNS) {
            return HeaderPrecipPlacement(showHeaderPrecip = headerCanShowPrecip, allowTodayColumnPrecip = false)
        }

        val dateFitsWithPrecip =
            headerCanShowPrecip &&
                resolveHeaderDatePlacement(
                    context = context,
                    widthDp = widthDp,
                    numColumns = numColumns,
                    currentTempText = currentTempText,
                    deltaText = deltaText,
                    precipText = precipText,
                    precipTextSizeDp = precipTextSizeDp,
                    apiSourceText = apiSourceText,
                    apiTextSizeDp = apiTextSizeDp,
                    dateText = dateText,
                    includeIcon = includeIcon,
                ) != null
        if (dateFitsWithPrecip) {
            return HeaderPrecipPlacement(showHeaderPrecip = true, allowTodayColumnPrecip = false)
        }

        val dateFitsWithoutPrecip =
            resolveHeaderDatePlacement(
                context = context,
                widthDp = widthDp,
                numColumns = numColumns,
                currentTempText = currentTempText,
                deltaText = deltaText,
                precipText = null,
                precipTextSizeDp = null,
                apiSourceText = apiSourceText,
                apiTextSizeDp = apiTextSizeDp,
                dateText = dateText,
                includeIcon = includeIcon,
            ) != null

        return HeaderPrecipPlacement(
            showHeaderPrecip = headerCanShowPrecip && !dateFitsWithoutPrecip,
            allowTodayColumnPrecip = false,
        )
    }

    @VisibleForTesting
    internal fun currentTempTextSizePx(context: Context): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
            context.resources.displayMetrics,
        )

    @VisibleForTesting
    internal fun resolveTodayHeaderForecast(
        now: LocalDateTime,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ): HourlyForecastEntity? {
        val today = now.toLocalDate()
        val forecastsByTime = resolveForecastsByTime(hourlyForecasts, displaySource)

        val candidateTimes =
            listOf(
                now.plusHours(1).takeIf { it.toLocalDate() == today },
                now,
            ).filterNotNull()

        return candidateTimes.firstNotNullOfOrNull { candidateTime ->
            forecastsByTime[WeatherTimeUtils.toHourlyForecastKeyMs(candidateTime)]
        }
    }

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
