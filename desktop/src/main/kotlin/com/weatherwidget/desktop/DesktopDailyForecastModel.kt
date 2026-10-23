package com.weatherwidget.desktop

import com.weatherwidget.shared.util.PreferredSourceHome
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.TodayColumnOverlayContent
import com.weatherwidget.shared.actuals.TodayColumnOverlayContentResolver
import com.weatherwidget.shared.graph.DailyLayoutConstants
import com.weatherwidget.shared.graph.LargeTodayOverlayPolicy
import com.weatherwidget.shared.util.Log
import com.weatherwidget.util.NavigationUtils
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "DesktopDailyModel"

data class DesktopWidgetDimensions(
    val cols: Int,
    val rows: Int,
    val widthDp: Int,
    val heightDp: Int,
    val isIconWidth: Boolean,
    val useGraph: Boolean,
)

/** A daily column — built by `:shared` [com.weatherwidget.shared.daily.DailyColumnBuilder]. */
typealias DesktopDailyDay = com.weatherwidget.shared.daily.DailyColumn

data class DesktopDailyViewState(
    val dimensions: DesktopWidgetDimensions,
    val days: List<DesktopDailyDay>,
    val canNavigateLeft: Boolean,
    val canNavigateRight: Boolean,
    val skipYesterday: Boolean,
    val clampedDateOffset: Int,
    /**
     * Where the daily home button's recentre goes: yesterday, then today (`PreferredSourceHome`),
     * with the current zoom-out kept — its columns shift to the forecast side.
     */
    val homeDateOffset: Int = 0,
    /** Scroll-zoom: extra history days actually shown after clamping to available data + the cap. */
    val clampedExtraHistory: Int,
    /** True when more history can be revealed by zooming out (data + cap permitting). */
    val canZoomOut: Boolean,
    /** True when zoomed out past the default (clampedExtraHistory > 0), so zoom-in can trim history. */
    val canZoomIn: Boolean,
    /** Shared detailed-Today mode; desktop Compose only measures and paints [todayOverlay]. */
    val largeTodayOverlayEnabled: Boolean,
    val todayOverlay: TodayColumnOverlayContent?,
)

object DesktopDailyForecastModel {
    const val CELL_HEIGHT_DP = DailyLayoutConstants.CELL_HEIGHT_DP
    const val ICON_WIDTH_THRESHOLD_DP = DailyLayoutConstants.ICON_WIDTH_THRESHOLD_DP
    private const val MAX_DESKTOP_DAILY_COLUMNS = 9
    /**
     * Upper bound on how many extra history days scroll-zoom may prepend on the left. History is the
     * user's priority for zoom-out, so this is generous; the true limit is usually the available data.
     * Tunable — raise/lower if columns feel too cramped at full zoom-out.
     */
    private const val DAILY_MAX_EXTRA_HISTORY = 14

    /** Day columns for a popup [widthDp] wide; the cell width is shared with Android. */
    fun columnsForWidthDp(widthDp: Int): Int =
        ((widthDp + 15).toFloat() / DailyLayoutConstants.cellWidthDp(widthDp)).roundToInt()
            .coerceAtLeast(1)
            .coerceAtMost(MAX_DESKTOP_DAILY_COLUMNS)

