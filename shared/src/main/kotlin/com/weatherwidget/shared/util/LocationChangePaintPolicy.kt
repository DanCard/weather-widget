package com.weatherwidget.shared.util

import com.weatherwidget.data.local.LocationMatch

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

    /** True when the change stayed inside one site (jitter), so nothing on screen is wrong. */
    fun isSameSite(previous: Pair<Double, Double>?, newLat: Double, newLon: Double): Boolean =
        previous != null && LocationMatch.sameSite(previous.first, previous.second, newLat, newLon)
}
