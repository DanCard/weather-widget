package com.weatherwidget.shared.util

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyForecast
import java.time.LocalDate

/**
 * Decides whether a location change should replace what is on screen with a
 * "Getting weather for {place}…" interstitial, or leave the display to the normal cache/fetch
 * repaints.
 *
 * The axis is not "setup vs GPS" but **user-initiated vs background**. A setup-screen save is the
 * one location write where the user is guaranteed to be looking at the widget in the next few
 * seconds, asking "did my choice register, and did it pick the right place?" — so a message naming
 * the place is worth the flash. A follow-device move is background: nobody is watching, the fetch
 * is battery-gated (up to 1440 min off charger), and a placeholder that could sit for hours would
 * be strictly worse than the sparse-but-correct graph the immediate switch already gives. The paint
 * policy follows the fetch policy: only the path that bypasses the battery gate gets feedback.
 *
 * Shared by Android (`LocationUpdater`) and desktop (`DesktopUiApplication`).
 */
object LocationChangePaintPolicy {

    /**
     * @param userInitiated true for the setup screen / location picker; false for device following.
     * @param previous the site on screen before the change, or null when there was none.
     * @param newLat/newLon the site just written.
     * @param hasCachedRowsAtNewSite true when the new site already has a forecast row for today —
     *   switching between two saved places (home ↔ work) must not flash a spinner over a render the
     *   cache repaint can produce correctly.
     */
    fun shouldShowInterstitial(
        userInitiated: Boolean,
        previous: Pair<Double, Double>?,
        newLat: Double,
        newLon: Double,
        hasCachedRowsAtNewSite: Boolean,
    ): Boolean {
        if (!userInitiated) return false
        if (hasCachedRowsAtNewSite) return false
        return !isSameSite(previous, newLat, newLon)
    }

    /** What a location change puts on screen while its first fetch runs. */
    enum class Feedback {
        /** Background move, or no real move: leave the display to the normal repaints. */
        NONE,

        /**
         * A "Getting weather for {place}…" banner over whatever is already drawn — the previous
         * site's graph included. The user asked for this over a blank placeholder (2026-09-28):
         * the old graph plus a message saying the new one is coming beats a screen with nothing
         * on it, and a toast lasts ~4 s against a fetch that takes 10–30.
         */
        BANNER,

        /** Full-screen "Getting weather for {place}…": only when there is nothing to keep showing. */
        INTERSTITIAL,
    }

    /**
     * @param hasRenderToKeep true when the widget/popup already shows something worth keeping
     *   under a banner — the previous site's render, or the new site's cached rows for today.
     */
    fun feedback(userInitiated: Boolean, siteChanged: Boolean, hasRenderToKeep: Boolean): Feedback = when {
        !userInitiated || !siteChanged -> Feedback.NONE
        hasRenderToKeep -> Feedback.BANNER
        else -> Feedback.INTERSTITIAL
    }

    /**
     * The one definition of `hasCachedRowsAtNewSite`: a real (non-climate-normal) forecast row whose
     * date is [today]. "Any cached row" is not enough — a site last visited two weeks ago still
     * returns its old daily rows, none of which fall in the visible window, and adopting that cache
     * painted an empty graph for the whole first fetch (desktop, 2026-09-28).
     */
    fun hasTodayRow(dailyRows: Iterable<DailyForecast>, today: LocalDate): Boolean {
        val todayIso = today.toString()
        return dailyRows.any { !it.isClimateNormal && it.date == todayIso }
    }

    /**
     * Short place name for transient messages, or null when the label can't say which component
     * is the place. Nominatim display names lead with the most specific component, which is the
     * city for a city search ("Warsaw, Masovian Voivodeship, Poland") but a house number for an
     * address ("860, Avery Drive, Mountain View, …") and the postcode for a ZIP search ("94043,
     * Mountain View, …"). Those two are indistinguishable from the label alone, so a letterless
     * lead component (and bare coordinates) gives null; the caller says "the new location" until
     * a reverse lookup's structured name arrives.
     */
    fun shortPlaceNameOrNull(label: String): String? {
        val first = label.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return first.takeIf { part -> part.any { it.isLetter() } }
    }

    /** True when the change stayed inside one site (jitter), so nothing on screen is wrong. */
    fun isSameSite(previous: Pair<Double, Double>?, newLat: Double, newLon: Double): Boolean =
        previous != null && LocationMatch.sameSite(previous.first, previous.second, newLat, newLon)
}
