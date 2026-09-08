package com.weatherwidget.widget.handlers

import android.graphics.Color
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.HeaderFormatter
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SunInfo
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureResolution
import com.weatherwidget.widget.CurrentTemperatureResolver
import kotlin.math.abs

internal object TemperatureHeaderStateBuilder {
    private const val DELTA_COLOR_HEX = "#FF6B35"
    private const val DELTA_VISIBILITY_THRESHOLD = 0.1f

    fun buildHeaderState(
        currentTempResolution: CurrentTemperatureResolution,
        deltaFromYesterday: Float?,
        centerTime: java.time.LocalDateTime,
        now: java.time.LocalDateTime,
        displaySource: WeatherSource,
        dimensions: WidgetDimensions,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        sunInfo: SunInfo,
        precipProbability: Int?,
        useCelsius: Boolean,
    ): Pair<TemperatureWidgetState.HeaderState, Int?> {
        val currentTemp = currentTempResolution.displayTemp
        val headerDelta = deltaFromYesterday
        val deltaVisible = currentTemp != null && headerDelta != null &&
            abs(headerDelta) >= DELTA_VISIBILITY_THRESHOLD

        val sourceIndicator = HeaderFormatter.formatSourceIndicator(
            centerTime = centerTime,
            now = now,
            sourceName = displaySource.shortDisplayName,
            widthDp = dimensions.widthDp
        )

        val currentHourForecast = WeatherTimeUtils.getCurrentHourForecast(currentTempHourlyForecasts, displaySource)
        val iconRes = WeatherIconMapper.getIconResource(
            condition = currentHourForecast?.condition,
            isNight = sunInfo.isNight,
            cloudCover = currentHourForecast?.cloudCover,
            precipProbability = currentHourForecast?.precipProbability,
            isTwilight = sunInfo.phase == SunPhase.TWILIGHT,
            isSunBoundary = sunInfo.isSunBoundary,
        )

        val headerPrecipProbability = HeaderPrecipCalculator.getNext6HourPrecipProbability(
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            fallbackDailyProbability = precipProbability,
            referenceTime = centerTime,
        )
        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(headerPrecipProbability)

        val headerState = TemperatureWidgetState.HeaderState(
            sourceIndicator = sourceIndicator,
            iconRes = iconRes,
            currentTemp = if (currentTemp != null) {
                val formatted = CurrentTemperatureResolver.formatDisplayTemperature(
                    currentTemp,
                    dimensions.cols,
                    currentTempResolution.isStaleEstimate,
                    useCelsius = useCelsius
                )
                formatted
            } else null,
            currentTempSizeDp = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
            deltaText = if (deltaVisible) {
                val displayDelta = if (useCelsius) headerDelta / 1.8f else headerDelta
                String.format("%+.1f", displayDelta)
            } else null,
            deltaColor = Color.parseColor(DELTA_COLOR_HEX),
            precipProbability = if (isPrecipVisible) "$headerPrecipProbability%" else null,
            precipTextSizeDp = if (isPrecipVisible) HeaderPrecipCalculator.getPrecipTextSize(checkNotNull(headerPrecipProbability)) else 0f,
            isPrecipVisible = isPrecipVisible,
            isCurrentTempVisible = currentTemp != null,
            isDeltaVisible = deltaVisible,
            isStaleEstimate = currentTempResolution.isStaleEstimate,
        )
        return headerState to headerPrecipProbability
    }
}
