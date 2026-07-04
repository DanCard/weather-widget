package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
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
    val actual: DailyHistory?,
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
    /**
     * Resolved + gated daily icon NAME (e.g. "ic_weather_mostly_clear"). Derived from [iconCondition]
     * with the source-filtered noon cloud % threaded in and the daily partly-cloudy floor applied,
     * so the displayed icon (and its painter/colors/tap-routing) match Android.
     */
    val iconName: String,
    val isToday: Boolean,
    val isPast: Boolean,
    val cloudCoverRatio: Float?,
    /** Daytime rain label drawn on top of the bar (observed amount, forecast amount, or prob%). */
    val dailyRainLabelText: String?,
    /** Nighttime rain label tucked between columns (observed amount or prob%). */
    val nightRainLabelText: String?,
    val isClimateNormal: Boolean,
    /** Local hour-of-day (0–23) for the today column's actual-tracking cutoffs; null = legacy. */
    val nowHour: Int? = null,
)

data class DesktopDailyViewState(
    val dimensions: DesktopWidgetDimensions,
    val days: List<DesktopDailyDay>,
    val canNavigateLeft: Boolean,
    val canNavigateRight: Boolean,
    val skipYesterday: Boolean,
    val clampedDateOffset: Int,
    /** Scroll-zoom: extra history days actually shown after clamping to available data + the cap. */
    val clampedExtraHistory: Int,
    /** True when more history can be revealed by zooming out (data + cap permitting). */
    val canZoomOut: Boolean,
    /** True when zoomed out past the default (clampedExtraHistory > 0), so zoom-in can trim history. */
    val canZoomIn: Boolean,
)

object DesktopDailyForecastModel {
    private const val CELL_WIDTH_DP = 60
    private const val CELL_HEIGHT_DP = 90
    private const val ICON_WIDTH_THRESHOLD_DP = 130
    private const val MAX_DESKTOP_DAILY_COLUMNS = 9
    /**
     * Upper bound on how many extra history days scroll-zoom may prepend on the left. History is the
     * user's priority for zoom-out, so this is generous; the true limit is usually the available data.
     * Tunable — raise/lower if columns feel too cramped at full zoom-out.
     */
    private const val DAILY_MAX_EXTRA_HISTORY = 14

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

        // Scroll-wheel zoom (history-biased): prepend extra history days on the LEFT while today + the
        // future stay anchored on the right. The extra is clamped to available history and the cap; when
        // > 0 it shows history even if skipHistory dropped yesterday (zoom-out intent wins).
        val baseOffsets = NavigationUtils.getDayOffsets(dimensions.cols, skipHistory)
        val maxExtra = maxExtraHistory(centerDate, baseOffsets.first(), availableDates)
        val extraHistory = config.dailyExtraHistory.coerceIn(0, maxExtra)
        val historyOffsets = (1..extraHistory).map { baseOffsets.first() - it }.reversed()
        val allOffsets = historyOffsets + baseOffsets

