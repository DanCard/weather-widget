package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyExtreme
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.util.NavigationUtils
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

data class DesktopWidgetDimensions(
    val cols: Int,
    val rows: Int,
    val widthDp: Int,
    val heightDp: Int,
    val isIconWidth: Boolean,
    val useGraph: Boolean,
)

data class DesktopDailyDay(
    val date: LocalDate,
    val label: String,
    val forecast: DailyForecast?,
    val actual: DailyExtreme?,
    val snapshot: DailyForecastSnapshot?,
    val solidHigh: Float?,
    val solidLow: Float?,
    val forecastHigh: Float?,
    val forecastLow: Float?,
    /** Today-only faint high-water mark (peak observed so far); ghost line reaches up to this. */
    val ghostHigh: Float?,
    val snapshotHigh: Float?,
    val snapshotLow: Float?,
    val iconCondition: String?,
    val isToday: Boolean,
    val isPast: Boolean,
    val cloudCoverRatio: Float?,
    /** Daytime rain label drawn on top of the bar (observed amount, forecast amount, or prob%). */
    val dailyRainLabelText: String?,
    /** Nighttime rain label tucked between columns (observed amount or prob%). */
    val nightRainLabelText: String?,
    val isClimateNormal: Boolean,
)

data class DesktopDailyViewState(
    val dimensions: DesktopWidgetDimensions,
    val days: List<DesktopDailyDay>,
    val canNavigateLeft: Boolean,
    val canNavigateRight: Boolean,
    val skipYesterday: Boolean,
    val clampedDateOffset: Int,
)

object DesktopDailyForecastModel {
    private const val CELL_WIDTH_DP = 60
    private const val CELL_HEIGHT_DP = 90
    private const val ICON_WIDTH_THRESHOLD_DP = 130
    private const val MAX_DESKTOP_DAILY_COLUMNS = 9

    fun dimensions(widthDp: Int, heightDp: Int): DesktopWidgetDimensions {
        val cols = ((widthDp + 15).toFloat() / CELL_WIDTH_DP).roundToInt()
            .coerceAtLeast(1)
            .coerceAtMost(MAX_DESKTOP_DAILY_COLUMNS)
        val rows = ((heightDp + 25).toFloat() / CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)
        val rawRows = (heightDp + 25).toFloat() / CELL_HEIGHT_DP
        return DesktopWidgetDimensions(
            cols = cols,
            rows = rows,
            widthDp = widthDp,
            heightDp = heightDp,
            isIconWidth = widthDp <= ICON_WIDTH_THRESHOLD_DP,
            useGraph = rawRows >= 1.4f,
        )
    }

    fun build(
        config: DesktopConfig,
        forecast: ForecastResult,
        dimensions: DesktopWidgetDimensions,
        now: LocalDateTime = LocalDateTime.now(),
    ): DesktopDailyViewState {
        val today = now.toLocalDate()
        val skipYesterday = NavigationUtils.shouldSkipYesterday(now.toLocalTime(), dimensions.cols)
        val availableDates = buildAvailableDates(forecast)
        val offset = clampOffset(config.dateOffset, today, dimensions.cols, skipYesterday, availableDates)
        val skipHistory = NavigationUtils.shouldSkipHistory(skipYesterday, offset)
        val centerDate = NavigationUtils.getDisplayCenterDate(today, offset, skipYesterday)
        val daysByDate = forecast.daily.associateBy { LocalDate.parse(it.date) }
        val actualsByDate = forecast.dailyActuals.mapKeys { LocalDate.parse(it.key) }
        val snapshotsByDate = forecast.dailySnapshots.mapKeys { LocalDate.parse(it.key) }

        val days = NavigationUtils.getDayOffsets(dimensions.cols, skipHistory).map { dayOffset ->
            val date = centerDate.plusDays(dayOffset)
            buildDay(
                date = date,
                today = today,
                now = now,
                forecast = daysByDate[date],
                actual = actualsByDate[date],
                snapshots = snapshotsByDate[date].orEmpty(),
                hourly = forecast.hourly,
                currentTemp = forecast.currentTemp,
                displaySourceId = config.weatherSource,
            )
        }

        return DesktopDailyViewState(
            dimensions = dimensions,
            days = days,
            canNavigateLeft = canNavigate(today, offset - 1, dimensions.cols, skipYesterday, availableDates, left = true),
            canNavigateRight = canNavigate(today, offset + 1, dimensions.cols, skipYesterday, availableDates, left = false),
            skipYesterday = skipYesterday,
            clampedDateOffset = offset,
        )
    }

