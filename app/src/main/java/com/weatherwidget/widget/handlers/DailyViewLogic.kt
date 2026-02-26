package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure logic for the daily forecast view, separated from Android UI components for testability.
 */
object DailyViewLogic {
    private fun formatTempLabel(v: Float?): String? {
        if (v == null) return null
        val rounded = v.roundToInt()
        val diff = abs(v - rounded.toFloat())
        return if (diff < 0.01f) "$rounded°" else String.format("%.1f°", v)
    }


    data class TextDayData(
        val dayIndex: Int,
        val date: LocalDate,
        val dateStr: String,
        val isVisible: Boolean,
        val hasData: Boolean,
        val label: String,
        val weather: WeatherEntity?,
        val rainSummary: String?,
        val showRain: Boolean,
        val isToday: Boolean,
        val hasRainForecast: Boolean,
        val highLabel: String?,
        val lowLabel: String?
    )

    fun getEffectiveCondition(
        now: LocalDateTime,
        todayStr: String,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        dailyWeather: WeatherEntity?
    ): String? {
        val currentHourKey = com.weatherwidget.util.WeatherTimeUtils.toHourlyForecastKey(now)
        val currentHourForecast = hourlyForecasts.filter { it.dateTime == currentHourKey }.let { forecasts ->
            forecasts.find { it.source == displaySource.id }
                ?: forecasts.find { it.source == WeatherSource.GENERIC_GAP.id }
                ?: forecasts.firstOrNull()
        }
        return currentHourForecast?.condition ?: dailyWeather?.condition
    }

