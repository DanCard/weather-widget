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
    val snapshotHigh: Float?,
    val snapshotLow: Float?,
    val iconCondition: String?,
    val isToday: Boolean,
    val isPast: Boolean,
    val cloudCoverRatio: Float?,
    val precipProbability: Int?,
    val precipAmountMm: Float?,
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
    private const val CELL_WIDTH_DP = 70
    private const val CELL_HEIGHT_DP = 90
    private const val ICON_WIDTH_THRESHOLD_DP = 130
    private const val MAX_DESKTOP_DAILY_COLUMNS = 8

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
    ): DesktopDailyDay {
        val isToday = date == today
        val isPast = date.isBefore(today)
        val snapshot = snapshots
            .filter { it.highTemp != null && it.lowTemp != null && it.highTemp != it.lowTemp }
            .maxByOrNull { it.fetchedAt }
            ?: snapshots
                .filter { it.highTemp != null || it.lowTemp != null }
                .maxByOrNull { it.fetchedAt }

        val solidHigh: Float?
        val solidLow: Float?
        val forecastHigh: Float?
        val forecastLow: Float?

        when {
            isPast -> {
                solidHigh = actual?.highTemp
                solidLow = actual?.lowTemp
                forecastHigh = snapshot?.highTemp ?: forecast?.highTemp
                forecastLow = snapshot?.lowTemp ?: forecast?.lowTemp
            }
            isToday -> {
                val current = currentTemp
                solidHigh = listOfNotNull(actual?.highTemp, current).maxOrNull()
                solidLow = actual?.lowTemp ?: current
                forecastHigh = forecast?.highTemp
                forecastLow = forecast?.lowTemp
            }
            else -> {
                solidHigh = forecast?.highTemp
                solidLow = forecast?.lowTemp
                forecastHigh = null
                forecastLow = null
            }
        }

        return DesktopDailyDay(
            date = date,
            label = if (isToday) "Today" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            forecast = forecast,
            actual = actual,
            snapshot = snapshot,
            solidHigh = solidHigh,
            solidLow = solidLow,
            forecastHigh = forecastHigh,
            forecastLow = forecastLow,
            snapshotHigh = snapshot?.highTemp,
            snapshotLow = snapshot?.lowTemp,
            iconCondition = forecast?.condition ?: actual?.condition ?: snapshot?.condition,
            isToday = isToday,
            isPast = isPast,
            cloudCoverRatio = resolveNoonCloudCoverRatio(date, hourly),
            precipProbability = if (isToday) nextPrecipProbability(now, hourly) else forecast?.precipProbability,
            precipAmountMm = forecast?.precipAmountMm ?: snapshot?.precipAmountMm,
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

    private fun nextPrecipProbability(now: LocalDateTime, hourly: List<HourlyForecast>): Int? {
        val zone = ZoneId.systemDefault()
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val endMs = now.plusHours(8).atZone(zone).toInstant().toEpochMilli()
        return hourly.asSequence()
            .filter { it.dateTime in nowMs..endMs }
            .mapNotNull { it.precipProbability }
            .maxOrNull()
    }


}