    private fun buildDay(
        date: LocalDate,
        today: LocalDate,
        now: LocalDateTime,
        forecast: DailyForecast?,
        actual: DailyExtreme?,
        snapshots: List<DailyForecastSnapshot>,
        hourly: List<HourlyForecast>,
        currentTemp: Float?,
        displaySourceId: String,
    ): DesktopDailyDay {
        val isToday = date == today
        val isPast = date.isBefore(today)
        // Past-day overlay wants the most-recent snapshot (matches Android's past-day logic).
        val snapshot = snapshots
            .filter { it.highTemp != null && it.lowTemp != null && it.highTemp != it.lowTemp }
            .maxByOrNull { it.fetchedAt }
            ?: snapshots
                .filter { it.highTemp != null || it.lowTemp != null }
                .maxByOrNull { it.fetchedAt }

        // Today's left bar wants the forecast "as of ~24h ago" — shared with Android.
        val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todaySnapshot = com.weatherwidget.shared.util.DailySnapshotSelector.selectPriorDaySnapshot(
            snapshots.filter { it.highTemp != null && it.lowTemp != null },
            nowMillis, { it.fetchedAt },
        )
        val displaySnapshot = if (isToday) todaySnapshot else snapshot

        val solidHigh: Float?
        val solidLow: Float?
        val forecastHigh: Float?
        val forecastLow: Float?
        // Ghost (high-water mark) is a today-only affordance, matching Android.
        var ghostHigh: Float? = null

        when {
            isPast -> {
                solidHigh = actual?.highTemp
                solidLow = actual?.lowTemp
                forecastHigh = snapshot?.highTemp ?: forecast?.highTemp
                forecastLow = snapshot?.lowTemp ?: forecast?.lowTemp
            }
            isToday -> {
                val todayValues = com.weatherwidget.shared.util.DailyDayValueResolver.resolveTodayLineValues(
                    actualHigh = actual?.highTemp,
                    actualLow = actual?.lowTemp,
                    forecastHigh = forecast?.highTemp,
                    forecastLow = forecast?.lowTemp,
                    currentTemp = currentTemp,
                )
                solidHigh = todayValues.solidHigh
                solidLow = todayValues.solidLow
                forecastHigh = todayValues.forecastHigh
                forecastLow = todayValues.forecastLow
                ghostHigh = todayValues.ghostHigh
            }
            else -> {
                solidHigh = forecast?.highTemp
                solidLow = forecast?.lowTemp
                forecastHigh = null
                forecastLow = null
            }
        }

        // Rain labels (text-building logic shared with the Android widget). The daytime label sits
        // on the bar; the night label tucks between columns. Past days prefer observed actuals;
        // forecast days fall back to the day/night precip-probability windows from hourly data.
        val dayNight = if (!isPast) {
            com.weatherwidget.shared.util.DailyRainLabels.calculateDayNightPrecipProbabilities(
                hourly = hourly,
                targetDate = date,
                displaySourceId = displaySourceId,
            )
        } else {
            null
        }
        val forecastAmountMm = forecast?.precipAmountMm ?: snapshot?.precipAmountMm
        val dailyRainLabelText = com.weatherwidget.shared.util.DailyRainLabels.buildDailyRainLabel(
            date = date,
            today = today,
            isPastDate = isPast,
            precipAmountMm = forecastAmountMm,
            dayPrecipProbability = dayNight?.dayMax ?: forecast?.precipProbability,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = actual?.precipDayMm ?: actual?.precipAmountMm,
        )
        val nightRainLabelText = com.weatherwidget.shared.util.DailyRainLabels.buildNightRainLabel(
            date = date,
            today = today,
            isPastDate = isPast,
            nightPrecipProbability = dayNight?.nightMax,
            observedNightPrecipMm = actual?.precipNightMm,
        )

        return DesktopDailyDay(
            date = date,
            label = if (isToday) "Today" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            forecast = forecast,
            actual = actual,
            snapshot = displaySnapshot,
            solidHigh = solidHigh,
            solidLow = solidLow,
            forecastHigh = forecastHigh,
            forecastLow = forecastLow,
            ghostHigh = ghostHigh,
            snapshotHigh = displaySnapshot?.highTemp,
            snapshotLow = displaySnapshot?.lowTemp,
            iconCondition = forecast?.condition ?: actual?.condition ?: displaySnapshot?.condition,
            isToday = isToday,
            isPast = isPast,
            cloudCoverRatio = resolveNoonCloudCoverRatio(date, hourly),
            dailyRainLabelText = dailyRainLabelText,
            nightRainLabelText = nightRainLabelText,
            isClimateNormal = forecast?.isClimateNormal == true,
        )
    }