    fun prepareTextDays(
        now: LocalDateTime,
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<String, WeatherEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        numColumns: Int,
        displaySource: WeatherSource,
        skipHistory: Boolean = false,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
    ): List<TextDayData> {
        val effectiveCenter = if (skipHistory) centerDate.plusDays(1) else centerDate
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val daySlots = listOf(-1, 0, 1, 2, 3, 4, 5).mapIndexed { index, offset ->
            val date = effectiveCenter.plusDays(offset.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weather = weatherByDate[dateStr]
            val hasData = weather != null && weather.highTemp != null && weather.lowTemp != null
            
            val isVisible = when {
                numColumns >= 7 -> true
                numColumns == 6 -> index <= 5
                numColumns == 5 -> index <= 4
                numColumns == 4 -> index <= 3
                numColumns == 3 -> index <= 2
                numColumns == 2 -> index in 1..2
                else -> index == 1
            } && hasData

            Triple(index + 1, date, isVisible)
        }

        val nearTermLimit = today.plusDays(2)
        fun isNearTerm(date: LocalDate) = !date.isBefore(today) && !date.isAfter(nearTermLimit)

        val rawSummaries = daySlots.map { (_, date, isVisible) ->
            if (isVisible) {
                RainAnalyzer.getRainSummary(hourlyForecasts, date, displaySource.id, now)
            } else null
        }

        val displayedSummaries = daySlots.mapIndexed { index, (_, date, isVisible) ->
            if (!isVisible || !isNearTerm(date)) null
            else if (date == today && stateManager?.wasRainShownToday(appWidgetId, todayStr) == true) null
            else rawSummaries[index]
        }

        val firstRainDayIndex = displayedSummaries.indexOfFirst { it != null }

        return daySlots.mapIndexed { index, (dayIndex, date, isVisible) ->
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weather = weatherByDate[dateStr]
            val isToday = date == today
            val precip = if (isToday) todayNext8HourPrecipProbability else weather?.precipProbability
            
            var highLabel: String? = formatTempLabel(weather?.highTemp)
            var lowLabel: String? = formatTempLabel(weather?.lowTemp)

            if (isToday && hourlyForecasts.isNotEmpty() && weather != null) {
                val tripleValues = com.weatherwidget.util.DailyActualsEstimator.calculateTodayTripleLineValues(
                    hourlyForecasts, today, now, displaySource, weather
                )
                
                highLabel = formatTempLabel(tripleValues.observedHigh) ?: highLabel
                lowLabel = formatTempLabel(tripleValues.observedLow) ?: lowLabel
            }

            TextDayData(
                dayIndex = dayIndex,
                date = date,
                dateStr = dateStr,
                isVisible = isVisible,
                hasData = weather != null && weather.highTemp != null && weather.lowTemp != null,
                label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                weather = weather,
                rainSummary = displayedSummaries[index],
                showRain = index == firstRainDayIndex,
                isToday = isToday,
                hasRainForecast = DayClickHelper.hasRainForecast(rawSummaries[index], precip),
                highLabel = highLabel,
                lowLabel = lowLabel
            )
        }
    }

    fun prepareGraphDays(
        now: LocalDateTime,
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<String, WeatherEntity>,
        forecastSnapshots: Map<String, List<ForecastSnapshotEntity>>,
        numColumns: Int,
        displaySource: WeatherSource,
        isEveningMode: Boolean,
        skipHistory: Boolean,
        hourlyForecasts: List<HourlyForecastEntity>,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
    ): List<DailyForecastGraphRenderer.DayData> {
        val days = mutableListOf<DailyForecastGraphRenderer.DayData>()
        val dayOffsets = NavigationUtils.getDayOffsets(numColumns, skipHistory)
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        dayOffsets.forEach { offset ->
            val date = centerDate.plusDays(offset.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weather = weatherByDate[dateStr] ?: return@forEach

            if (weather.highTemp == null && weather.lowTemp == null) return@forEach

            val forecasts = forecastSnapshots[dateStr] ?: emptyList()
            val forecast = forecasts.filter { it.source == displaySource.id }.maxByOrNull { it.fetchedAt }
                ?: forecasts.filter { it.source == WeatherSource.GENERIC_GAP.id }.maxByOrNull { it.fetchedAt }

            val label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val isPastDate = date.isBefore(today)
            val isToday = date == today

            val showComparison = (isPastDate || (isToday && isEveningMode)) &&
                forecast != null

            var finalHigh: Float? = weather.highTemp
            var finalLow: Float? = weather.lowTemp
            var fHigh: Float? = null
            var fLow: Float? = null

            if (isToday && hourlyForecasts.isNotEmpty()) {
                val tripleValues = com.weatherwidget.util.DailyActualsEstimator.calculateTodayTripleLineValues(
                    hourlyForecasts, today, now, displaySource, weather
                )
                finalHigh = tripleValues.observedHigh
                finalLow = tripleValues.observedLow
                fHigh = tripleValues.forecastHigh
                fLow = tripleValues.forecastLow
            } else if (showComparison) {
                fHigh = forecast?.highTemp
                fLow = forecast?.lowTemp
            }

            val effectiveCondition = if (isToday) {
                getEffectiveCondition(now, todayStr, displaySource, hourlyForecasts, weather)
            } else weather.condition
            
            val iconRes = WeatherIconMapper.getIconResource(effectiveCondition)

            val rawRainSummary = if (!isPastDate) {
                RainAnalyzer.getRainSummary(hourlyForecasts, date, displaySource.id, now)
            } else null
            
            val precip = if (isToday) todayNext8HourPrecipProbability else weather.precipProbability
            val hasRainForecast = DayClickHelper.hasRainForecast(rawRainSummary, precip)
            
            val nearTermLimit = today.plusDays(2)
            val rainSummary = if (!date.isBefore(today) && !date.isAfter(nearTermLimit)) {
                if (isToday && rawRainSummary != null && stateManager?.wasRainShownToday(appWidgetId, todayStr) == true) {
                    null
                } else {
                    rawRainSummary
                }
            } else null

            days.add(
                DailyForecastGraphRenderer.DayData(
                    date = dateStr,
                    label = label,
                    high = finalHigh,
                    low = finalLow,
                    iconRes = iconRes,
                    isSunny = WeatherIconMapper.isSunny(iconRes),
                    isRainy = WeatherIconMapper.isRainy(iconRes),
                    isMixed = WeatherIconMapper.isMixed(iconRes),
                    isToday = isToday,
                    isPast = isPastDate,
                    isClimateNormal = weather.isClimateNormal,
                    forecastHigh = fHigh,
                    forecastLow = fLow,
                    rainSummary = rainSummary,
                    dailyPrecipProbability = precip,
                    hasRainForecast = hasRainForecast,
                )
            )
        }
        return days
    }
}
