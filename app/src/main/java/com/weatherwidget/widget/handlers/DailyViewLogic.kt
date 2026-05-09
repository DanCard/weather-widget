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
import com.weatherwidget.widget.DailyActualMap
import com.weatherwidget.widget.ObservationResolver
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure business logic for the daily forecast view, extracted for testability.
 */
object DailyViewLogic {
    private const val TAG = "DailyViewLogic"

    private fun isTerminalLowOnlyNwsFutureDay(
        weather: ForecastEntity?,
        date: LocalDate,
        today: LocalDate,
        weatherByDate: Map<LocalDate, ForecastEntity>,
    ): Boolean {
        if (weather?.source != WeatherSource.NWS.id) return false
        if (!date.isAfter(today)) return false
        if (weather.highTemp != null || weather.lowTemp == null) return false

        val lastNwsFutureDate =
            weatherByDate.entries
                .asSequence()
                .filter { (candidateDate, candidateWeather) ->
                    candidateDate.isAfter(today) && candidateWeather.source == WeatherSource.NWS.id
                }
                .map { it.key }
                .maxOrNull()

        return date == lastNwsFutureDate
    }

    data class TextDayData(
        val dayIndex: Int,
        val date: LocalDate,
        val dateStr: String,
        val isVisible: Boolean,
        val hasData: Boolean,
        val label: String,
        val showLabel: Boolean,
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
        dailyActuals: DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Int, Int>> = emptyMap(),
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
        currentTemp: Float? = null,
        observedAt: Long? = null,
        rainSummaryProvider: (List<HourlyForecastEntity>, LocalDate, String?, LocalDateTime) -> String? = RainAnalyzer::getRainSummary,
    ): List<TextDayData> {
        Log.d(TAG, "prepareTextDays: today=$today, weatherByDateKeys=${weatherByDate.keys}, displaySource=${displaySource.id}")

        val effectiveCenter = if (skipHistory) centerDate.plusDays(1) else centerDate
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val daySlots = listOf(-1, 0, 1, 2, 3, 4, 5, 6).mapIndexed { index, offset ->
            val date = effectiveCenter.plusDays(offset.toLong())
            val weather = weatherByDate[date]
            val isToday = date == today
            val isPast = date.isBefore(today)
            val isTerminalLowOnlyNwsFuture = isTerminalLowOnlyNwsFutureDay(weather, date, today, weatherByDate)
            
            // For future days, we need both high and low.
            // For today and past days, we can show partial data (High-only or Low-only).
            val hasData = if (!isToday && !isPast) {
                (weather != null && weather.highTemp != null && weather.lowTemp != null) ||
                    isTerminalLowOnlyNwsFuture ||
                    dailyActuals.containsKey(date)
            } else {
                (weather != null && (weather.highTemp != null || weather.lowTemp != null)) || dailyActuals.containsKey(date)
            }

            Log.d(TAG, "prepareTextDays: index=$index date=$date weather=${weather != null} hasData=$hasData high=${weather?.highTemp} low=${weather?.lowTemp} terminalNws=$isTerminalLowOnlyNwsFuture")

            val isVisible = when {
                numColumns >= 8 -> true
                numColumns == 7 -> index <= 6
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
                rainSummaryProvider(hourlyForecasts, date, displaySource.id, now)
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
            val isTerminalLowOnlyNwsFuture = isTerminalLowOnlyNwsFutureDay(weather, date, today, weatherByDate)
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
                if (!isTerminalLowOnlyNwsFuture && (highLabel == null || lowLabel == null)) {
                    val normal = climateNormals[java.time.MonthDay.from(date)]
                    if (normal != null) {
                        highLabel = formatTemp(normal.first.toFloat())
                        lowLabel = formatTemp(normal.second.toFloat())
                    }
                }
            }

            val useDirectNwsPeriodPrecip = weather?.source == WeatherSource.NWS.id && displaySource == WeatherSource.NWS
            val dayNightPrecip = if (!isPast && !isToday && weather != null && !useDirectNwsPeriodPrecip) {
                DailyForecastIconResolver.calculateDayNightPrecipProbabilities(
                    hourlyForecasts = hourlyForecasts,
                    targetDate = date,
                    now = now,
                    latitude = weather.locationLat,
                    longitude = weather.locationLon,
                    displaySource = displaySource,
                )
            } else {
                null
            }
            val dayPrecipForIcon = if (useDirectNwsPeriodPrecip) {
                weather.daytimePrecipProbability ?: weather.precipProbability
            } else {
                dayNightPrecip?.dayMax
            }
            val nightPrecipForIcon = if (useDirectNwsPeriodPrecip) {
                weather.nighttimePrecipProbability
            } else {
                dayNightPrecip?.nightMax
            }

            val iconRes =
                if (weather != null) {
                    DailyForecastIconResolver.resolveIcon(
                        weather = weather,
                        targetDate = date,
                        now = now,
                        latitude = weather.locationLat,
                        longitude = weather.locationLon,
                        dayPrecipProbability = dayPrecipForIcon,
                        nightPrecipProbability = nightPrecipForIcon,
                    )
                } else {
                    WeatherIconMapper.getIconResource(
                        condition = null,
                        precipProbability = null,
                    )
                }

            val showLabel = !(isToday && numColumns == 1)
            val label =
                if (isToday) {
                    "Today"
                } else {
                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

            TextDayData(
                dayIndex = dayIndex,
                date = date,
                dateStr = dateStr,
                isVisible = isVisible,
                hasData = if (!isToday && !isPast) {
                    (weather != null && weather.highTemp != null && weather.lowTemp != null) ||
                        isTerminalLowOnlyNwsFuture
                } else {
                    (weather != null && (weather.highTemp != null || weather.lowTemp != null)) || dailyActuals.containsKey(date)
                },
                label = label,
                showLabel = showLabel,
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
        skipYesterday: Boolean,
        skipHistory: Boolean,
        hourlyForecasts: List<HourlyForecastEntity>,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
        dailyActuals: DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Int, Int>> = emptyMap(),
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
        currentTemp: Float? = null,
        observedAt: Long? = null,
        allowTodayRainChanceLabel: Boolean = false,
        rainSummaryProvider: (List<HourlyForecastEntity>, LocalDate, String?, LocalDateTime) -> String? = RainAnalyzer::getRainSummary,
    ): List<DailyForecastGraphRenderer.DayData> {
        Log.d(TAG, "prepareGraphDays: today=$today, weatherByDateKeys=${weatherByDate.keys}, forecastSnapshotKeys=${forecastSnapshots.keys}")

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
            val isTerminalLowOnlyNwsFuture = isTerminalLowOnlyNwsFutureDay(weather, date, today, weatherByDate)

            Log.d(TAG, "prepareGraphDays: index=$index date=$date weather=${weather != null} forecast=${forecast != null} forecastsSize=${forecasts.size} terminalNws=$isTerminalLowOnlyNwsFuture")

            val label = if (isToday) "Today" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val showComparison = isPastDate

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
                    // For past-day forecast comparison, ONLY use real snapshots from the
                    // displaySource — skip GENERIC_GAP (climate-normal fallback rows) and
                    // any isClimateNormal=true row, and require both highTemp and lowTemp.
                    // NWS evening batches drop lowTemp once the day's low has passed, so
                    // we must look back at older NWS batches with both values populated.
                    val pastForecast = forecasts
                        .filter { it.source == displaySource.id }
                        .filter { !it.isClimateNormal }
                        .filter { it.highTemp != null && it.lowTemp != null }
                        .maxByOrNull { it.fetchedAt }
                    fHigh = pastForecast?.highTemp
                    fLow = pastForecast?.lowTemp

                    if (fHigh == null || fLow == null) {
                        Log.d(TAG, "prepareGraphDays: past day $date has no usable forecast snapshot from ${displaySource.id}; skipping forecast overlay")
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
                if (!isTerminalLowOnlyNwsFuture && (finalHigh == null || finalLow == null)) {
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

            val useDirectNwsPeriodPrecip = weather?.source == WeatherSource.NWS.id && displaySource == WeatherSource.NWS
            val dayNightPrecip = if (!isPastDate && !isToday && weather != null && !useDirectNwsPeriodPrecip) {
                DailyForecastIconResolver.calculateDayNightPrecipProbabilities(
                    hourlyForecasts = hourlyForecasts,
                    targetDate = date,
                    now = now,
                    latitude = weather.locationLat,
                    longitude = weather.locationLon,
                    displaySource = displaySource,
                )
            } else {
                null
            }
            val dayPrecipForIcon = if (useDirectNwsPeriodPrecip) {
                weather.daytimePrecipProbability ?: weather.precipProbability
            } else {
                dayNightPrecip?.dayMax
            }
            val nightPrecipForIcon = if (useDirectNwsPeriodPrecip) {
                weather.nighttimePrecipProbability
            } else {
                dayNightPrecip?.nightMax
            }

            val cloudCoverRatioOverride =
                resolveNoonCloudCoverRatio(
                    date = date,
                    hourlyForecasts = hourlyForecasts,
                    displaySource = displaySource,
                    weatherSourceId = weather?.source,
                )
            val cloudCoverPercent = cloudCoverRatioOverride?.let { (it * 100).toInt() }

            val iconRes =
                when {
                    weather != null ->
                        DailyForecastIconResolver.resolveIcon(
                            weather = weather,
                            targetDate = date,
                            now = now,
                            latitude = weather.locationLat,
                            longitude = weather.locationLon,
                            dayPrecipProbability = dayPrecipForIcon,
                            nightPrecipProbability = nightPrecipForIcon,
                            cloudCover = cloudCoverPercent,
                        )
                    actual != null -> WeatherIconMapper.getIconResource(
                        condition = actual.condition,
                        precipProbability = null,
                    )
                    else -> WeatherIconMapper.getIconResource(
                        condition = null,
                        precipProbability = null,
                    )
                }
            
            val rawRainSummary = if (!isPastDate) {
                rainSummaryProvider(hourlyForecasts, date, displaySource.id, now)
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

            val dailyRainLabelText = buildDailyRainLabel(
                date = date,
                today = today,
                isPastDate = isPastDate,
                precipProbability = precip,
                precipAmountMm = weather?.precipAmountMm,
                dailyPrecipProbability = weather?.precipProbability,
                dayPrecipProbability = dayPrecipForIcon,
                nightPrecipProbability = nightPrecipForIcon,
                allowTodayRainChanceLabel = allowTodayRainChanceLabel,
            )

            days.add(
                DailyForecastGraphRenderer.DayData(
                    date = date,
                    label = label,
                    high = finalHigh,
                    low = finalLow,
                    bottomStackLow = bottomStackLow ?: finalLow,
                    iconRes = iconRes,
                    isSunny = WeatherIconMapper.isSunny(iconRes),
                    isRainy = WeatherIconMapper.isPrecipitation(iconRes),
                    isMixed = WeatherIconMapper.isMixed(iconRes),
                    isToday = isToday,
                    isPast = isPastDate,
                    isClimateNormal = isClimateOverlay,
                    isSourceGapFallback = weather?.source == WeatherSource.GENERIC_GAP.id,
forecastHigh = fHigh,
    forecastLow = fLow,
    rainData = DailyForecastGraphRenderer.RainData(
        rainSummary = rainSummary,
        dailyPrecipProbability = precip,
        nighttimePrecipProbability = weather?.nighttimePrecipProbability,
        dailyPrecipAmountMm = weather?.precipAmountMm,
        dailyRainLabelText = dailyRainLabelText,
        nightRainLabelText = buildNightRainLabel(
            date = date,
            today = today,
            isPastDate = isPastDate,
            dailyRainLabelText = dailyRainLabelText,
            nightPrecipProbability = weather?.nighttimePrecipProbability,
        ),
        hasRainForecast = hasRainForecast,
    ),
    columnIndex = days.size,
                    isTodayForecastFallback = isTodayForecastFallback,
                    snapshotHigh = snapshotHigh,
                    snapshotLow = snapshotLow,
                    trueActualHigh = trueActualHigh,
                    cloudCoverRatioOverride = cloudCoverRatioOverride,
                    daysFromToday = ChronoUnit.DAYS.between(today, date).toInt(),
                )
            )
        }
        return days
    }

    private fun resolveNoonCloudCoverRatio(
        date: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        weatherSourceId: String?,
    ): Float? {
        val targetSourceId = if (weatherSourceId == WeatherSource.GENERIC_GAP.id) {
            WeatherSource.GENERIC_GAP.id
        } else {
            displaySource.id
        }
        val noon = date.atTime(12, 0)

        val closestCloudCover = hourlyForecasts
            .asSequence()
            .filter { it.source == targetSourceId }
            .mapNotNull { forecast ->
                val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(forecast.dateTime), ZoneId.systemDefault())
                val cloudCover = forecast.cloudCover ?: return@mapNotNull null
                if (localDateTime.toLocalDate() != date) return@mapNotNull null
                Triple(abs(ChronoUnit.MINUTES.between(noon, localDateTime)), localDateTime, cloudCover)
            }
            .minWithOrNull(compareBy<Triple<Long, LocalDateTime, Int>> { it.first }.thenBy { it.second })
            ?.third

        return closestCloudCover?.coerceIn(0, 100)?.div(100f)
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
        precipProbability: Int?,
        precipAmountMm: Float?,
        dailyPrecipProbability: Int? = null,
        dayPrecipProbability: Int? = null,
        nightPrecipProbability: Int? = null,
        allowTodayRainChanceLabel: Boolean = false,
    ): String? {
        if (isPastDate) {
            Log.d(TAG, "buildDailyRainLabel skipping past date=$date")
            return null
        }
        if (date == today) {
            if (dailyPrecipProbability != null && dailyPrecipProbability >= 95 && precipAmountMm != null) {
                Log.d(TAG, "buildDailyRainLabel today label: date=$date dailyPrecip=$dailyPrecipProbability% amount=${precipAmountMm}mm")
                return formatPrecipAmount(precipAmountMm)
            }
            if (allowTodayRainChanceLabel && precipProbability != null && precipProbability > 0) {
                Log.d(TAG, "buildDailyRainLabel today fallback label: date=$date precipProbability=$precipProbability%")
                return "$precipProbability%"
            }
            Log.d(TAG, "buildDailyRainLabel skipping today: date=$date dailyPrecip=$dailyPrecipProbability precipAmount=$precipAmountMm")
            return null
        }
        val daysFromToday = ChronoUnit.DAYS.between(today, date).toInt()
        val dayMinProb = DailyForecastIconResolver.getMinimumPrecipProbabilityDay(daysFromToday)
        val nightMinProb = DailyForecastIconResolver.getMinimumPrecipProbabilityNight(daysFromToday)
        val dayPrecip = dayPrecipProbability ?: precipProbability ?: dailyPrecipProbability
        val nightPrecip = nightPrecipProbability ?: dayPrecip

        val daySuppresses = dayPrecip != null && dayPrecip < dayMinProb
        if (daySuppresses) {
            Log.d(TAG, "buildDailyRainLabel suppressing label for $date: dayPrecip=$dayPrecip dayMin=$dayMinProb nightPrecip=$nightPrecip nightMin=$nightMinProb")
            return null
        }
        val result = when {
            precipProbability != null && precipProbability >= 99 && precipAmountMm != null -> formatPrecipAmount(precipAmountMm)
            precipProbability != null && precipProbability > 0 -> "$precipProbability%"
            else -> null
        }
        if (result == null) {
            Log.d(TAG, "buildDailyRainLabel no label produced: date=$date precipProbability=$precipProbability precipAmount=$precipAmountMm")
        } else {
            Log.d(TAG, "buildDailyRainLabel label for $date: $result (precipProbability=$precipProbability%)")
        }
        return result
    }

    private fun buildNightRainLabel(
        date: LocalDate,
        today: LocalDate,
        isPastDate: Boolean,
        dailyRainLabelText: String?,
        nightPrecipProbability: Int?,
    ): String? {
        if (isPastDate) {
            Log.d(TAG, "buildNightRainLabel skipping past date=$date")
            return null
        }
        val probability = nightPrecipProbability ?: run {
            Log.d(TAG, "buildNightRainLabel skipping null night precip: date=$date")
            return null
        }
        val daysFromToday = ChronoUnit.DAYS.between(today, date).toInt()
        if (daysFromToday < 0) return null

        val threshold = DailyForecastIconResolver.getMinimumPrecipProbabilityNight(daysFromToday)
        val shouldShow = probability >= threshold

        if (!shouldShow) {
            Log.d(TAG, "buildNightRainLabel suppressing label for $date: nightPrecip=$probability threshold=$threshold daysFromToday=$daysFromToday")
            return null
        }

        val result = "$probability%"
        Log.d(TAG, "buildNightRainLabel label for $date: $result (threshold=$threshold daysFromToday=$daysFromToday)")
        return result
    }

}
