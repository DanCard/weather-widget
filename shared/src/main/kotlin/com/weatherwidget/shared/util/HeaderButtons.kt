package com.weatherwidget.shared.util

import com.weatherwidget.widget.ViewMode
import java.time.LocalDate
import java.time.LocalDateTime

/** A centred header button. Declaration order is the display order, left to right ([HeaderButtons.ORDER]). */
enum class HeaderButton { HOME, OBSERVATIONS, GRAPH_SELECTOR, MONTHS, HISTORY }

/**
 * Which header buttons show, and in what order — one rule for the daily and hourly headers on both
 * platforms (user, 2026-10-22; `plans/261022-home-button-first-shared-header-buttons.md`).
 *
 * The row ends on [HeaderFit]'s fixed line and grows leftwards, so only its leftmost button can come
 * and go without moving the rest. Home goes there: it is the one a source switch adds or removes,
 * and the first dropped on a narrow header. Observations is second, so with four buttons home and
 * observations sit at the same x on both views; history is last on both, so switching daily ↔
 * hourly changes only the third icon (months ↔ graph selector).
 *
 * Daily: home (when [PreferredSourceHome] has an action and it fits) · observations (today or
 * yesterday on screen) · months (when wanted and it fits) · history.
 * Hourly: home (back to the daily view, always) · observations (today on screen) · graph selector ·
 * history.
 *
 * Each platform measures widths and draws; [want] feeds [HeaderFit.Measured], [shown] says what to draw.
 */
object HeaderButtons {
    val ORDER: List<HeaderButton> = HeaderButton.entries

    /** What the header would show with room for everything. */
    data class Want(
        val hourly: Boolean,
        val observations: Boolean,
        /** Daily only: home has an action. Hourly home is always there. */
        val home: Boolean = false,
        /** Daily only. */
        val months: Boolean = false,
    ) {
        /** [HeaderFit.Measured.baseButtons]: the buttons never dropped one by one. */
        val baseButtons: Int
            get() = if (hourly) 3 + (if (observations) 1 else 0) else (if (observations) 1 else 0) + 1
        val wantHome: Boolean get() = !hourly && home
        val wantMonths: Boolean get() = !hourly && months
    }

    fun hourly(observationsInView: Boolean) = Want(hourly = true, observations = observationsInView)

    fun daily(observationsInView: Boolean, home: Boolean, months: Boolean) =
        Want(hourly = false, observations = observationsInView, home = home, months = months)

    /** The buttons [plan] shows, left to right; empty when it shows none. Its size is [HeaderFit.Plan.buttons]. */
    fun shown(want: Want, plan: HeaderFit.Plan): List<HeaderButton> {
        if (plan.placement == HeaderFit.ButtonPlacement.HIDDEN || plan.buttons <= 0) return emptyList()
        return ORDER.filter { button ->
            when (button) {
                HeaderButton.HOME -> want.hourly || plan.homeShown
                HeaderButton.OBSERVATIONS -> want.observations
                HeaderButton.GRAPH_SELECTOR -> want.hourly
                HeaderButton.MONTHS -> !want.hourly && plan.monthsShown
                HeaderButton.HISTORY -> true
            }
        }
    }

    /**
     * Hourly: whether the graph window `[windowStart, windowEnd)` shows any of [today], i.e. whether
     * current observations apply. The button opens current readings on both platforms, so a history
     * or future window hides it. Replaces Android's two "is today" checks and desktop's always-on.
     */
    fun hourlyObservationsInView(windowStart: LocalDateTime, windowEnd: LocalDateTime, today: LocalDate): Boolean =
        windowStart.isBefore(today.plusDays(1).atStartOfDay()) && windowEnd.isAfter(today.atStartOfDay())

    /** [hourlyObservationsInView] for the graph window around [centre]: `centre − backHours` to `centre + forwardHours`. */
    fun hourlyObservationsInView(centre: LocalDateTime, backHours: Long, forwardHours: Long, today: LocalDate): Boolean =
        hourlyObservationsInView(centre.minusHours(backHours), centre.plusHours(forwardHours), today)
}

/** The hourly graph selector: it cycles temperature → cloud → precipitation and shows the view it goes to. */
object GraphSelector {
    fun next(current: ViewMode): ViewMode = when (current) {
        ViewMode.CLOUD_COVER -> ViewMode.PRECIPITATION
        ViewMode.PRECIPITATION -> ViewMode.TEMPERATURE
        ViewMode.TEMPERATURE, ViewMode.DAILY -> ViewMode.CLOUD_COVER
    }

    /** The emoji naming [next]'s view. */
    fun emoji(current: ViewMode): String = when (next(current)) {
        ViewMode.CLOUD_COVER -> "☁️" // ☁️
        ViewMode.PRECIPITATION -> "🌧️" // 🌧️
        ViewMode.TEMPERATURE, ViewMode.DAILY -> "🌡️" // 🌡️
    }
}