    private fun buildAvailableDates(forecast: ForecastResult): Set<LocalDate> =
        forecast.daily.map { LocalDate.parse(it.date) }.toSet() +
            forecast.dailyActuals.keys.map { LocalDate.parse(it) } +
            forecast.dailySnapshots.keys.map { LocalDate.parse(it) }

    private fun clampOffset(
        dateOffset: Int,
        today: LocalDate,
        numColumns: Int,
        skipYesterday: Boolean,
        availableDates: Set<LocalDate>,
    ): Int {
        if (availableDates.isEmpty()) return 0
        if (rangeHasAnyDate(today, dateOffset, numColumns, skipYesterday, availableDates)) return dateOffset
        return 0
    }

    private fun canNavigate(
        today: LocalDate,
        candidateOffset: Int,
        numColumns: Int,
        skipYesterday: Boolean,
        availableDates: Set<LocalDate>,
        left: Boolean,
    ): Boolean {
        if (availableDates.isEmpty()) return false
        val (leftmost, rightmost) = NavigationUtils.getVisibleDateRange(today, candidateOffset, numColumns, skipYesterday)
        return if (left) {
            availableDates.minOrNull()?.let { !it.isAfter(leftmost) } == true
        } else {
            availableDates.maxOrNull()?.let { !it.isBefore(rightmost) } == true
        }
    }

    private fun rangeHasAnyDate(
        today: LocalDate,
        dateOffset: Int,
        numColumns: Int,
        skipYesterday: Boolean,
        availableDates: Set<LocalDate>,
    ): Boolean {
        val (leftmost, rightmost) = NavigationUtils.getVisibleDateRange(today, dateOffset, numColumns, skipYesterday)
        return availableDates.any { !it.isBefore(leftmost) && !it.isAfter(rightmost) }
    }

    private fun resolveNoonCloudCoverRatio(date: LocalDate, hourly: List<HourlyForecast>): Float? {
        val noon = date.atTime(12, 0)
        return hourly.asSequence()
            .mapNotNull { forecast ->
                val cloud = forecast.cloudCover ?: return@mapNotNull null
                val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(forecast.dateTime), ZoneId.systemDefault())
                if (local.toLocalDate() != date) return@mapNotNull null
                Triple(ChronoUnit.MINUTES.between(noon, local).let { kotlin.math.abs(it) }, local, cloud)
            }
            .minWithOrNull(compareBy<Triple<Long, LocalDateTime, Int>> { it.first }.thenBy { it.second })
            ?.third
            ?.coerceIn(0, 100)
            ?.div(100f)
    }

}
