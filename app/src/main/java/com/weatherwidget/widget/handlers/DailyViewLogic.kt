package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.graph.AdaptiveBarStrokes
import com.weatherwidget.shared.util.DailyColumnSource
import com.weatherwidget.shared.util.PastDayForecastOverlay
import android.util.Log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toDailyForecast
import com.weatherwidget.data.local.toDailyForecastSnapshot
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

    /** Today's row for both paths, via the rule shared with desktop ([DailyTodayResolver.resolveTodayRow]). */
    private fun resolveTodayWeather(
        caller: String,
        batchRow: ForecastEntity?,
        snapshots: List<ForecastEntity>?,
        displaySource: WeatherSource,
    ): ForecastEntity? {
        val chosen = DailyTodayResolver.resolveTodayRow(batchRow, snapshots.orEmpty(), displaySource.id)
        if (chosen != null && chosen !== batchRow) {
            Log.d(
                TAG,
                "$caller: today weather ${if (batchRow == null) "missing" else "incomplete (high=${batchRow.highTemp} low=${batchRow.lowTemp})"}, " +
                    "using stored row from ${Instant.ofEpochMilli(chosen.fetchedAt)} (high=${chosen.highTemp} low=${chosen.lowTemp})",
            )
        }
        return chosen
    }

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
        val todayWeather = resolveTodayWeather("prepareTextDays", weatherByDate[today], forecastSnapshots?.get(today), displaySource)

        val daySlots = listOf(-1, 0, 1, 2, 3, 4, 5, 6).mapIndexed { index, offset ->
            val date = effectiveCenter.plusDays(offset.toLong())
            val isToday = date == today
            val weather = if (isToday) todayWeather else weatherByDate[date]
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
            val isToday = date == today
            val weather = if (isToday) todayWeather else weatherByDate[date]
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
                        PastDayForecastOverlay.pick(
                            forecastSnapshots?.get(date).orEmpty()
                                .filter { it.source == displaySource.id && !it.isClimateNormal },
                            { it.highTemp },
                            { it.lowTemp },
                            { it.fetchedAt },
                        )
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
        use24HourClock: Boolean = com.weatherwidget.shared.util.DailyRainLabels.localeUses24HourClock(),
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
                use24HourClock = use24HourClock,
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
        use24HourClock: Boolean = com.weatherwidget.shared.util.DailyRainLabels.localeUses24HourClock(),
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
                use24HourClock = use24HourClock,
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
        val zone = java.time.ZoneId.systemDefault()
        val sharedHourly = hourlyForecasts.map { it.toHourlyForecast() }

        // The rows the daily view shows, as desktop's daily read returns them: today and future only
        // (today's by the shared today-row rule), partial future days filled from climate normals.
        val shownRows: Map<LocalDate, com.weatherwidget.data.model.DailyForecast> =
            com.weatherwidget.shared.util.PartialForecastDays.fillPartialFutureDays(
                daily = weatherByDate.keys.filter { !it.isBefore(today) }.sorted().mapNotNull { date ->
                    val row = if (date == today) {
                        resolveTodayWeather("prepareGraphDays", weatherByDate[date], forecastSnapshots[date], displaySource)
                    } else {
                        weatherByDate[date]
                    }
                    row?.toDailyForecast(date)
                },
                sourceId = displaySource.id,
                normals = request.climateNormals,
                today = today,
            ).associateBy { LocalDate.parse(it.date) }

        dayOffsets.forEach { offset ->
            val date = centerDate.plusDays(offset)
            val isToday = date == today
            val isPastDate = date.isBefore(today)
            val actual = dailyActuals[date]
            val shown = if (isPastDate) null else {
                shownRows[date]
                    // Display source (or long-term climate filler) only — never another provider's row.
                    ?: forecastSnapshots[date]?.firstOrNull {
                        DailyColumnSource.mayDraw(it.source, displaySource.id, date, today)
                    }?.toDailyForecast(date)
            }
            // The day's older fetches of the display source. A past day's are all of them: the shown
            // rows (ForecastSnapshotStore.shownRows) start at the newest batch's first day, as desktop's.
            val olderRows = forecastSnapshots[date].orEmpty()
                .filter { it.source == displaySource.id }
                .map { it.toDailyForecastSnapshot(date) }

            // The column rule is shared with desktop (DailyColumnBuilder; parity fixtures DailyParity).
            val column = com.weatherwidget.shared.daily.DailyColumnBuilder.build(
                date = date,
                label = if (isToday) todayLabel else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                today = today,
                now = now,
                forecast = shown,
                actual = actual,
                snapshots = olderRows,
                hourly = sharedHourly,
                currentTemp = request.currentTemp,
                displaySourceId = displaySource.id,
                centerLat = request.centerLat ?: shown?.let { weatherByDate[date]?.locationLat } ?: 0.0,
                centerLon = request.centerLon ?: shown?.let { weatherByDate[date]?.locationLon } ?: 0.0,
                use24HourClock = request.use24HourClock,
                allowTodayRainChanceLabel = request.allowTodayRainChanceLabel,
            )
            Log.d(
                TAG,
                "prepareGraphDays: date=$date shown=${shown != null} older=${olderRows.size} icon=${column.iconName}",
            )
            // Permanent diagnostics, read by DailyCloudCoverSiteParityRoboTest.
            Log.v(
                TAG,
                "resolveDailyLabelPrecip: mode=GRAPH date=$date source=${displaySource.id} " +
                    "day=${column.dayPrecipProbability} night=${column.nightPrecipProbability} center=${request.centerLat},${request.centerLon}",
            )
            if (!(isPastDate && actual?.noonCloudPercent != null)) {
                Log.d(
                    TAG,
                    "resolveNoonCloudCoverRatio: date=$date displaySource=${displaySource.id} " +
                        "weatherSourceId=${shown?.source} rowNoon=${shown?.noonCloudPercent} ratio=${column.cloudCoverRatio}",
                )
            }

            // Android-only extras: the day-tap rain summary and the text-mode fallbacks.
            val rawRainSummary = if (!isPastDate) {
                request.rainSummaryProvider(hourlyForecasts, date, displaySource.id, now)
            } else null
            val precip = if (isToday) request.todayPrecipProbability else shown?.precipProbability
            val hasRainForecast = DayClickHelper.hasRainForecast(rawRainSummary, precip)
            val nearTermLimit = today.plusDays(2)
            val rainSummary = if (!date.isBefore(today) && !date.isAfter(nearTermLimit)) {
                if (isToday && rawRainSummary != null && request.stateManager?.wasRainShownToday(request.appWidgetId, todayStr) == true) {
                    null
                } else {
                    rawRainSummary
                }
            } else null

            val finalLow = if (isToday) column.solidLow ?: column.forecastLow else column.solidLow
            val todayHasActualLow = isToday && actual?.computedLowTemp != null
            val iconRes = WeatherIconMapper.fromIconName(column.iconName)
            days.add(
                PreparedGraphDay(
                    renderDay = DailyForecastGraphRenderer.DayData(
                        date = date,
                        label = column.label,
                        solidLineHigh = column.barTopHigh,
                        solidLineLow = finalLow,
                        bottomStackLow = if (isToday) {
                            com.weatherwidget.shared.util.DailyDayValueResolver.effectiveLowForLabel(
                                isToday = true,
                                solidLow = column.solidLow,
                                forecastLow = column.forecastLow,
                                lowSettled = column.lowSettled,
                                actualLow = actual?.computedLowTemp,
                            ) ?: finalLow
                        } else {
                            finalLow
                        },
                        iconRes = iconRes,
                        isSunny = WeatherIconMapper.isSunny(iconRes),
                        isRainy = WeatherIconMapper.isPrecipitation(iconRes),
                        isMixed = WeatherIconMapper.isMixed(iconRes),
                        isToday = isToday,
                        isPast = isPastDate,
                        isClimateNormal = column.isClimateNormal && shown?.source != WeatherSource.GENERIC_GAP.id,
                        isSourceGapFallback = shown?.source == WeatherSource.GENERIC_GAP.id,
                        solidIsForecastFallback = column.solidIsForecastFallback,
                        dashedLineHigh = column.forecastHigh,
                        dashedLineLow = column.forecastLow,
                        rainData = DailyForecastGraphRenderer.RainLabelData(
                            dailyPrecipProbability = column.dayPrecipProbability,
                            nighttimePrecipProbability = column.nightPrecipProbability,
                            dailyRainLabelText = column.dailyRainLabelText,
                            dailyRainPeakLabelText = column.dailyRainPeakLabelText,
                            nightRainLabelText = column.nightRainLabelText,
                        ),
                        columnIndex = days.size,
                        isTodayForecastFallback = isToday && column.solidHigh == null && !todayHasActualLow &&
                            (column.barTopHigh != null || finalLow != null),
                        todayHasActualLow = todayHasActualLow,
                        snapshotHigh = column.snapshotHigh,
                        snapshotLow = column.snapshotLow,
                        snapshotIconRes = column.snapshotIconName?.let(WeatherIconMapper::fromIconName),
                        snapshotIsStale = column.snapshotIsStale,
                        priorBarRainPercent = column.priorBarRainPercent,
                        forecastIsFallback = column.forecastIsFallback,
                        actualsFromOtherSite = column.actualsFromOtherSite,
                        ghostLineHigh = column.ghostHigh,
                        cloudCoverRatioOverride = column.cloudCoverRatio,
                        daysFromToday = column.daysFromToday,
                        nowHour = column.nowHour,
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
