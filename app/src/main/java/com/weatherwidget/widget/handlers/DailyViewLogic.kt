package com.weatherwidget.widget.handlers

import android.util.Log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.shared.util.RainAnalyzer
import com.weatherwidget.util.DailyForecastIconResolver
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.DailyActualMap
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure business logic for the daily forecast view, extracted for testability.
 */
object DailyViewLogic {
    private const val TAG = "DailyViewLogic"

    internal fun entityRainSummary(
        hourly: List<HourlyForecastEntity>,
        date: LocalDate,
        source: String?,
        now: LocalDateTime,
    ): String? =
        RainAnalyzer.getRainSummary(hourly.map { it.toHourlyForecast() }, date, source, now)

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

    data class PreparedGraphDay(
        val renderDay: DailyForecastGraphRenderer.DayData,
        val rainSummary: String?,
        val hasRainForecast: Boolean,
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
        todayPrecipProbability: Int? = null,
        dailyActuals: DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Float, Float>> = emptyMap(),
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
        currentTemp: Float? = null,
        observedAt: Long? = null,
        rainSummaryProvider: (List<HourlyForecastEntity>, LocalDate, String?, LocalDateTime) -> String? = ::entityRainSummary,
        todayLabel: String,
        centerLat: Double? = null,
        centerLon: Double? = null,
    ): List<TextDayData> {
        Log.d(TAG, "prepareTextDays: today=$today, weatherByDateKeys=${weatherByDate.keys}, displaySource=${displaySource.id}")

        val effectiveCenter = if (skipHistory && numColumns >= 3) centerDate.plusDays(1) else centerDate
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val daySlots = listOf(-1, 0, 1, 2, 3, 4, 5, 6).mapIndexed { index, offset ->
            val date = effectiveCenter.plusDays(offset.toLong())
            val weather = weatherByDate[date]
            val isToday = date == today
            val isPast = date.isBefore(today)
            val isTerminalLowOnlyNwsFuture = DailyFutureDayResolver.isTerminalLowOnlyNwsFutureDay(weather, date, today, weatherByDate)

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
            }

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
                val completeSnapshot =
                    DailyTodayResolver.completeSameSiteReplacement(weather, forecastSnapshots?.get(date) ?: emptyList())
                if (completeSnapshot != null) {
                    Log.d(TAG, "prepareTextDays: today weather incomplete (high=${weather.highTemp} low=${weather.lowTemp}), using complete snapshot from ${Instant.ofEpochMilli(completeSnapshot.fetchedAt)}")
                    weather = completeSnapshot
                }
            }
            val isPast = date.isBefore(today)
            val isTerminalLowOnlyNwsFuture = DailyFutureDayResolver.isTerminalLowOnlyNwsFutureDay(weather, date, today, weatherByDate)
            val precip = if (isToday) todayPrecipProbability else weather?.precipProbability

            val useCelsius = stateManager?.useCelsius() ?: false
            val formatTemp = { v: Float? -> com.weatherwidget.shared.util.TempUtils.formatTemp(v, useCelsius) }

            var highLabel: String? = formatTemp(weather?.highTemp)
            var lowLabel: String? = formatTemp(weather?.lowTemp)
            var isTodayForecastFallback = false

