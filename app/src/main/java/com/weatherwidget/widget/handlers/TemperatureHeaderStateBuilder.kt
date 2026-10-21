package com.weatherwidget.widget.handlers

import android.graphics.Color
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.CurrentTemperatureResolution

internal object TemperatureHeaderStateBuilder {
    private const val DELTA_COLOR_HEX = "#FF6B35"

    fun buildHeaderState(
        currentTempResolution: CurrentTemperatureResolution,
        deltaFromYesterday: Float?,
        centerTime: java.time.LocalDateTime,
        now: java.time.LocalDateTime,
        displaySource: WeatherSource,
        dimensions: WidgetDimensions,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        lat: Double,
        lon: Double,
        precipProbability: Int?,
        useCelsius: Boolean,
    ): Pair<TemperatureWidgetState.HeaderState, Int?> {
        // Icon, temp, rain % and delta: the shared header rule (desktop draws the same content).
        val content = HeaderContent.build(
            view = HeaderContent.View.TEMPERATURE,
            hourly = hourlyForecasts.map(HourlyForecastEntity::toHourlyForecast),
            iconHourly = currentTempHourlyForecasts.ifEmpty { hourlyForecasts }.map(HourlyForecastEntity::toHourlyForecast),
            displaySourceId = displaySource.id,
            dailyFallback = null,
            fallbackDailyPrecip = precipProbability,
            currentTemp = currentTempResolution.displayTemp,
            isStaleEstimate = currentTempResolution.isStaleEstimate,
            numColumns = dimensions.cols,
            deltaFromYesterday = deltaFromYesterday,
            now = now,
            centerTime = centerTime,
            lat = lat,
            lon = lon,
            useCelsius = useCelsius,
            widthDp = dimensions.widthDp,
        )
        val shownPrecip = content.precipProbability

        val headerState = TemperatureWidgetState.HeaderState(
            sourceIndicator = content.sourceLabel,
            iconRes = content.iconName?.let(WeatherIconMapper::fromIconName) ?: 0,
            currentTemp = content.tempText,
            currentTempSizeDp = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
            deltaText = content.deltaText,
            deltaColor = Color.parseColor(DELTA_COLOR_HEX),
            precipProbability = content.precipText,
            precipTextSizeDp = content.precipFontScale?.let { HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP * it } ?: 0f,
            isPrecipVisible = shownPrecip != null,
            isCurrentTempVisible = content.tempText != null,
            isDeltaVisible = content.deltaText != null,
            isStaleEstimate = currentTempResolution.isStaleEstimate,
        )
        return headerState to content.computedPrecipProbability
    }
}
