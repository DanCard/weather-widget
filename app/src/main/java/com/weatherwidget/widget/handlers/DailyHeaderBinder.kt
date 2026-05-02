package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.WeatherTimeUtils
import java.time.LocalDateTime

internal object DailyHeaderBinder {

    internal const val DELTA_VISIBILITY_THRESHOLD = 0.1f

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

    fun bindHeaderDate(
        context: Context,
        views: RemoteViews,
        widthDp: Int,
        numColumns: Int,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        apiSourceText: String,
        apiTextSizeDp: Float,
        dateText: String,
    ) {
        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)
        if (dateText.isBlank()) return

        val placement =
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
            ) ?: return

        val targetId =
            when (placement) {
                HeaderDatePlacement.CENTER -> R.id.header_date_center
                HeaderDatePlacement.RIGHT -> R.id.header_date_right
            }
        views.setTextViewText(targetId, dateText)
        val datePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, HeaderConstants.DATE_TEXT_SIZE_DP, context.resources.displayMetrics)
        views.setTextViewTextSize(targetId, TypedValue.COMPLEX_UNIT_PX, datePx)
        views.setViewVisibility(targetId, View.VISIBLE)
    }

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
            HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
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
        appliedDelta: Float?,
        threshold: Float = DELTA_VISIBILITY_THRESHOLD,
    ): String? =
        when {
            currentTemp == null -> "current_temp_missing"
            appliedDelta == null -> "no_delta"
            kotlin.math.abs(appliedDelta) < threshold -> "below_threshold"
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
        deltaVisible: Boolean,
        deltaHiddenReason: String?,
        precipVisible: Boolean,
        precipProbability: Int?,
        isNowLineVisible: Boolean?,
        offset: Int,
        zoom: com.weatherwidget.widget.ZoomLevel?,
        resolveMs: Long,
    ): String =
        "headerState widget=$widgetId mode=${viewMode.name} source=${displaySource.id} " +
            "configuredLoc=${formatLocation(configuredLocation)} dataLoc=${formatLocation(dataLat to dataLon)} " +
            "cols=${dimensions.cols} rows=${dimensions.rows} sizeDp=${dimensions.widthDp}x${dimensions.heightDp} " +
            "currentTemp=${formatTemp(currentTemp)} estimatedTemp=${formatTemp(estimatedTemp)} " +
            "observedTemp=${formatTemp(observedTemp)} appliedDelta=${formatTemp(appliedDelta)} " +
            "deltaVisible=$deltaVisible deltaHiddenReason=${deltaHiddenReason ?: "none"} " +
            "precipVisible=$precipVisible precipProbability=${precipProbability ?: "none"} " +
            "isNowLineVisible=${isNowLineVisible ?: "n/a"} offset=$offset zoom=${zoom?.name ?: "n/a"} resolveMs=$resolveMs"

    internal fun formatLocation(location: Pair<Double, Double>?): String {
        if (location == null) return "none"
        return String.format("%.5f,%.5f", location.first, location.second)
    }

    internal fun formatTemp(value: Float?): String = value?.let { String.format("%.2f", it) } ?: "none"
}