            if (isPast) {
                val row = dailyActuals[date]
                val obsHigh = row?.computedHighTemp
                val obsLow = row?.computedLowTemp
                val pastForecast =
                    if (obsHigh == null && obsLow == null && (row?.forecastHighTemp == null || row.forecastLowTemp == null)) {
                        forecastSnapshots?.get(date)
                            ?.filter { it.source == displaySource.id && !it.isClimateNormal && it.highTemp != null && it.lowTemp != null }
                            ?.maxByOrNull { it.fetchedAt }
                    } else {
                        null
                    }
                highLabel = formatTemp(obsHigh ?: row?.forecastHighTemp ?: pastForecast?.highTemp)
                lowLabel = formatTemp(obsLow ?: row?.forecastLowTemp ?: pastForecast?.lowTemp)
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
                highLabel = formatTemp(visibleHigh)
                lowLabel = formatTemp(visibleLow)
                isTodayForecastFallback =
                    tripleValues.solidLineHigh == null &&
                        !tripleValues.hasActualLow &&
                        (visibleHigh != null || visibleLow != null)
            } else {
                if (weather != null && !isTerminalLowOnlyNwsFuture && (highLabel == null || lowLabel == null)) {
                    val normal = climateNormals[java.time.MonthDay.from(date)]
                    if (normal != null) {
                        highLabel = formatTemp(normal.first)
                        lowLabel = formatTemp(normal.second)
                    }
                }
            }

            val resolvedPrecip = DailyForecastIconResolver.resolveDailyLabelPrecip(
                weather = weather,
                hourlyForecasts = hourlyForecasts,
                targetDate = date,
                isPast = isPast,
                displaySource = displaySource,
                actual = dailyActuals[date],
                centerLat = centerLat,
                centerLon = centerLon,
            )
            val dayPrecipForIcon = resolvedPrecip.dayPrecip
            val nightPrecipForIcon = resolvedPrecip.nightPrecip
            Log.v(
                TAG,
                "resolveDailyLabelPrecip: mode=TEXT date=$date source=${displaySource.id} " +
                    "day=$dayPrecipForIcon night=$nightPrecipForIcon center=$centerLat,$centerLon",
            )

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
                    todayLabel
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

