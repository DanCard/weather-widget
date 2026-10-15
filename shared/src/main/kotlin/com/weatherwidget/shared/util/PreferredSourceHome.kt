package com.weatherwidget.shared.util

import com.weatherwidget.util.NavigationUtils

/**
 * When the daily view is showing a source the user did not put first, and how to get back.
 *
 * The daily view *is* home in the **view** sense — that is why its header leaves the home zone
 * hidden and `positionDailyIcons` never touches it. But a widget has a second axis it can be off
 * home on: the **displayed source**. Tapping the API indicator cycles it
 * (`WeatherSourcePreferences.toggleDisplaySource` / the desktop header's source label), and
 * without this button the only way back is to keep cycling until the list wraps.
 *
 * Works in `List<String>` of `WeatherSource.id`, like [WeatherSourceOrdering], because that is how
 * both platforms persist the visible order (`visible_sources_order` CSV on Android,
 * `DesktopConfig.visibleSources` on desktop). "Preferred" is the FIRST visible source, the same
 * definition `WeatherSourcePreferences.primarySource()` uses — see the CLAUDE.md note that
 * "primary" in this app means the displayed-by-default source, not a fetch-priority rank.
 *
 * On the preferred source the button has a second job: when the user has panned today off screen,
 * it brings today back with one day of history to its left ([HomeAction.RECENTER_TODAY]).
 *
 * See `plans/260901-daily-home-button-when-source-not-preferred.md` and
 * `plans/261015-daily-home-button-recentres-today.md`.
 */
object PreferredSourceHome {

    /** The source the daily view returns to, or null when no source order is stored at all. */
    fun preferredSourceId(visibleSourceIds: List<String>): String? = visibleSourceIds.firstOrNull()

    /**
     * Whether the daily header should offer the home button, ignoring the header's own fit
     * question — the caller decides whether there is room, this decides whether there is a reason.
     *
     * False when the current source is already preferred, and false when the order is empty or
     * does not contain the current source: with nothing to go back to, a button that reports
     * "you are somewhere else" would be lying about a destination it cannot reach.
     */
    fun shouldShowHomeButton(
        currentSourceId: String?,
        visibleSourceIds: List<String>,
    ): Boolean {
        val preferred = preferredSourceId(visibleSourceIds) ?: return false
        if (currentSourceId.isNullOrEmpty()) return false
        return currentSourceId != preferred && currentSourceId in visibleSourceIds
    }

    /** What the daily header's home button does when tapped. */
    enum class HomeAction {
        /** Switch back to the preferred source; the date window is left where it is. */
        RESET_SOURCE,

        /** Move the date window to [homeDateOffset]: yesterday, today, then the forecast. */
        RECENTER_TODAY,
    }

    /**
     * The daily home button's action, or null when it should not be offered.
     *
     * A non-preferred source always means [HomeAction.RESET_SOURCE] (unchanged, whatever the dates).
     * On the preferred source the button appears only while today is off screen — the request was
     * conditional on "on the main api", so a non-preferred tap never moves the dates.
     */
    fun homeAction(
        currentSourceId: String?,
        visibleSourceIds: List<String>,
        todayInView: Boolean,
    ): HomeAction? {
        if (shouldShowHomeButton(currentSourceId, visibleSourceIds)) return HomeAction.RESET_SOURCE
        val preferred = preferredSourceId(visibleSourceIds) ?: return null
        if (currentSourceId != preferred || todayInView) return null
        return HomeAction.RECENTER_TODAY
    }

    /**
     * The date offset whose window starts at yesterday with today second.
     *
     * Narrow widgets ([NavigationUtils.shouldSkipYesterday]) are today-first at offset 0, so
     * yesterday needs offset −1 (its centre date is today again). Wide widgets already start at
     * yesterday at 0. One and two columns never show yesterday ([NavigationUtils.getDayOffsets]),
     * so 0 — today alone — is the closest they get.
     *
     * [skipYesterday] defaults to the width rule; desktop passes its own because it decides the
     * skip from the full column count but draws one fewer when the large Today overlay is on.
     *
     * [extraHistoryDays] is desktop's scroll zoom-out, which prepends that many history columns
     * left of the window. The zoom is kept (user, 2026-10-15): the window moves forward by the
     * same count, so yesterday is still the only history column and the extra columns become
     * forecast days. (Wide: leftmost = today + offset − 1 − E; narrow: today + offset − E, offset 0
     * included because it is today-first — both land on yesterday at base + E.)
     */
    fun homeDateOffset(
        numColumns: Int,
        skipYesterday: Boolean = NavigationUtils.shouldSkipYesterday(numColumns),
        extraHistoryDays: Int = 0,
    ): Int = (if (numColumns >= 3 && skipYesterday) -1 else 0) + extraHistoryDays.coerceAtLeast(0)
}
