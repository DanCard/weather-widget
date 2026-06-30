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
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>>? = null,
        hourlyForecasts: List<HourlyForecastEntity>,
        numColumns: Int,
        displaySource: WeatherSource,
        skipHistory: Boolean = false,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
        dailyActuals: DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Float, Float>> = emptyMap(),
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
            var weather = weatherByDate[date]
            val isToday = date == today
            if (isToday && weather != null && (weather.highTemp == null || weather.lowTemp == null)) {
                // Latest batch for Today is incomplete (likely NWS evening drop).
                // Search snapshots for the most recent complete forecast from the same source.
                val completeSnapshot = (forecastSnapshots?.get(date) ?: emptyList()).filter {
                    it.source == weather.source && it.highTemp != null && it.lowTemp != null
                }.maxByOrNull { it.fetchedAt }
                if (completeSnapshot != null) {
                    Log.d(TAG, "prepareTextDays: today weather incomplete (high=${weather.highTemp} low=${weather.lowTemp}), using complete snapshot from ${Instant.ofEpochMilli(completeSnapshot.fetchedAt)}")
                    weather = completeSnapshot
                }
            }
            val isPast = date.isBefore(today)
            val isTerminalLowOnlyNwsFuture = isTerminalLowOnlyNwsFutureDay(weather, date, today, weatherByDate)
            val precip = if (isToday) todayNext8HourPrecipProbability else weather?.precipProbability
            
            // Round future days to integers to maintain UI consistency.
            // Today and historical days are permitted to show decimals for precision.
            // Show the tenth for any non-integer value (whole degrees stay clean via the
            // ".0" suppression in TempUtils.formatTemp), for today/past and future alike.
            val formatTemp = { v: Float? -> com.weatherwidget.util.TempUtils.formatTemp(v) }
            
            var highLabel: String? = formatTemp(weather?.highTemp)
            var lowLabel: String? = formatTemp(weather?.lowTemp)
            var isTodayForecastFallback = false

            if (isPast) {
                val obsHigh = dailyActuals[date]?.highTemp
                val obsLow = dailyActuals[date]?.lowTemp
                highLabel = com.weatherwidget.util.TempUtils.formatTemp(obsHigh)
                lowLabel = com.weatherwidget.util.TempUtils.formatTemp(obsLow)
            } else if (isToday && (weather != null || dailyActuals.containsKey(date))) {
                val resolvedCurrentTemp = currentTemp ?: com.weatherwidget.widget.ObservationResolver.resolveObservedCurrentTemp(
                    currentTemps, displaySource
                )?.temperature

                val tripleValues = com.weatherwidget.util.DailyActualsEstimator.calculateTodayTripleLineValues(
                    hourlyForecasts, today, now, displaySource, weather, dailyActuals,
                    currentTemp = resolvedCurrentTemp
                )

                val visibleHigh = listOfNotNull(tripleValues.solidLineHigh, tripleValues.dashedLineHigh, tripleValues.ghostLineHigh).maxOrNull()
                val visibleLow = tripleValues.solidLineLow ?: tripleValues.dashedLineLow
                highLabel = com.weatherwidget.util.TempUtils.formatTemp(visibleHigh)
                lowLabel = com.weatherwidget.util.TempUtils.formatTemp(visibleLow)
                isTodayForecastFallback =
                    tripleValues.solidLineHigh == null &&
                        tripleValues.solidLineLow == null &&
                        (visibleHigh != null || visibleLow != null)
            } else {
                // Future day fallback to climate normals if missing
                if (!isTerminalLowOnlyNwsFuture && (highLabel == null || lowLabel == null)) {
                    val normal = climateNormals[java.time.MonthDay.from(date)]
                    if (normal != null) {
                        highLabel = formatTemp(normal.first)
                        lowLabel = formatTemp(normal.second)
                    }
                }
            }

            // Day/night precip % for the icon + label, via the shared selection (parity with desktop).
            val resolvedPrecip = DailyForecastIconResolver.resolveDailyLabelPrecip(
                weather = weather,
                hourlyForecasts = hourlyForecasts,
                targetDate = date,
                isPast = isPast,
                displaySource = displaySource,
            )
            val dayPrecipForIcon = resolvedPrecip.dayPrecip
            val nightPrecipForIcon = resolvedPrecip.nightPrecip


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
        climateNormals: Map<java.time.MonthDay, Pair<Float, Float>> = emptyMap(),
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
            val isToday = date == today

            // Try preferred source first, then any available snapshot for the given date.
            // GENERIC_GAP filler is only allowed for long-term future days (> today+2); for history
            // and today/+1/+2 a snapshot fallback must not introduce a GENERIC_GAP weather row.
            val allowGapFallback = date.isAfter(today.plusDays(2))
            var weather = weatherByDate[date]
                ?: forecastSnapshots[date]?.firstOrNull { allowGapFallback || it.source != WeatherSource.GENERIC_GAP.id }
            if (isToday && weather != null && (weather.highTemp == null || weather.lowTemp == null)) {
                // Latest batch for Today is incomplete (likely NWS evening drop).
                // Search snapshots for the most recent complete forecast from the same source.
                val completeSnapshot = forecastSnapshots[date]?.filter {
                    it.source == weather.source && it.highTemp != null && it.lowTemp != null
                }?.maxByOrNull { it.fetchedAt }
                if (completeSnapshot != null) {
                    Log.d(TAG, "prepareGraphDays: today weather incomplete (high=${weather.highTemp} low=${weather.lowTemp}), using complete snapshot from ${Instant.ofEpochMilli(completeSnapshot.fetchedAt)}")
                    weather = completeSnapshot
                }
            }
            val actual = dailyActuals[date]
            val forecasts = forecastSnapshots[date] ?: emptyList()
            val forecast = forecasts
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .filter { it.highTemp != null && it.lowTemp != null }
                .maxByOrNull { it.fetchedAt }
                ?: forecasts.filter { it.source == displaySource.id }.maxByOrNull { it.fetchedAt }
                ?: forecasts.filter { it.source == WeatherSource.GENERIC_GAP.id }.maxByOrNull { it.fetchedAt }

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
            var snapshotIconRes: Int? = null
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
                val snapshotCandidates = forecasts
                    .filter { it.source == displaySource.id }
                    .filter { it.highTemp != null && it.lowTemp != null }
                val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val snapshot = com.weatherwidget.shared.util.DailySnapshotSelector.selectPriorDaySnapshot(
                    snapshotCandidates, nowMillis, { it.fetchedAt },
                )

                snapshotIconRes = snapshot?.let { w ->
                    DailyForecastIconResolver.resolveIcon(
                        weather = w,
                        targetDate = date,
                        now = now,
                        latitude = w.locationLat,
                        longitude = w.locationLon,
                        dayPrecipProbability = w.daytimePrecipProbability ?: w.precipProbability,
                        nightPrecipProbability = w.nighttimePrecipProbability,
                    )
                }

                val tripleValues = com.weatherwidget.util.DailyActualsEstimator.calculateTodayTripleLineValues(
                    hourlyForecasts, today, now, displaySource, weather, dailyActuals,
                    currentTemp = currentTemp,
                    snapshotHigh = snapshot?.highTemp,
                    snapshotLow = snapshot?.lowTemp,
                    snapshotIconRes = snapshotIconRes,
                )

                finalHigh = tripleValues.solidLineHigh ?: tripleValues.dashedLineHigh
                finalLow = tripleValues.solidLineLow ?: tripleValues.dashedLineLow
                fHigh = tripleValues.dashedLineHigh
                fLow = tripleValues.dashedLineLow
                bottomStackLow = com.weatherwidget.shared.util.DailyDayValueResolver.effectiveLowForLabel(
                    isToday = true,
                    solidLow = tripleValues.solidLineLow,
                    forecastLow = tripleValues.dashedLineLow,
                    nowHour = now.hour,
                )
                snapshotHigh = tripleValues.snapshotHigh
                snapshotLow = tripleValues.snapshotLow
                snapshotIconRes = tripleValues.snapshotIconRes
                trueActualHigh = tripleValues.ghostLineHigh
                isTodayForecastFallback =
                    tripleValues.solidLineHigh == null &&
                        tripleValues.solidLineLow == null &&
                        (finalHigh != null || finalLow != null)
            } else {
                // Future day
                if (!isTerminalLowOnlyNwsFuture && (finalHigh == null || finalLow == null)) {
                    val normal = climateNormals[java.time.MonthDay.from(date)]
                    if (normal != null) {
                        finalHigh = normal.first
                        finalLow = normal.second
                        isClimateOverlay = true
                    }
                }

                if (showComparison) {
                    fHigh = forecast?.highTemp
                    fLow = forecast?.lowTemp
                }
            }

            // Day/night precip % for the icon + label, via the shared selection (parity with desktop).
            val resolvedPrecip = DailyForecastIconResolver.resolveDailyLabelPrecip(
                weather = weather,
                hourlyForecasts = hourlyForecasts,
                targetDate = date,
                isPast = isPastDate,
                displaySource = displaySource,
            )
            val dayPrecipForIcon = resolvedPrecip.dayPrecip
            val nightPrecipForIcon = resolvedPrecip.nightPrecip


            val cloudCoverRatioOverride =
                resolveNoonCloudCoverRatio(
                    date = date,
                    hourlyForecasts = hourlyForecasts,
                    displaySource = displaySource,
                    weatherSourceId = weather?.source,
                )
            val cloudCoverPercent = (cloudCoverRatioOverride * 100).toInt()

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

            // Diagnoses why a past-day forecast overlay does/doesn't get the grey cloud-cover segment.
            Log.d(
                TAG,
                "cloudDecision: date=$date isPast=$isPastDate weatherPresent=${weather != null}" +
                    " actualPresent=${actual != null} iconRes=$iconRes isMixed=${WeatherIconMapper.isMixed(iconRes)}" +
                    " cloudCoverRatioOverride=$cloudCoverRatioOverride hasOverlay=${fHigh != null && fLow != null}",
            )

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
                precipAmountMm = weather?.precipAmountMm,
                dayPrecipProbability = dayPrecipForIcon,
                allowTodayRainChanceLabel = allowTodayRainChanceLabel,
                observedPrecipAmountMm = actual?.precipDayMm ?: actual?.precipAmountMm,
            )

            days.add(
                DailyForecastGraphRenderer.DayData(
                    date = date,
                    label = label,
                    solidLineHigh = finalHigh,
                    solidLineLow = finalLow,
                    bottomStackLow = bottomStackLow ?: finalLow,
                    iconRes = iconRes,
                    isSunny = WeatherIconMapper.isSunny(iconRes),
                    isRainy = WeatherIconMapper.isPrecipitation(iconRes),
                    isMixed = WeatherIconMapper.isMixed(iconRes),
                    isToday = isToday,
                    isPast = isPastDate,
                    isClimateNormal = isClimateOverlay,
                    isSourceGapFallback = weather?.source == WeatherSource.GENERIC_GAP.id,
                    dashedLineHigh = fHigh,
                    dashedLineLow = fLow,
                    rainData = DailyForecastGraphRenderer.RainData(
                        rainSummary = rainSummary,
                        dailyPrecipProbability = precip,
                        nighttimePrecipProbability = nightPrecipForIcon,
                        dailyPrecipAmountMm = weather?.precipAmountMm,
                        dailyRainLabelText = dailyRainLabelText,
                        nightRainLabelText = buildNightRainLabel(
                            date = date,
                            today = today,
                            isPastDate = isPastDate,
                            dailyRainLabelText = dailyRainLabelText,
                            nightPrecipProbability = nightPrecipForIcon,
                            observedNightPrecipMm = actual?.precipNightMm,
                        ),
                        hasRainForecast = hasRainForecast,
                    ),
                    columnIndex = days.size,
                    isTodayForecastFallback = isTodayForecastFallback,
                    snapshotHigh = snapshotHigh,
                    snapshotLow = snapshotLow,
                    snapshotIconRes = snapshotIconRes,
                    ghostLineHigh = trueActualHigh,
                    cloudCoverRatioOverride = cloudCoverRatioOverride,
                    daysFromToday = ChronoUnit.DAYS.between(today, date).toInt(),
                    nowHour = if (isToday) now.hour else null,
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
    ): Float {
        // Delegate to the shared single source of truth (source-filtered, GENERIC_GAP-aware) so
        // Android and desktop compute the day's noon cloud cover identically.
        val mapped = hourlyForecasts.map {
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = it.dateTime,
                temperature = it.temperature,
                condition = it.condition,
                cloudCover = it.cloudCover,
                source = it.source,
            )
        }
        val ratio = com.weatherwidget.shared.util.DailyNoonCloudCover.resolveNoonCloudCoverRatio(
            hourly = mapped,
            date = date,
            displaySourceId = displaySource.id,
            rowSourceId = weatherSourceId,
        )
        Log.d(TAG, "resolveNoonCloudCoverRatio: date=$date displaySource=${displaySource.id} weatherSourceId=$weatherSourceId ratio=$ratio")
        return ratio
    }

    private fun buildDailyRainLabel(
        date: LocalDate,
        today: LocalDate,
        isPastDate: Boolean,
        precipAmountMm: Float?,
        dayPrecipProbability: Int? = null,
        allowTodayRainChanceLabel: Boolean = false,
        observedPrecipAmountMm: Float? = null,
    ): String? = com.weatherwidget.shared.util.DailyRainLabels.buildDailyRainLabel(
        date = date,
        today = today,
        isPastDate = isPastDate,
        precipAmountMm = precipAmountMm,
        dayPrecipProbability = dayPrecipProbability,
        allowTodayRainChanceLabel = allowTodayRainChanceLabel,
        observedPrecipAmountMm = observedPrecipAmountMm,
    )

    // dailyRainLabelText is retained in the signature for call-site symmetry but is unused.
    private fun buildNightRainLabel(
        date: LocalDate,
        today: LocalDate,
        isPastDate: Boolean,
        dailyRainLabelText: String?,
        nightPrecipProbability: Int?,
        observedNightPrecipMm: Float? = null,
    ): String? = com.weatherwidget.shared.util.DailyRainLabels.buildNightRainLabel(
        date = date,
        today = today,
        isPastDate = isPastDate,
        nightPrecipProbability = nightPrecipProbability,
        observedNightPrecipMm = observedNightPrecipMm,
    )

}