    fun prepareGraphDays(request: GraphDayRequest): List<DailyForecastGraphRenderer.DayData> =
        prepareGraphDayInputs(request).map(PreparedGraphDay::renderDay)

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
        todayPrecipProbability: Int? = null,
        dailyActuals: DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Float, Float>> = emptyMap(),
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
        currentTemp: Float? = null,
        observedAt: Long? = null,
        allowTodayRainChanceLabel: Boolean = false,
        rainSummaryProvider: (List<HourlyForecastEntity>, LocalDate, String?, LocalDateTime) -> String? = ::entityRainSummary,
        todayLabel: String,
        centerLat: Double? = null,
        centerLon: Double? = null,
    ): List<DailyForecastGraphRenderer.DayData> =
        prepareGraphDays(
            GraphDayRequest(
                now = now,
                centerDate = centerDate,
                today = today,
                weatherByDate = weatherByDate,
                forecastSnapshots = forecastSnapshots,
                numColumns = numColumns,
                displaySource = displaySource,
                skipYesterday = skipYesterday,
                skipHistory = skipHistory,
                hourlyForecasts = hourlyForecasts,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                todayPrecipProbability = todayPrecipProbability,
                dailyActuals = dailyActuals,
                climateNormals = climateNormals,
                currentTemps = currentTemps,
                currentTemp = currentTemp,
                observedAt = observedAt,
                allowTodayRainChanceLabel = allowTodayRainChanceLabel,
                rainSummaryProvider = rainSummaryProvider,
                todayLabel = todayLabel,
                centerLat = centerLat,
                centerLon = centerLon,
            )
        )

    fun prepareGraphDayInputs(
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
        todayPrecipProbability: Int? = null,
        dailyActuals: DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Float, Float>> = emptyMap(),
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
        currentTemp: Float? = null,
        observedAt: Long? = null,
        allowTodayRainChanceLabel: Boolean = false,
        rainSummaryProvider: (List<HourlyForecastEntity>, LocalDate, String?, LocalDateTime) -> String? = ::entityRainSummary,
        todayLabel: String,
        centerLat: Double? = null,
        centerLon: Double? = null,
    ): List<PreparedGraphDay> =
        prepareGraphDayInputs(
            GraphDayRequest(
                now = now,
                centerDate = centerDate,
                today = today,
                weatherByDate = weatherByDate,
                forecastSnapshots = forecastSnapshots,
                numColumns = numColumns,
                displaySource = displaySource,
                skipYesterday = skipYesterday,
                skipHistory = skipHistory,
                hourlyForecasts = hourlyForecasts,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                todayPrecipProbability = todayPrecipProbability,
                dailyActuals = dailyActuals,
                climateNormals = climateNormals,
                currentTemps = currentTemps,
                currentTemp = currentTemp,
                observedAt = observedAt,
                allowTodayRainChanceLabel = allowTodayRainChanceLabel,
                rainSummaryProvider = rainSummaryProvider,
                todayLabel = todayLabel,
                centerLat = centerLat,
                centerLon = centerLon,
            )
        )

    fun prepareGraphDayInputs(request: GraphDayRequest): List<PreparedGraphDay> {
        val now = request.now
        val centerDate = request.centerDate
        val today = request.today
        val weatherByDate = request.weatherByDate
        val forecastSnapshots = request.forecastSnapshots
        val displaySource = request.displaySource
        val hourlyForecasts = request.hourlyForecasts
        val dailyActuals = request.dailyActuals
        val todayLabel = request.todayLabel
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        Log.d(TAG, "prepareGraphDays: today=$today, weatherByDateKeys=${weatherByDate.keys}, forecastSnapshotKeys=${forecastSnapshots.keys}")

        val days = mutableListOf<PreparedGraphDay>()
        val dayOffsets = NavigationUtils.getDayOffsets(request.numColumns, request.skipHistory)

        dayOffsets.forEachIndexed { index, offset ->
            val date = centerDate.plusDays(offset)
            val isToday = date == today

            val allowGapFallback = date.isAfter(today.plusDays(2))
            var weather = weatherByDate[date]
                ?: forecastSnapshots[date]?.firstOrNull { allowGapFallback || it.source != WeatherSource.GENERIC_GAP.id }
            if (isToday && weather != null && (weather.highTemp == null || weather.lowTemp == null)) {
                val completeSnapshot =
                    DailyTodayResolver.completeSameSiteReplacement(weather, forecastSnapshots[date] ?: emptyList())
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
            val isTerminalLowOnlyNwsFuture = DailyFutureDayResolver.isTerminalLowOnlyNwsFutureDay(weather, date, today, weatherByDate)

            Log.d(TAG, "prepareGraphDays: index=$index date=$date weather=${weather != null} forecast=${forecast != null} forecastsSize=${forecasts.size} terminalNws=$isTerminalLowOnlyNwsFuture")

            val label = if (isToday) todayLabel else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val showComparison = isPastDate

            val finalHigh: Float?
            val finalLow: Float?
            val fHigh: Float?
            val fLow: Float?
            var snapshotHigh: Float? = null
            var snapshotLow: Float? = null
            var snapshotIconRes: Int? = null
            val isClimateOverlay: Boolean
            val isTodayForecastFallback: Boolean
            var trueActualHigh: Float? = null
            var bottomStackLow: Float? = null
            val solidIsForecastFallback: Boolean
            val todayHasActualLow: Boolean

            if (isPastDate) {
                val pastValues = DailyPastDayResolver.resolvePastDayValues(
                    actual = actual,
                    forecasts = forecasts,
                    displaySource = displaySource,
                    date = date,
                    showComparison = showComparison,
                )
                finalHigh = pastValues.finalHigh
                finalLow = pastValues.finalLow
                fHigh = pastValues.fHigh
                fLow = pastValues.fLow
                solidIsForecastFallback = pastValues.solidIsForecastFallback
                isClimateOverlay = false
                isTodayForecastFallback = false
                todayHasActualLow = false
            } else if (isToday && (weather != null || dailyActuals.containsKey(date))) {
                val todayValues = DailyTodayResolver.resolveTodayValues(
                    date = date,
                    today = today,
                    now = now,
                    displaySource = displaySource,
                    weather = weather,
                    dailyActuals = dailyActuals,
                    actual = actual,
                    forecasts = forecasts,
                    hourlyForecasts = hourlyForecasts,
                    currentTemp = request.currentTemp,
                )
                finalHigh = todayValues.finalHigh
                finalLow = todayValues.finalLow
                fHigh = todayValues.fHigh
                fLow = todayValues.fLow
                bottomStackLow = todayValues.bottomStackLow
                snapshotHigh = todayValues.snapshotHigh
                snapshotLow = todayValues.snapshotLow
                snapshotIconRes = todayValues.snapshotIconRes
                trueActualHigh = todayValues.trueActualHigh
                todayHasActualLow = todayValues.todayHasActualLow
                isTodayForecastFallback = todayValues.isTodayForecastFallback
                solidIsForecastFallback = false
                isClimateOverlay = false
            } else {
                val futureValues = DailyFutureDayResolver.resolveFutureDayValues(
                    weather = weather,
                    forecast = forecast,
                    date = date,
                    isTerminalLowOnlyNwsFuture = isTerminalLowOnlyNwsFuture,
                    climateNormals = request.climateNormals,
                    showComparison = showComparison,
                )
                finalHigh = futureValues.finalHigh
                finalLow = futureValues.finalLow
                fHigh = futureValues.fHigh
                fLow = futureValues.fLow
                isClimateOverlay = futureValues.isClimateOverlay
                solidIsForecastFallback = false
                isTodayForecastFallback = false
                todayHasActualLow = false
            }

            val resolvedPrecip = DailyForecastIconResolver.resolveDailyLabelPrecip(
                weather = weather,
                hourlyForecasts = hourlyForecasts,
                targetDate = date,
                isPast = isPastDate,
                displaySource = displaySource,
                actual = actual,
                centerLat = request.centerLat,
                centerLon = request.centerLon,
            )
            val dayPrecipForIcon = resolvedPrecip.dayPrecip
            val nightPrecipForIcon = resolvedPrecip.nightPrecip
            Log.v(
                TAG,
                "resolveDailyLabelPrecip: mode=GRAPH date=$date source=${displaySource.id} " +
                    "day=$dayPrecipForIcon night=$nightPrecipForIcon center=${request.centerLat},${request.centerLon}",
            )

            val storedNoonCloud = if (isPastDate) actual?.noonCloudPercent else null
            val cloudCoverRatioOverride =
                storedNoonCloud?.let { it / 100f }
                    ?: resolveNoonCloudCoverRatio(
                        date = date,
                        hourlyForecasts = hourlyForecasts,
                        displaySource = displaySource,
                        weatherSourceId = weather?.source,
                    )
            val measuredCloudCoverPercent =
                storedNoonCloud
                    ?: resolveMeasuredNoonCloudCoverPercent(
                        date = date,
                        hourlyForecasts = hourlyForecasts,
                        displaySource = displaySource,
                        weatherSourceId = weather?.source,
                    )

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
                            cloudCover = measuredCloudCoverPercent,
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

            Log.d(
                TAG,
                "cloudDecision: date=$date isPast=$isPastDate weatherPresent=${weather != null}" +
                    " actualPresent=${actual != null} iconRes=$iconRes isMixed=${WeatherIconMapper.isMixed(iconRes)}" +
                    " cloudCoverRatioOverride=$cloudCoverRatioOverride measuredCloudCover=$measuredCloudCoverPercent" +
                    " storedNoonCloud=$storedNoonCloud hasOverlay=${fHigh != null && fLow != null}",
            )

            val rawRainSummary = if (!isPastDate) {
                request.rainSummaryProvider(hourlyForecasts, date, displaySource.id, now)
            } else null

            val precip = if (isToday) request.todayPrecipProbability else weather?.precipProbability
            val hasRainForecast = DayClickHelper.hasRainForecast(rawRainSummary, precip)

            val nearTermLimit = today.plusDays(2)
            val rainSummary = if (!date.isBefore(today) && !date.isAfter(nearTermLimit)) {
                if (isToday && rawRainSummary != null && request.stateManager?.wasRainShownToday(request.appWidgetId, todayStr) == true) {
                    null
                } else {
                    rawRainSummary
                }
            } else null

            val dailyRainLabelText = buildDailyRainLabel(
                date = date,
                today = today,
                isPastDate = isPastDate,
                precipAmountMm = if (isPastDate) {
                    actual?.forecastPrecipAmountMm ?: weather?.precipAmountMm
                } else {
                    weather?.precipAmountMm
                },
                dayPrecipProbability = dayPrecipForIcon,
                allowTodayRainChanceLabel = request.allowTodayRainChanceLabel,
                observedPrecipAmountMm = com.weatherwidget.shared.util.DailyRainLabels.resolveObservedDayPrecip(
                    dayMm = actual?.precipDayMm,
                    nightMm = actual?.precipNightMm,
                    totalMm = actual?.precipAmountMm,
                ),
            )

            days.add(
                PreparedGraphDay(
                    renderDay = DailyForecastGraphRenderer.DayData(
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
                        solidIsForecastFallback = solidIsForecastFallback,
                        dashedLineHigh = fHigh,
                        dashedLineLow = fLow,
                        rainData = DailyForecastGraphRenderer.RainLabelData(
                            dailyPrecipProbability = dayPrecipForIcon,
                            nighttimePrecipProbability = nightPrecipForIcon,
                            dailyRainLabelText = dailyRainLabelText,
                            nightRainLabelText = buildNightRainLabel(
                                date = date,
                                today = today,
                                isPastDate = isPastDate,
                                dailyRainLabelText = dailyRainLabelText,
                                nightPrecipProbability = nightPrecipForIcon,
                                observedNightPrecipMm = actual?.precipNightMm,
                            ),
                        ),
                        columnIndex = days.size,
                        isTodayForecastFallback = isTodayForecastFallback,
                        todayHasActualLow = todayHasActualLow,
                        snapshotHigh = snapshotHigh,
                        snapshotLow = snapshotLow,
                        snapshotIconRes = snapshotIconRes,
                        ghostLineHigh = trueActualHigh,
                        cloudCoverRatioOverride = cloudCoverRatioOverride,
                        daysFromToday = ChronoUnit.DAYS.between(today, date).toInt(),
                        nowHour = if (isToday) now.hour else null,
                    ),
                    rainSummary = rainSummary,
                    hasRainForecast = hasRainForecast,
                ),
            )
        }
        return days
    }

    private fun mapHourlyForecastsForNoonCloud(
        hourlyForecasts: List<HourlyForecastEntity>,
    ): List<com.weatherwidget.data.model.HourlyForecast> =
        hourlyForecasts.map { it.toHourlyForecast() }

    private fun resolveNoonCloudCoverRatio(
        date: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        weatherSourceId: String?,
    ): Float {
        val ratio = com.weatherwidget.shared.util.DailyNoonCloudCover.resolveNoonCloudCoverRatio(
            hourly = mapHourlyForecastsForNoonCloud(hourlyForecasts),
            date = date,
            displaySourceId = displaySource.id,
            rowSourceId = weatherSourceId,
        )
        Log.d(TAG, "resolveNoonCloudCoverRatio: date=$date displaySource=${displaySource.id} weatherSourceId=$weatherSourceId ratio=$ratio")
        return ratio
    }

    private fun resolveMeasuredNoonCloudCoverPercent(
        date: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        weatherSourceId: String?,
    ): Int? =
        com.weatherwidget.shared.util.DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(
            hourly = mapHourlyForecastsForNoonCloud(hourlyForecasts),
            date = date,
            displaySourceId = displaySource.id,
            rowSourceId = weatherSourceId,
        )

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