    fun dimensions(widthDp: Int, heightDp: Int): DesktopWidgetDimensions {
        val cols = columnsForWidthDp(widthDp)
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

    /**
     * Columns the daily view draws for [config]'s offset and zoom: one fewer while the large Today
     * overlay is on, which depends on whether today is in view. Cheap and pure, so the months view
     * can ask it for the window it is about to jump to.
     */
    fun displayColumns(config: DesktopConfig, dimensions: DesktopWidgetDimensions, today: LocalDate): Int =
        overlayDecision(config, dimensions, today).displayColumns

    private fun overlayDecision(config: DesktopConfig, dimensions: DesktopWidgetDimensions, today: LocalDate): LargeTodayOverlayPolicy.Decision {
        val skipYesterday = NavigationUtils.shouldSkipYesterday(dimensions.cols)
        val overlayCandidateColumns = (dimensions.cols - 1).coerceAtLeast(1)
        // The eligibility window must be the window the user actually SEES. `getVisibleDateRange`
        // knows nothing about the zoom-out extra-history columns, which `historyOffsets` in [build]
        // prepends — so the raw range starts `dailyExtraHistory` days later than the rendered one.
        // With a forward dateOffset the two disagreed enough to report today as off-screen while it
        // sat in column 2, and the whole Today overlay switched itself off
        // (dateOffset=3 + dailyExtraHistory=3 -> candidate range today+2..today+12, today absent).
        val overlayCandidateRawRange =
            NavigationUtils.getVisibleDateRange(
                today,
                config.dateOffset,
                overlayCandidateColumns,
                skipYesterday,
            )
        val overlayCandidateRange =
            overlayCandidateRawRange.first.minusDays(
                config.dailyExtraHistory.coerceAtLeast(0).toLong(),
            ) to overlayCandidateRawRange.second
        return LargeTodayOverlayPolicy.resolve(
            profile = LargeTodayOverlayPolicy.Profile.DESKTOP,
            availableColumns = dimensions.cols,
            rows = dimensions.rows,
            useGraph = dimensions.useGraph,
            todayVisible = today in overlayCandidateRange.first..overlayCandidateRange.second,
        )
    }

    /**
     * @param currentTemp the current temperature the header shows — the daemon-published
     *   `current_status` value, else the snapshot's own. Required, never read from [forecast]: the
     *   popup's snapshot is resolved once per load while the header re-reads the published value each
     *   tick, so the two disagreed (header 73.4°, today's bar 71.8°;
     *   plans/261013-desktop-today-column-uses-published-current-temp.md).
     */
    fun build(
        config: DesktopConfig,
        forecast: ForecastSnapshot,
        dimensions: DesktopWidgetDimensions,
        now: LocalDateTime = LocalDateTime.now(),
        currentTemp: Float?,
    ): DesktopDailyViewState {
        val today = now.toLocalDate()
        val skipYesterday = NavigationUtils.shouldSkipYesterday(dimensions.cols)
        val overlayDecision = overlayDecision(config, dimensions, today)
        val displayColumns = overlayDecision.displayColumns
        val availableDates = buildAvailableDates(forecast)
        val offset = clampOffset(config.dateOffset, today, displayColumns, skipYesterday, availableDates)
        val skipHistory = NavigationUtils.shouldSkipHistory(skipYesterday, offset)
        val centerDate = NavigationUtils.getDisplayCenterDate(today, offset, skipYesterday)
        val daysByDate = forecast.raw.daily.associateBy { LocalDate.parse(it.date) }
        val actualsByDate = forecast.raw.dailyActuals.mapKeys { LocalDate.parse(it.key) }
        val snapshotsByDate = forecast.raw.dailySnapshots.mapKeys { LocalDate.parse(it.key) }

        // Scroll-wheel zoom (history-biased): prepend extra history days on the LEFT while today + the
        // future stay anchored on the right. The extra is clamped to available history and the cap; when
        // > 0 it shows history even if skipHistory dropped yesterday (zoom-out intent wins).
        val baseOffsets = NavigationUtils.getDayOffsets(displayColumns, skipHistory)
        val maxExtra = maxExtraHistory(centerDate, baseOffsets.first(), availableDates)
        val extraHistory = config.dailyExtraHistory.coerceIn(0, maxExtra)
        val historyOffsets = (1..extraHistory).map { baseOffsets.first() - it }.reversed()
        val allOffsets = historyOffsets + baseOffsets

        val days = allOffsets.map { dayOffset ->
            val date = centerDate.plusDays(dayOffset)
            com.weatherwidget.shared.daily.DailyColumnBuilder.build(
                date = date,
                label = if (date == today) "Today" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                today = today,
                now = now,
                forecast = daysByDate[date],
                actual = actualsByDate[date],
                snapshots = snapshotsByDate[date].orEmpty(),
                hourly = forecast.raw.hourly,
                currentTemp = currentTemp,
                displaySourceId = config.displaySource,
                centerLat = config.lat,
                centerLon = config.lon,
            )
        }
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val overlayFlags =
            Pair(config.settings.todayOverlayDelta, config.settings.todayOverlayDominantTemp)
        val todayOverlay =
            if (overlayDecision.enabled && (overlayFlags.first || overlayFlags.second)) {
                val obsCount = forecast.raw.rawObservations.size
                val result = TodayColumnOverlayContentResolver.resolveLatest(
                    observations = forecast.raw.rawObservations,
                    hourlyForecasts = forecast.raw.hourly,
                    displaySourceId = WeatherSource.fromDisplaySource(config.displaySource).id,
                    userLat = config.lat,
                    userLon = config.lon,
                    nowMs = nowMs,
                    personalStationWeight = config.personalStationWeight(),
                    useCelsius = config.settings.useCelsius,
                    // Overlay delta row = FORECAST delta (swapped with the header, which now shows
                    // the yesterday delta).
                    forecastDelta = forecast.resolved.appliedDelta,
                    showForecastDelta = overlayFlags.first,
                    showDominantStationTemp = overlayFlags.second,
                )
                Log.d(
                    TAG,
                    "todayOverlay resolve obsCount=$obsCount enabled=${overlayDecision.enabled} " +
                        "flags=delta:${overlayFlags.first},temp:${overlayFlags.second} " +
                        "deltaText=${result?.deltaValueText} dominantTemp=${result?.dominantTempText} " +
                        "observedAt=${result?.observedAt}",
                )
                result
            } else {
                Log.d(
                    TAG,
                    "todayOverlay disabled: enabled=${overlayDecision.enabled} " +
                        "extraHistory=${config.dailyExtraHistory} flags=$overlayFlags",
                )
                null
            }

        return DesktopDailyViewState(
            dimensions = dimensions,
            days = days,
            canNavigateLeft = canNavigate(today, offset - 1, displayColumns, skipYesterday, availableDates, left = true),
            canNavigateRight = canNavigate(today, offset + 1, displayColumns, skipYesterday, availableDates, left = false),
            skipYesterday = skipYesterday,
            clampedDateOffset = offset,
            homeDateOffset = PreferredSourceHome.homeDateOffset(displayColumns, skipYesterday, extraHistory),
            clampedExtraHistory = extraHistory,
            canZoomOut = extraHistory < maxExtra,
            canZoomIn = extraHistory > 0,
            largeTodayOverlayEnabled = overlayDecision.enabled,
            todayOverlay = todayOverlay,
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

    private fun buildAvailableDates(forecast: ForecastSnapshot): Set<LocalDate> =
        forecast.raw.daily.map { LocalDate.parse(it.date) }.toSet() +
            forecast.raw.dailyActuals.keys.map { LocalDate.parse(it) } +
            forecast.raw.dailySnapshots.keys.map { LocalDate.parse(it) }

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
