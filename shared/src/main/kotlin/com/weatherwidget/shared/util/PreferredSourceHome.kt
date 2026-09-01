package com.weatherwidget.shared.util

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
 * See `plans/260901-daily-home-button-when-source-not-preferred.md`.
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
}