        val days = allOffsets.map { dayOffset ->
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
            clampedExtraHistory = extraHistory,
            canZoomOut = extraHistory < maxExtra,
            canZoomIn = extraHistory > 0,
        )
    }

    /**
     * Max extra history days zoom-out may prepend: the number of days of available history to the left
     * of the current first column, capped at [DAILY_MAX_EXTRA_HISTORY]. Zero when no data is available.
     */
    private fun maxExtraHistory(
        centerDate: LocalDate,
        firstBaseOffset: Long,
        availableDates: Set<LocalDate>,
    ): Int {
        val minDate = availableDates.minOrNull() ?: return 0
        val firstBaseDate = centerDate.plusDays(firstBaseOffset)
        val availableHistory = ChronoUnit.DAYS.between(minDate, firstBaseDate).toInt().coerceAtLeast(0)
        return availableHistory.coerceAtMost(DAILY_MAX_EXTRA_HISTORY)
    }

    private fun buildDay(
        date: LocalDate,
        today: LocalDate,
        now: LocalDateTime,
        forecast: DailyForecast?,
        actual: DailyHistory?,
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
        // on the bar; the night label tucks between columns. The day/night precip % is chosen by the
        // shared resolveDailyLabelPrecip so desktop and Android pick identical values. Non-past days
        // use the hourly 8am–8pm / 8pm–8am window max, with the row's period fields as fallback. Past
        // days replay the value snapshotted into daily_history while the day was still live
        // (actual?.forecastDayPrecipChance / forecastNightPrecipChance), falling back to the raw
        // period fields for history written before that snapshot existed.
        // Past days have no live `forecast` row (the daily list holds only today + future), so the
        // forecast rain chance to keep visible in history comes from the day's snapshot instead.
        val resolvedPrecip = com.weatherwidget.shared.util.DailyRainLabels.resolveDailyLabelPrecip(
            isPast = isPast,
            displaySourceId = displaySourceId,
            daytimePrecipProbability = forecast?.daytimePrecipProbability ?: displaySnapshot?.daytimePrecipProbability,
            nighttimePrecipProbability = forecast?.nighttimePrecipProbability ?: displaySnapshot?.nighttimePrecipProbability,
            precipProbability = forecast?.precipProbability ?: displaySnapshot?.precipProbability,
            hourly = hourly,
            targetDate = date,
            storedDayPrecipChance = actual?.forecastDayPrecipChance,
            storedNightPrecipChance = actual?.forecastNightPrecipChance,
        )
        val forecastAmountMm = forecast?.precipAmountMm ?: snapshot?.precipAmountMm
        val dailyRainLabelText = com.weatherwidget.shared.util.DailyRainLabels.buildDailyRainLabel(
            date = date,
            today = today,
            isPastDate = isPast,
            precipAmountMm = forecastAmountMm,
            dayPrecipProbability = resolvedPrecip.dayPrecip,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = actual?.precipDayMm ?: actual?.precipAmountMm,
        )
        val nightRainLabelText = com.weatherwidget.shared.util.DailyRainLabels.buildNightRainLabel(
            date = date,
            today = today,
            isPastDate = isPast,
            nightPrecipProbability = resolvedPrecip.nightPrecip,
            observedNightPrecipMm = actual?.precipNightMm,
        )

        // Source-filtered noon cloud cover (shared with Android) drives both the bar split ratio and
        // the daily icon. The GENERIC_GAP exception applies only to climate-normal future days.
        val rowSourceId = if (forecast?.isClimateNormal == true) WeatherSource.GENERIC_GAP.id else null
        val measuredNoonCloudPercent = com.weatherwidget.shared.util.DailyNoonCloudCover
            .resolveMeasuredNoonCloudCoverPercent(
                hourly = hourly,
                date = date,
                displaySourceId = displaySourceId,
                rowSourceId = rowSourceId,
            )
        val noonCloudPercentForBar = measuredNoonCloudPercent ?: 0
        val rawCondition = forecast?.condition ?: actual?.condition ?: displaySnapshot?.condition
        // Daily icon noon is daytime (isNight = false). Thread the measured noon cloud % in, then
        // apply the daily partly-cloudy floor: worded "partly cloudy" needs ≥25% noon cloud to stand.
        val baseIconName = com.weatherwidget.shared.util.WeatherConditionResolver.resolveIconName(
            condition = rawCondition,
            isNight = false,
            cloudCover = measuredNoonCloudPercent,
            precipProbability = forecast?.precipProbability,
        )
        val gatedIconName = com.weatherwidget.shared.util.WeatherConditionResolver.applyDailyPartlyCloudyFloor(
            baseIconName, measuredNoonCloudPercent, isNight = false,
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
            iconCondition = rawCondition,
            iconName = gatedIconName,
            isToday = isToday,
            isPast = isPast,
            cloudCoverRatio = noonCloudPercentForBar / 100f,
            dailyRainLabelText = dailyRainLabelText,
            nightRainLabelText = nightRainLabelText,
            isClimateNormal = forecast?.isClimateNormal == true,
            nowHour = if (isToday) now.hour else null,
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

}
