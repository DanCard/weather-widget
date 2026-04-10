package com.weatherwidget.widget.handlers

import android.util.Log
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.util.DailyForecastIconResolver
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
    private const val TAG = "DailyViewLogic"

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
        val lowLabel: String?,
        val isTodayForecastFallback: Boolean = false,
    )

    fun prepareTextDays(
        now: LocalDateTime,
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<LocalDate, ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        numColumns: Int,
        displaySource: WeatherSource,
        skipHistory: Boolean = false,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
        dailyActuals: com.weatherwidget.widget.DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Int, Int>> = emptyMap(),
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
        currentTemp: Float? = null,
        observedAt: Long? = null,
    ): List<TextDayData> {

        val effectiveCenter = if (skipHistory) centerDate.plusDays(1) else centerDate
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val daySlots = listOf(-1, 0, 1, 2, 3, 4, 5).mapIndexed { index, offset ->
            val date = effectiveCenter.plusDays(offset.toLong())
            val weather = weatherByDate[date]
            val isToday = date == today
            val isPast = date.isBefore(today)
            
            // For future days, we need both high and low.
            // For today and past days, we can show partial data (High-only or Low-only).
            val hasData = if (!isToday && !isPast) {
                (weather != null && weather.highTemp != null && weather.lowTemp != null) || dailyActuals.containsKey(date)
            } else {
                (weather != null && (weather.highTemp != null || weather.lowTemp != null)) || dailyActuals.containsKey(date)
            }

            val isVisible = when {
                numColumns >= 7 -> true
                numColumns == 6 -> index <= 5
                numColumns == 5 -> index <= 4
                numColumns == 4 -> index <= 3
                numColumns == 3 -> index <= 2
                numColumns == 2 -> index in 1..2
                else -> index == 1
            } // Removed && hasData to always show the column space if navigation reaches it.

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
            val weather = weatherByDate[date]
            val isToday = date == today
            val isPast = date.isBefore(today)
            val precip = if (isToday) todayNext8HourPrecipProbability else weather?.precipProbability
            
            // Round future days to integers to maintain UI consistency.
            // Today and historical days are permitted to show decimals for precision.
            val formatTemp = if (isToday || isPast) { v: Float? -> formatTempLabel(v) } else { v: Float? -> 
                v?.roundToInt()?.let { "$it°" } 
            }
            
            var highLabel: String? = formatTemp(weather?.highTemp)
            var lowLabel: String? = formatTemp(weather?.lowTemp)
            var isTodayForecastFallback = false

            if (isPast) {
                val obsHigh = dailyActuals[date]?.highTemp
                val obsLow = dailyActuals[date]?.lowTemp
                highLabel = formatTempLabel(obsHigh)
                lowLabel = formatTempLabel(obsLow)
            } else if (isToday && (weather != null || dailyActuals.containsKey(date))) {
                val resolvedCurrentTemp = currentTemp ?: com.weatherwidget.widget.ObservationResolver.resolveObservedCurrentTemp(
                    currentTemps, displaySource
                )?.temperature

                val tripleValues = com.weatherwidget.util.DailyActualsEstimator.calculateTodayTripleLineValues(
                    hourlyForecasts, today, now, displaySource, weather, dailyActuals,
                    currentTemp = resolvedCurrentTemp
                )

                val visibleHigh = listOfNotNull(tripleValues.observedHigh, tripleValues.forecastHigh, tripleValues.trueActualHigh).maxOrNull()
                val visibleLow = tripleValues.observedLow ?: tripleValues.forecastLow
                highLabel = formatTempLabel(visibleHigh)
                lowLabel = formatTempLabel(visibleLow)
                isTodayForecastFallback =
                    tripleValues.observedHigh == null &&
                        tripleValues.observedLow == null &&
                        (visibleHigh != null || visibleLow != null)
            } else {
                // Future day fallback to climate normals if missing
                if (highLabel == null || lowLabel == null) {
                    val normal = climateNormals[java.time.MonthDay.from(date)]
                    if (normal != null) {
                        highLabel = formatTemp(normal.first.toFloat())
                        lowLabel = formatTemp(normal.second.toFloat())
                    }
                }
            }

            val iconRes =
                if (weather != null) {
                    DailyForecastIconResolver.resolveIcon(
                        weather = weather,
                        targetDate = date,
                        now = now,
                        latitude = weather.locationLat,
                        longitude = weather.locationLon,
                    )
                } else {
                    WeatherIconMapper.getIconResource(condition = null)
                }

            TextDayData(
                dayIndex = dayIndex,
                date = date,
                dateStr = dateStr,
                isVisible = isVisible,
                hasData = if (!isToday && !isPast) {
                    weather != null && weather.highTemp != null && weather.lowTemp != null
                } else {
                    (weather != null && (weather.highTemp != null || weather.lowTemp != null)) || dailyActuals.containsKey(date)
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
                lowLabel = lowLabel,
                isTodayForecastFallback = isTodayForecastFallback,
            )
        }
    }

    fun prepareGraphDays(
        now: LocalDateTime,
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<LocalDate, ForecastEntity>,
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        numColumns: Int,
        displaySource: WeatherSource,
        isEveningMode: Boolean,
        skipHistory: Boolean,
        hourlyForecasts: List<HourlyForecastEntity>,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
        dailyActuals: com.weatherwidget.widget.DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Int, Int>> = emptyMap(),
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
        currentTemp: Float? = null,
        observedAt: Long? = null,
    ): List<DailyForecastGraphRenderer.DayData> {
        val days = mutableListOf<DailyForecastGraphRenderer.DayData>()
        val dayOffsets = NavigationUtils.getDayOffsets(numColumns, skipHistory)
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        dayOffsets.forEachIndexed { index, offset ->
            val date = centerDate.plusDays(offset)

            // Try preferred source first, then any available source for the given date.
            val weather = weatherByDate[date] ?: forecastSnapshots[date]?.firstOrNull()
            val actual = dailyActuals[date]
            val forecasts = forecastSnapshots[date] ?: emptyList()
            val forecast = forecasts
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .filter { it.highTemp != null && it.lowTemp != null }
                .maxByOrNull { it.fetchedAt }
                ?: forecasts.filter { it.source == displaySource.id }.maxByOrNull { it.fetchedAt }
                ?: forecasts.filter { it.source == WeatherSource.GENERIC_GAP.id }.maxByOrNull { it.fetchedAt }

            val isToday = date == today
            val isPastDate = date.isBefore(today)

            val label = if (isToday) "Today" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val showComparison = (isPastDate || (isToday && isEveningMode))

            var finalHigh: Float? = weather?.highTemp
            var finalLow: Float? = weather?.lowTemp
            var fHigh: Float? = null
            var fLow: Float? = null
            var snapshotHigh: Float? = null
            var snapshotLow: Float? = null
            var isClimateOverlay = false
            var isTodayForecastFallback = false
            var trueActualHigh: Float? = null
            var bottomStackLow: Float? = null

            if (isPastDate) {
                finalHigh = actual?.highTemp
                finalLow = actual?.lowTemp

                if (showComparison) {
                    fHigh = forecast?.highTemp
                    fLow = forecast?.lowTemp
                    
                    if (fHigh == null || fLow == null) {
                        val normal = climateNormals[java.time.MonthDay.from(date)]
                        if (normal != null) {
                            fHigh = normal.first.toFloat()
                            fLow = normal.second.toFloat()
                            isClimateOverlay = true
                        } else if (weather?.isClimateNormal == true) {
                            fHigh = weather.highTemp
                            fLow = weather.lowTemp
                            isClimateOverlay = true
                        }
                    }
                }
            } else if (isToday && (weather != null || dailyActuals.containsKey(date))) {
                val yesterdaySameTime = now.minusHours(24)
                val snapshotCandidates = forecasts
                    .filter { it.source == displaySource.id }
                    .filter { it.highTemp != null && it.lowTemp != null }
                val snapshot = snapshotCandidates
                    .filter { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.fetchedAt), java.time.ZoneId.systemDefault()).isBefore(yesterdaySameTime) }
                    .maxByOrNull { it.fetchedAt }
                    ?: snapshotCandidates.minByOrNull { it.fetchedAt }

                val tripleValues = com.weatherwidget.util.DailyActualsEstimator.calculateTodayTripleLineValues(
                    hourlyForecasts, today, now, displaySource, weather, dailyActuals,
                    currentTemp = currentTemp,
                    snapshotHigh = snapshot?.highTemp,
                    snapshotLow = snapshot?.lowTemp
                )

                finalHigh = tripleValues.observedHigh ?: tripleValues.forecastHigh
                finalLow = tripleValues.observedLow ?: tripleValues.forecastLow
                fHigh = tripleValues.forecastHigh
                fLow = tripleValues.forecastLow
                bottomStackLow = listOfNotNull(tripleValues.observedLow, tripleValues.forecastLow).minOrNull()
                snapshotHigh = tripleValues.snapshotHigh
                snapshotLow = tripleValues.snapshotLow
                trueActualHigh = tripleValues.trueActualHigh
                isTodayForecastFallback =
                    tripleValues.observedHigh == null &&
                        tripleValues.observedLow == null &&
                        (finalHigh != null || finalLow != null)
            } else {
                // Future day
                if (finalHigh == null || finalLow == null) {
                    val normal = climateNormals[java.time.MonthDay.from(date)]
                    if (normal != null) {
                        finalHigh = normal.first.toFloat()
                        finalLow = normal.second.toFloat()
                        isClimateOverlay = true
                    }
                }

                if (showComparison) {
                    fHigh = forecast?.highTemp
                    fLow = forecast?.lowTemp
                }
            }

            val iconRes =
                when {
                    weather != null ->
                        DailyForecastIconResolver.resolveIcon(
                            weather = weather,
                            targetDate = date,
                            now = now,
                            latitude = weather.locationLat,
                            longitude = weather.locationLon,
                        )
                    actual != null -> WeatherIconMapper.getIconResource(condition = actual.condition)
                    else -> WeatherIconMapper.getIconResource(condition = null)
                }

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
                    date = date,
                    label = label,
                    high = finalHigh,
                    low = finalLow,
                    bottomStackLow = bottomStackLow ?: finalLow,
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
                    dailyPrecipAmountMm = weather?.precipAmountMm,
                    dailyRainLabelText = buildDailyRainLabel(
                        date = date,
                        today = today,
                        isPastDate = isPastDate,
                        iconRes = iconRes,
                        precipProbability = precip,
                        precipAmountMm = weather?.precipAmountMm,
                    ),
                    hasRainForecast = hasRainForecast,
                    columnIndex = days.size,
                    isTodayForecastFallback = isTodayForecastFallback,
                    snapshotHigh = snapshotHigh,
                    snapshotLow = snapshotLow,
                    trueActualHigh = trueActualHigh,
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

    private fun buildDailyRainLabel(
        date: LocalDate,
        today: LocalDate,
        isPastDate: Boolean,
        iconRes: Int,
        precipProbability: Int?,
        precipAmountMm: Float?,
    ): String? {
        if (date == today || isPastDate || !isRainIndicatorIcon(iconRes)) return null
        return when {
            precipProbability != null && precipProbability >= 99 && precipAmountMm != null -> formatPrecipAmount(precipAmountMm)
            precipProbability != null && precipProbability > 0 -> "$precipProbability%"
            else -> null
        }
    }

    private fun isRainIndicatorIcon(iconRes: Int): Boolean {
        return iconRes == R.drawable.ic_weather_rain || iconRes == R.drawable.ic_weather_storm
    }

    private fun formatPrecipAmount(amountMm: Float): String {
        val country = Locale.getDefault().country.uppercase(Locale.US)
        return if (country == "US" || country == "GB") {
            formatInches(amountMm / 25.4f)
        } else {
            formatMillimeters(amountMm)
        }
    }

    private fun formatInches(amountInches: Float): String {
        val precision = when {
            amountInches < 0.01f -> 3
            amountInches < 0.1f -> 3
            amountInches < 1f -> 2
            else -> 1
        }
        val formatted = String.format(Locale.US, "%.${precision}f", amountInches)
            .trimEnd('0')
            .trimEnd('.')
        return "${formatted.removePrefix("0")}in"
    }

    private fun formatMillimeters(amountMm: Float): String {
        val precision = if (amountMm < 10f) 1 else 0
        val formatted = String.format(Locale.US, "%.${precision}f", amountMm)
            .trimEnd('0')
            .trimEnd('.')
        return "${formatted.removePrefix("0")}mm"
    }
}
