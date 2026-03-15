package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
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
import kotlin.math.roundToInt

/**
 * Pure business logic for the daily forecast view, extracted for testability.
 */
object DailyViewLogic {

    data class TextDayData(
        val dayIndex: Int,
        val date: LocalDate,
        val dateStr: String,
        val isVisible: Boolean,
        val hasData: Boolean,
        val label: String,
        val weather: ForecastEntity?,
        val rainSummary: String?,
        val showRain: Boolean,
        val isToday: Boolean,
        val isSourceGapFallback: Boolean,
        val hasRainForecast: Boolean,
        val iconRes: Int,
        val highLabel: String?,
        val lowLabel: String?
    )

    fun prepareTextDays(
        now: LocalDateTime,
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<String, ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        numColumns: Int,
        displaySource: WeatherSource,
        skipHistory: Boolean = false,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
        dailyActuals: Map<String, com.weatherwidget.widget.ObservationResolver.DailyActual> = emptyMap()
    ): List<TextDayData> {
        val effectiveCenter = if (skipHistory) centerDate.plusDays(1) else centerDate
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val daySlots = listOf(-1, 0, 1, 2, 3, 4, 5).mapIndexed { index, offset ->
            val date = effectiveCenter.plusDays(offset.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weather = weatherByDate[dateStr]
            val isToday = date == today
            val isPast = date.isBefore(today)
            
            // For future days, we need both high and low.
            // For today and past days, we can show partial data (High-only or Low-only).
            val hasData = if (!isToday && !isPast) {
                weather != null && weather.highTemp != null && weather.lowTemp != null
            } else {
                (weather != null && (weather.highTemp != null || weather.lowTemp != null)) || dailyActuals.containsKey(dateStr)
            }
            
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
            val isPast = date.isBefore(today)
            val precip = if (isToday) todayNext8HourPrecipProbability else weather?.precipProbability
            
            // Round future days to integers to maintain UI consistency.
            // Today and historical days are permitted to show decimals for precision.
            val formatTemp = if (isToday || isPast) ::formatTempLabel else { v: Float? -> 
                v?.roundToInt()?.let { "$it°" } 
            }
            
            var highLabel: String? = formatTemp(weather?.highTemp)
            var lowLabel: String? = formatTemp(weather?.lowTemp)

            if (isPast) {
                val obsHigh = dailyActuals[dateStr]?.highTemp
                val obsLow = dailyActuals[dateStr]?.lowTemp
                val fcstHigh = weather?.highTemp
                val fcstLow = weather?.lowTemp

                val finalHigh: Float?
                val finalLow: Float?
                if (displaySource == WeatherSource.NWS && fcstHigh != null && fcstLow != null) {
                    // NWS mode: trust NWS daily endpoint value first if complete.
                    finalHigh = fcstHigh
                    finalLow = fcstLow
                } else {
                    // If incomplete or other source, use the most extreme values.
                    finalHigh = listOfNotNull(obsHigh, fcstHigh).maxOrNull()
                    finalLow = listOfNotNull(obsLow, fcstLow).minOrNull()
                }

                highLabel = formatTempLabel(finalHigh)
                lowLabel = formatTempLabel(finalLow)
            } else if (isToday && (weather != null || dailyActuals.containsKey(dateStr))) {
                val tripleValues = com.weatherwidget.util.DailyActualsEstimator.calculateTodayTripleLineValues(
                    hourlyForecasts, today, now, displaySource, weather, dailyActuals
                )

                highLabel = formatTempLabel(tripleValues.observedHigh) ?: formatTempLabel(weather?.highTemp)
                lowLabel = formatTempLabel(tripleValues.observedLow) ?: formatTempLabel(weather?.lowTemp)
            }

            val todayIconForecast =
                if (isToday) DailyViewHandler.resolveTodayHeaderForecast(now, hourlyForecasts, displaySource) else null
            val iconRes =
                WeatherIconMapper.getIconResource(
                    condition = todayIconForecast?.condition ?: weather?.condition,
                    cloudCover = todayIconForecast?.cloudCover,
                )

            TextDayData(
                dayIndex = dayIndex,
                date = date,
                dateStr = dateStr,
                isVisible = isVisible,
                hasData = if (!isToday && !isPast) {
                    weather != null && weather.highTemp != null && weather.lowTemp != null
                } else {
                    (weather != null && (weather.highTemp != null || weather.lowTemp != null)) || dailyActuals.containsKey(dateStr)
                },
                label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                weather = weather,
                rainSummary = displayedSummaries[index],
                showRain = index == firstRainDayIndex,
                isToday = isToday,
                isSourceGapFallback = weather?.source == WeatherSource.GENERIC_GAP.id,
                hasRainForecast = DayClickHelper.hasRainForecast(rawSummaries[index], precip),
                iconRes = iconRes,
                highLabel = highLabel,
                lowLabel = lowLabel
            )
        }
    }

    fun prepareGraphDays(
        now: LocalDateTime,
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<String, ForecastEntity>,
        forecastSnapshots: Map<String, List<ForecastEntity>>,
        numColumns: Int,
        displaySource: WeatherSource,
        isEveningMode: Boolean,
        skipHistory: Boolean,
        hourlyForecasts: List<HourlyForecastEntity>,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
        dailyActuals: Map<String, com.weatherwidget.widget.ObservationResolver.DailyActual> = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Int, Int>> = emptyMap()
    ): List<DailyForecastGraphRenderer.DayData> {
        val days = mutableListOf<DailyForecastGraphRenderer.DayData>()
        val dayOffsets = NavigationUtils.getDayOffsets(numColumns, skipHistory)
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        dayOffsets.forEachIndexed { index, offset ->
            val date = centerDate.plusDays(offset.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            
            // Try preferred source first, then any available source for the given date.
            // This ensures we fill all columns if data exists anywhere in the DB.
            val weather = weatherByDate[dateStr] ?: forecastSnapshots[dateStr]?.firstOrNull()
            val actual = dailyActuals[dateStr]

            if (weather == null && actual == null) return@forEachIndexed
            
            // For future days, we need both high and low.
            // For today and past days, we can show partial data (High-only or Low-only).
            val isToday = date == today
            val isPastDate = date.isBefore(today)
            if (!isToday && !isPastDate) {
                if (weather?.highTemp == null || weather?.lowTemp == null) return@forEachIndexed
            } else {
                // Today/Past: Must have at least ONE temperature source
                if (weather?.highTemp == null && weather?.lowTemp == null && actual == null) return@forEachIndexed
            }

            val forecasts = forecastSnapshots[dateStr] ?: emptyList()
            // Prefer the latest COMPLETE snapshot (both high and low) for historical comparison,
            // as NWS midday fetches often drop the morning low.
            val forecast = forecasts
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .filter { it.highTemp != null && it.lowTemp != null }
                .maxByOrNull { it.fetchedAt }
                ?: forecasts.filter { it.source == displaySource.id }.maxByOrNull { it.fetchedAt }
                ?: forecasts.filter { it.source == WeatherSource.GENERIC_GAP.id }.maxByOrNull { it.fetchedAt }

            val label = if (isToday) "Today" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

            val showComparison = (isPastDate || (isToday && isEveningMode))

            var finalHigh: Float? = weather?.highTemp
            var finalLow: Float? = weather?.lowTemp
            var fHigh: Float? = null
            var fLow: Float? = null
            var isClimateOverlay = false

            if (isPastDate) {
                // Merge raw observations and forecast snapshots to find the absolute truth.
                // If observations are missing (device off), fall back to forecast.
                // If forecast is partial, use observations to fill the gap.
                val obsHigh = actual?.highTemp
                val obsLow = actual?.lowTemp
                val fcstHigh = weather?.highTemp
                val fcstLow = weather?.lowTemp

                if (displaySource == WeatherSource.NWS && fcstHigh != null && fcstLow != null) {
                    // NWS mode: keep past-day highs/lows anchored to official NWS daily endpoint if complete.
                    finalHigh = fcstHigh
                    finalLow = fcstLow
                } else {
                    // If NWS is partial (missing high or low), or for other sources, use the most extreme values.
                    finalHigh = listOfNotNull(obsHigh, fcstHigh).maxOrNull()
                    finalLow = listOfNotNull(obsLow, fcstLow).minOrNull()
                }

                if (showComparison) {
                    fHigh = forecast?.highTemp
                    fLow = forecast?.lowTemp
                    
                    // Fallback to climate normal if no real snapshot exists
                    if (fHigh == null || fLow == null) {
                        val normal = climateNormals[java.time.MonthDay.from(date)]
                        if (normal != null) {
                            fHigh = normal.first.toFloat()
                            fLow = normal.second.toFloat()
                            isClimateOverlay = true
                        } else if (weather?.isClimateNormal == true) {
                            fHigh = fcstHigh
                            fLow = fcstLow
                            isClimateOverlay = true
                        }
                    }
                }
            } else if (isToday && (weather != null || dailyActuals.containsKey(dateStr))) {
                val tripleValues = com.weatherwidget.util.DailyActualsEstimator.calculateTodayTripleLineValues(
                    hourlyForecasts, today, now, displaySource, weather, dailyActuals
                )
                finalHigh = tripleValues.observedHigh
                finalLow = tripleValues.observedLow
                fHigh = tripleValues.forecastHigh
                fLow = tripleValues.forecastLow
            } else if (showComparison) {
                fHigh = forecast?.highTemp
                fLow = forecast?.lowTemp
            }

            val todayIconForecast =
                if (isToday) DailyViewHandler.resolveTodayHeaderForecast(now, hourlyForecasts, displaySource) else null
            val effectiveCondition = todayIconForecast?.condition ?: weather?.condition ?: actual?.condition

            val iconRes =
                WeatherIconMapper.getIconResource(
                    condition = effectiveCondition,
                    cloudCover = todayIconForecast?.cloudCover,
                )

            val rawRainSummary = if (!isPastDate) {
                RainAnalyzer.getRainSummary(hourlyForecasts, date, displaySource.id, now)
            } else null
            
            val precip = if (isToday) todayNext8HourPrecipProbability else weather?.precipProbability
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
                    isClimateNormal = isClimateOverlay,
                    isSourceGapFallback = weather?.source == WeatherSource.GENERIC_GAP.id,
                    forecastHigh = fHigh,
                    forecastLow = fLow,
                    rainSummary = rainSummary,
                    dailyPrecipProbability = precip,
                    hasRainForecast = hasRainForecast,
                    columnIndex = index
                )
            )
        }
        return days
    }

    private fun formatTempLabel(v: Float?): String? {
        if (v == null) return null
        val rounded = v.roundToInt()
        return if (kotlin.math.abs(v - rounded) < 0.01f) "$rounded°" else String.format("%.1f°", v)
    }
}
